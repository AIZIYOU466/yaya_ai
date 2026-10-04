//! 能力探测与降级（ROADMAP 任务 13/39）：能力由 config 声明（`capabilities` 对象），
//! core 据此调整行为（上下文窗口钳制 token 预算等）。任务 39 加「探测协议化」：
//! 平台实现真实 HTTP 探测（R6 平台持有 HTTP），core 定义探测项、判定逻辑与结果缓存；
//! 探测缓存是任务 42 `EndpointProfile.capabilities` 的输入（声明 + 实测合并）。
//!
//! 探测判定为纯函数（`judge`），缓存 TTL 过期，保证探测路径纯 core 可测。

use serde_json::Value;
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// 模型/端点能力集（config 声明，可被探测结果覆盖）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Capabilities {
    /// 支持流式 tool_calls 分片（SSE）。
    pub streaming_tool_calls: bool,
    /// 支持 function-calling / tools 字段。
    pub function_calling: bool,
    /// 支持 usage 统计返回。
    pub usage_report: bool,
    /// 模型上下文窗口（token 数）。
    pub max_context_tokens: u32,
    /// 支持图片输入（多模态）。
    pub vision: bool,
}

impl Default for Capabilities {
    fn default() -> Self {
        Capabilities {
            streaming_tool_calls: true,
            function_calling: true,
            usage_report: true,
            max_context_tokens: 128_000,
            vision: true,
        }
    }
}

impl Capabilities {
    /// 由 config 的 `capabilities` 对象解析；缺省字段取默认值。
    pub fn from_config(v: Option<&Value>) -> Self {
        let Some(v) = v else {
            return Self::default();
        };
        let bool_of = |k: &str, default: bool| {
            v.get(k).and_then(|x| x.as_bool()).unwrap_or(default)
        };
        let max_ctx = v
            .get("maxContextTokens")
            .and_then(|x| x.as_u64())
            .and_then(|x| u32::try_from(x).ok())
            .filter(|x| *x > 0)
            .unwrap_or(128_000);
        Capabilities {
            streaming_tool_calls: bool_of("streamingToolCalls", true),
            function_calling: bool_of("functionCalling", true),
            usage_report: bool_of("usageReport", true),
            max_context_tokens: max_ctx,
            vision: bool_of("vision", true),
        }
    }

    /// 依能力钳制 token 预算：max_tokens 不超过上下文窗口的一半。
    pub fn clamp_max_tokens(&self, max_tokens: Option<u32>) -> Option<u32> {
        let cap = self.max_context_tokens / 2;
        match max_tokens {
            Some(t) => Some(t.min(cap.max(1024))),
            None => Some(cap.max(1024)),
        }
    }

    /// 依上下文窗口推导对话压缩阈值（条数）：窗口越小，越激进压缩。
    pub fn resolved_max_messages(&self, default: usize) -> usize {
        // 经验：每条消息约 200 token；压缩阈值取窗口可承载消息数的约 40%。
        let budget_msgs = (self.max_context_tokens as usize / 200) * 2 / 5;
        default.min(budget_msgs.max(6))
    }
}

// ── 探测协议（任务 39）──

/// 探测项：平台按 `request_hint` 构造并发送真实请求，观测交回 `judge` 判定。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ProbeItem {
    /// 发 `max_tokens = probe_max_tokens` 的短请求，验证端点尊重该上限（防超发/
    /// 防截断，复用 openai.rs `detect_max_tokens_tamper` 思路）；`measured` 给
    /// 出端点实际允许的生成量（上下文窗口下界）。
    MaxContextWindow { probe_max_tokens: u32 },
    /// 发带 tools 字段的请求，验证端点返回 tool_call（function-calling 支持）。
    FunctionCalling,
    /// 发 stream=true 请求，验证收到多片分片（SSE 支持）。
    Streaming,
    /// 验证响应含 usage 统计。
    UsageReport,
    /// 发带图片的消息，验证端点接受多模态输入。
    Vision,
}

impl ProbeItem {
    /// 稳定标识（缓存键/日志用）。
    pub fn id(&self) -> &'static str {
        match self {
            ProbeItem::MaxContextWindow { .. } => "max_context_window",
            ProbeItem::FunctionCalling => "function_calling",
            ProbeItem::Streaming => "streaming",
            ProbeItem::UsageReport => "usage_report",
            ProbeItem::Vision => "vision",
        }
    }

    /// 平台实现构造探测请求的要点（core 不发送 HTTP）。
    pub fn request_hint(&self) -> &'static str {
        match self {
            ProbeItem::MaxContextWindow { probe_max_tokens } => {
                "单轮 chat/completions：messages 只含一条短 user 消息，max_tokens = \
                 probe_max_tokens，stream=false；读取 usage.completion_tokens 与 finish_reason"
            }
            ProbeItem::FunctionCalling => {
                "带 tools=[{type:function,function:{name:probe_tool,...}}] 的请求；\
                 判定响应首个 choice 是否含 tool_call"
            }
            ProbeItem::Streaming => {
                "stream=true 请求；统计收到的 SSE 分片数（1 片=不支持流式）"
            }
            ProbeItem::UsageReport => {
                "普通短请求；判定响应是否含 usage 对象"
            }
            ProbeItem::Vision => {
                "user 消息含 image_url（1x1 PNG）；判定请求是否被接受（非 4xx/参数错误）"
            }
        }
    }
}

/// 平台把真实 HTTP 响应转成的原始观测（core 据此判定）。
#[derive(Debug, Clone, Default)]
pub struct ProbeObservation {
    /// 响应 usage.completion_tokens（None = 未返回 usage）。
    pub completion_tokens: Option<u32>,
    /// finish_reason（"length"/"stop"/None）。
    pub finish_reason: Option<String>,
    /// 是否含 tool_call。
    pub has_tool_call: bool,
    /// 流式分片数。
    pub chunks: usize,
    /// 图片消息是否被接受。
    pub vision_accepted: bool,
}

/// 判定结果：`ok` 表示端点按预期行为；`measured` 是实测数值（窗口下界）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProbeResult {
    pub item: ProbeItem,
    pub ok: bool,
    pub measured: Option<u32>,
    pub evidence: String,
}

impl ProbeResult {
    pub fn is_ok(&self) -> bool {
        self.ok
    }
}

/// 判定纯函数：探测项 + 观测 → 结果。
pub fn judge(item: &ProbeItem, obs: &ProbeObservation) -> ProbeResult {
    match item {
        ProbeItem::MaxContextWindow { probe_max_tokens } => {
            match obs.completion_tokens {
                None => ProbeResult {
                    item: *item,
                    ok: false,
                    measured: None,
                    evidence: "响应无 usage.completion_tokens".into(),
                },
                Some(c) => {
                    let over = c > probe_max_tokens.saturating_add(8);
                    if over {
                        // 端点忽略 max_tokens 超发（tamper），能力不可信。
                        ProbeResult {
                            item: *item,
                            ok: false,
                            measured: Some(c),
                            evidence: format!("端点超发：请求 max_tokens={probe_max_tokens}，实际生成 {c}"),
                        }
                    } else if obs.finish_reason.as_deref() == Some("length") {
                        // 截在 max_tokens 处：端点至少允许 probe_max_tokens 的生成量。
                        ProbeResult {
                            item: *item,
                            ok: true,
                            measured: Some((*probe_max_tokens).min(c)),
                            evidence: format!("端点尊重 max_tokens，生成 {c} 后 length 截断"),
                        }
                    } else {
                        // 任务提前结束，未触及上限：尊重 max_tokens 但测不出窗口。
                        ProbeResult {
                            item: *item,
                            ok: true,
                            measured: Some(c),
                            evidence: format!("端点尊重 max_tokens，生成 {c} 后正常结束"),
                        }
                    }
                }
            }
        }
        ProbeItem::FunctionCalling => ProbeResult {
            item: *item,
            ok: obs.has_tool_call,
            measured: None,
            evidence: if obs.has_tool_call {
                "响应含 tool_call".into()
            } else {
                "响应无 tool_call（不支持 function-calling 或未触发）".into()
            },
        },
        ProbeItem::Streaming => ProbeResult {
            item: *item,
            ok: obs.chunks > 1,
            measured: Some(obs.chunks as u32),
            evidence: format!("收到 {} 个分片", obs.chunks),
        },
        ProbeItem::UsageReport => ProbeResult {
            item: *item,
            ok: obs.completion_tokens.is_some(),
            measured: None,
            evidence: if obs.completion_tokens.is_some() {
                "响应含 usage".into()
            } else {
                "响应无 usage".into()
            },
        },
        ProbeItem::Vision => ProbeResult {
            item: *item,
            ok: obs.vision_accepted,
            measured: None,
            evidence: if obs.vision_accepted {
                "图片消息被接受".into()
            } else {
                "图片消息被拒绝".into()
            },
        },
    }
}

/// 真实探测由平台实现（R6：平台持有 HTTP）。core 只定义探测项与判定。
pub trait CapabilityProber: Send {
    /// 按 item 构造探测请求并发送，返回原始观测。
    fn probe(&mut self, item: &ProbeItem) -> Result<ProbeObservation, String>;
}

/// 探测结果缓存：TTL 内命中复用；过期/未命中重探。结果供任务 42
/// `EndpointProfile.capabilities` 合并使用。
#[derive(Debug, Clone)]
pub struct ProbeCache {
    ttl: Duration,
    entries: HashMap<ProbeItem, ProbeEntry>,
}

#[derive(Debug, Clone)]
struct ProbeEntry {
    result: ProbeResult,
    at: Instant,
}

impl ProbeCache {
    pub fn new(ttl: Duration) -> Self {
        ProbeCache {
            ttl,
            entries: HashMap::new(),
        }
    }

    /// TTL 内未过期的命中。
    pub fn get(&self, item: &ProbeItem) -> Option<ProbeResult> {
        self.entries.get(item).and_then(|e| {
            if e.at.elapsed() < self.ttl {
                Some(e.result.clone())
            } else {
                None
            }
        })
    }

    pub fn set(&mut self, result: ProbeResult) {
        self.entries.insert(result.item, ProbeEntry { result, at: Instant::now() });
    }

    /// 缓存优先；未命中/过期则调 prober 重探并写缓存。
    pub fn probe_or_get(
        &mut self,
        item: &ProbeItem,
        prober: &mut dyn CapabilityProber,
    ) -> Result<ProbeResult, String> {
        if let Some(r) = self.get(item) {
            return Ok(r);
        }
        let obs = prober.probe(item)?;
        let r = judge(item, &obs);
        self.set(r.clone());
        Ok(r)
    }
}

/// 声明能力 + 探测结果：`merged` 用探测 ok 项覆盖声明，失败/过期保持声明。
/// 合并结果即任务 42 `EndpointProfile.capabilities` 的输入。
#[derive(Debug, Clone)]
pub struct ProbedCapabilities {
    pub declared: Capabilities,
    pub cache: ProbeCache,
}

impl ProbedCapabilities {
    pub fn new(declared: Capabilities) -> Self {
        ProbedCapabilities {
            declared,
            cache: ProbeCache::new(Duration::from_secs(30 * 60)),
        }
    }

    /// 合并后的能力集：未探测的项保持声明；已探测的项（无论 ok 与否）用实测覆盖。
    /// 探测执行失败（Err）不写缓存，见 `probe_or_get`，因此这里不区分失败路径。
    pub fn merged(&self) -> Capabilities {
        let mut c = self.declared;
        for (item, entry) in &self.cache.entries {
            if entry.at.elapsed() >= self.cache.ttl {
                continue;
            }
            let r = &entry.result;
            match item {
                ProbeItem::MaxContextWindow { probe_max_tokens } => {
                    if let Some(m) = r.measured {
                        if !r.ok {
                            // 端点超发/无 usage：不可信，保守压到实测值。
                            c.max_context_tokens = m.min(c.max_context_tokens);
                        } else {
                            // 实测生成量下界；若端点连我们请求的一半都生成不了，
                            // 保守压到实测值（防超发截断），否则保留声明。
                            let half = probe_max_tokens / 2;
                            c.max_context_tokens = if m < half {
                                m
                            } else {
                                c.max_context_tokens
                            };
                        }
                    }
                }
                ProbeItem::FunctionCalling => c.function_calling = r.ok,
                ProbeItem::Streaming => c.streaming_tool_calls = r.ok,
                ProbeItem::UsageReport => c.usage_report = r.ok,
                ProbeItem::Vision => c.vision = r.ok,
            }
        }
        c
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    fn obs_with(c: Option<u32>, finish: Option<&str>) -> ProbeObservation {
        ProbeObservation {
            completion_tokens: c,
            finish_reason: finish.map(str::to_string),
            has_tool_call: false,
            chunks: 0,
            vision_accepted: false,
        }
    }

    #[test]
    fn judge_max_window_respects_limit() {
        // 截在 length：端点尊重 max_tokens，窗口至少 probe 值。
        let r = judge(
            &ProbeItem::MaxContextWindow { probe_max_tokens: 4096 },
            &obs_with(Some(4096), Some("length")),
        );
        assert!(r.ok);
        assert_eq!(r.measured, Some(4096));

        // 提前正常结束：尊重但测不出上限。
        let r = judge(
            &ProbeItem::MaxContextWindow { probe_max_tokens: 4096 },
            &obs_with(Some(12), Some("stop")),
        );
        assert!(r.ok);
        assert_eq!(r.measured, Some(12));
    }

    #[test]
    fn judge_max_window_tamper_overrun() {
        // 端点超发（忽略 max_tokens）→ 不可信。
        let r = judge(
            &ProbeItem::MaxContextWindow { probe_max_tokens: 32 },
            &obs_with(Some(500), Some("stop")),
        );
        assert!(!r.ok);
        assert_eq!(r.measured, Some(500));

        // 无 usage → 不可判定。
        let r = judge(
            &ProbeItem::MaxContextWindow { probe_max_tokens: 32 },
            &obs_with(None, None),
        );
        assert!(!r.ok);
        assert!(r.evidence.contains("无 usage"));
    }

    #[test]
    fn judge_feature_items() {
        let fc = ProbeObservation {
            has_tool_call: true,
            ..obs_with(Some(1), Some("stop"))
        };
        assert!(judge(&ProbeItem::FunctionCalling, &fc).ok);
        let no_fc = ProbeObservation {
            has_tool_call: false,
            ..obs_with(Some(1), Some("stop"))
        };
        assert!(!judge(&ProbeItem::FunctionCalling, &no_fc).ok);

        let stream = ProbeObservation {
            chunks: 3,
            ..obs_with(Some(3), None)
        };
        assert!(judge(&ProbeItem::Streaming, &stream).ok);
        let single = ProbeObservation {
            chunks: 1,
            ..obs_with(Some(1), None)
        };
        assert!(!judge(&ProbeItem::Streaming, &single).ok);

        let usage = ProbeObservation {
            completion_tokens: Some(5),
            ..Default::default()
        };
        assert!(judge(&ProbeItem::UsageReport, &usage).ok);
        let no_usage = ProbeObservation {
            completion_tokens: None,
            ..Default::default()
        };
        assert!(!judge(&ProbeItem::UsageReport, &no_usage).ok);

        let vis = ProbeObservation {
            vision_accepted: true,
            ..Default::default()
        };
        assert!(judge(&ProbeItem::Vision, &vis).ok);
    }

    #[test]
    fn merged_overrides_declared_on_success() {
        let mut pc = ProbedCapabilities::new(Capabilities {
            function_calling: true,
            vision: true,
            ..Default::default()
        });
        // 声明全 true；探测判 FunctionCalling/Vision 为 false → merged 降级。
        pc.cache.set(ProbeResult {
            item: ProbeItem::FunctionCalling,
            ok: false,
            measured: None,
            evidence: "无 tool_call".into(),
        });
        pc.cache.set(ProbeResult {
            item: ProbeItem::Vision,
            ok: false,
            measured: None,
            evidence: "图片被拒".into(),
        });
        let m = pc.merged();
        assert!(!m.function_calling);
        assert!(!m.vision);
        assert_eq!(m.max_context_tokens, 128_000, "无关能力保持声明");
    }

    #[test]
    fn merged_keeps_declared_when_unprobed() {
        // 未探测（缓存空）→ 保持声明，不降级猜测。
        let pc = ProbedCapabilities::new(Capabilities::default());
        let m = pc.merged();
        assert!(m.streaming_tool_calls);
        assert_eq!(m.max_context_tokens, 128_000);
    }

    #[test]
    fn merged_shrinks_window_on_tiny_measurement() {
        let mut pc = ProbedCapabilities::new(Capabilities {
            max_context_tokens: 128_000,
            ..Default::default()
        });
        // 端点对 4096 的探测请求只生成 100 就 length 截断 → 窗口实际很小，保守下调。
        pc.cache.set(ProbeResult {
            item: ProbeItem::MaxContextWindow { probe_max_tokens: 4096 },
            ok: true,
            measured: Some(100),
            evidence: "100 后 length".into(),
        });
        let m = pc.merged();
        assert_eq!(m.max_context_tokens, 100);
    }

    #[test]
    fn cache_ttl_and_probe_or_get() {
        struct CountingProber {
            calls: RefCell<u32>,
        }
        impl CapabilityProber for CountingProber {
            fn probe(&mut self, _item: &ProbeItem) -> Result<ProbeObservation, String> {
                *self.calls.borrow_mut() += 1;
                Ok(obs_with(Some(1), Some("stop")))
            }
        }
        let mut cache = ProbeCache::new(Duration::from_secs(60));
        let mut prober = CountingProber {
            calls: RefCell::new(0),
        };
        // 未命中 → 探测 1 次。
        let r1 = cache.probe_or_get(&ProbeItem::UsageReport, &mut prober).unwrap();
        assert!(r1.ok);
        // TTL 内命中 → 不再探测。
        let r2 = cache.probe_or_get(&ProbeItem::UsageReport, &mut prober).unwrap();
        assert_eq!(*prober.calls.borrow(), 1);
        assert_eq!(r1, r2);

        // 过期后 → 重探。
        cache.entries.get_mut(&ProbeItem::UsageReport).unwrap().at =
            Instant::now() - Duration::from_secs(61);
        let _ = cache.probe_or_get(&ProbeItem::UsageReport, &mut prober).unwrap();
        assert_eq!(*prober.calls.borrow(), 2);

        // 探测失败 → Err 传播且不缓存。
        struct FailingProber;
        impl CapabilityProber for FailingProber {
            fn probe(&mut self, _item: &ProbeItem) -> Result<ProbeObservation, String> {
                Err("http 500".into())
            }
        }
        let mut cache = ProbeCache::new(Duration::from_secs(60));
        assert!(cache.probe_or_get(&ProbeItem::Streaming, &mut FailingProber).is_err());
        assert!(cache.get(&ProbeItem::Streaming).is_none());
    }
}
