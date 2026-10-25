//! 降级状态机（commit 2）：capability / mode 分离。
//!
//! - `capability`：端点"能"做什么（静态能力，来自 config，见 `capability.rs`）。
//! - `mode`：当前"只"用什么（动态策略，随故障降级，见 [`DegradeMode`]）。
//!
//! 请求构造只看 `mode` 不看 `capability`；恢复靠金丝雀重探 + 指数退避防抖动。
//! 降级模式 = 「端点池的零号档」：单端点也有 failover 目标（切到降级而非切端点），
//! 加端点池只是多一个目标，架构不动。
//!
//! 状态持久化在 Android 侧（AGENTS.md R13：Rust Core 不持久化），经
//! [`DegradeState::snapshot`] / [`DegradeState::from_snapshot`] 与 Kotlin 交换。

use serde::{Deserialize, Serialize};
use serde_json::Value;

use std::collections::HashMap;

/// 端点归一化 id：`scheme + host（小写）+ 完整 path`，去 trailing slash / query / fragment。
/// 规则「宁分勿合」：path 大小写保留，分裂只分桶细，合并会让不同端点行为历史互相污染。
/// 任务 42 的 `EndpointProfile.id` 与任务 43 序列化边界引用本函数（ROADMAP 4C-2）。
pub fn normalize_endpoint_id(base_url: &str) -> String {
    let no_frag = base_url.trim().split('#').next().unwrap_or("");
    let no_query = no_frag.split('?').next().unwrap_or("");
    let (scheme, rest) = match no_query.split_once("://") {
        Some((sc, rest)) => (sc.to_ascii_lowercase(), rest),
        None => (String::new(), no_query),
    };
    let (host, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    let host = host.to_ascii_lowercase();
    let path = path.trim_end_matches('/');
    if scheme.is_empty() {
        format!("{host}{path}")
    } else {
        format!("{scheme}://{host}{path}")
    }
}

/// 行为维度（4C-2 定义、4C-3 驱动）：与健康环 `DegradeMode` 正交，
/// 描述「请求构造上对工具调用的介入强度」。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum BehaviorDim {
    /// 无介入（正常 auto）。
    #[default]
    Normal,
    /// 中等介入（如 tool_choice 强化，4C-3 L2）。
    Boosted,
    /// 强制介入（如 tool_choice required 仅一轮，4C-3 L3）。
    Forced,
}

impl BehaviorDim {
    pub fn label(self) -> &'static str {
        match self {
            BehaviorDim::Normal => "normal",
            BehaviorDim::Boosted => "boosted",
            BehaviorDim::Forced => "forced",
        }
    }

    pub fn from_label(s: &str) -> Option<Self> {
        match s {
            "normal" => Some(BehaviorDim::Normal),
            "boosted" => Some(BehaviorDim::Boosted),
            "forced" => Some(BehaviorDim::Forced),
            _ => None,
        }
    }
}

/// per-endpoint 行为历史（per-endpoint 键控，键为 [`normalize_endpoint_id`] 的结果）。
/// 序列化边界按 4C-3 最终字段集一次定型（ROADMAP 任务 43 引用）。
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct BehaviorHistory {
    /// 观测到的轮次数（相对信号冷启动门槛：20 轮）。
    pub rounds: u32,
    /// 累计工具调用次数（活跃度基线：该端点「正常时」每轮有多少调用）。
    pub tool_calls: u32,
    /// 最近强动词命中（裁剪到观测窗口长度）。
    #[serde(default)]
    pub strong_verb_hits: Vec<String>,
    /// 最近意向词命中（裁剪到观测窗口长度）。
    #[serde(default)]
    pub intent_hits: Vec<String>,
    /// 4C-3 强制轮（L3 required）失败连续计数：独立于健康环 `ToolSchema` 计数，
    /// ≥3 触发一次 canary 仲裁；一次成功清零（现在定型，43 序列化边界按此）。
    #[serde(default)]
    pub forced_failure_count: u32,
    /// 最近一次成功的工具调用名（供观测/复盘定位「最后动作」）。
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_tool_call: Option<String>,
    /// 4C-3 仲裁冷却：canary 已触发且通过，同端点不再重复仲裁
    ///（直到一次成功清零或行为环降级才解除）。
    #[serde(default)]
    pub canary_cooling: bool,
}

impl BehaviorHistory {
    /// 记录一轮观测；命中列表裁剪到 `window`（观测窗口，ROADMAP 4C-2 = 50）。
    pub fn note_round(&mut self, tool_calls: u32, strong: Option<&str>, intent: Option<&str>, window: usize) {
        self.rounds = self.rounds.saturating_add(1);
        self.tool_calls = self.tool_calls.saturating_add(tool_calls);
        let push = |v: &mut Vec<String>, s: &str| {
            v.push(s.to_string());
            if v.len() > window {
                v.drain(..v.len() - window);
            }
        };
        if let Some(s) = strong {
            push(&mut self.strong_verb_hits, s);
        }
        if let Some(s) = intent {
            push(&mut self.intent_hits, s);
        }
    }
}

/// 当前生效的降级档位（动态策略，与静态 `Capabilities` 分离）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum DegradeMode {
    /// 全部能力可用。
    #[default]
    Full,
    /// 关 tool_calls + vision（模型只做纯文本；请求不带 tools、图片降级为文本占位）。
    NoTools,
    /// 全保守文本：NoTools 基础上再关流式。`BareText` 不可再降，是零号档的底部。
    BareText,
}

impl DegradeMode {
    pub fn label(self) -> &'static str {
        match self {
            DegradeMode::Full => "full",
            DegradeMode::NoTools => "no_tools",
            DegradeMode::BareText => "bare_text",
        }
    }

    pub fn from_label(s: &str) -> Option<Self> {
        match s {
            "full" => Some(DegradeMode::Full),
            "no_tools" => Some(DegradeMode::NoTools),
            "bare_text" => Some(DegradeMode::BareText),
            _ => None,
        }
    }

    /// 请求是否携带 tools 字段（NoTools 及以下不带）。
    pub fn tools_enabled(self) -> bool {
        matches!(self, DegradeMode::Full)
    }

    /// 图片消息是否原样发送（NoTools 及以下降级为文本占位）。
    pub fn vision_enabled(self) -> bool {
        matches!(self, DegradeMode::Full)
    }

    /// 是否使用流式（BareText 关流式，退到最保守的整包请求）。
    pub fn streaming_enabled(self) -> bool {
        !matches!(self, DegradeMode::BareText)
    }
}

/// 触发降级计数的失败信号来源。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DegradeSignal {
    /// tool_call 协议污染（ID 异常、流内 error 载荷、空流等）。
    ToolProtocol,
    /// tool_call arguments 非法 JSON（schema 层面，阈值比整体污染宽松）。
    ToolSchema,
    /// 首 token 超时（send 到首个 data 行）。
    FirstTokenTimeout,
    /// 流中分片停顿（返回一半卡住）。
    ChunkStall,
    /// HTTP 5xx。
    Http5xx,
}

impl DegradeSignal {
    /// 各信号的连续失败阈值（达到即升级一档）。
    pub fn threshold(self) -> u32 {
        match self {
            // 严重：连续 3 次 tool_call 协议污染：关 tools + vision。
            DegradeSignal::ToolProtocol => 3,
            // 中度：连续 5 次 schema 校验失败：关 tools。
            DegradeSignal::ToolSchema => 5,
            // 严重：连续 2 次首 token 超时：关 tools + vision。
            DegradeSignal::FirstTokenTimeout => 2,
            // 网络脆弱：连续 3 次分片停顿：关 tools。
            DegradeSignal::ChunkStall => 3,
            // 轻度：连续 3 次 5xx：仅标记低置信，不降档。
            DegradeSignal::Http5xx => 3,
        }
    }
}

/// 降级状态机：计数 → 升级 / 成功清零 → 指数退避重探。纯 core 可测。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DegradeState {
    mode: DegradeMode,
    /// 各信号连续失败计数（`[ToolProtocol, ToolSchema, FirstTokenTimeout, ChunkStall, Http5xx]`）。
    fail_counts: [u32; 5],
    /// 当前档位下连续成功次数（恢复退避用）。
    success_streak: u32,
    /// 恢复退避轮次：允许重探前需 `2^round` 次连续成功。
    probe_round: u32,
    /// `success_streak` 已达标，等待金丝雀重探。
    probe_ready: bool,
    /// 金丝雀重探进行中（防止重复触发）。
    probing: bool,
    /// 轻度信号（5xx）触发的低置信标记，仅通知不改档。
    pub low_confidence: bool,
    /// 行为维度（4C-2 定义、4C-3 驱动）：与健康环正交的请求介入强度。
    behavior_dim: BehaviorDim,
    /// per-endpoint 行为历史（键为 [`normalize_endpoint_id`] 结果；无 base_url 用 "default" 桶）。
    behavior_history: HashMap<String, BehaviorHistory>,
}

impl DegradeState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn mode(&self) -> DegradeMode {
        self.mode
    }

    pub fn behavior_dim(&self) -> BehaviorDim {
        self.behavior_dim
    }

    /// 行为维度切换（4C-3 补偿层驱动；Forced→Boosted→Normal 回落也经此）。
    pub fn set_behavior_dim(&mut self, dim: BehaviorDim) {
        self.behavior_dim = dim;
    }

    /// 只读访问 per-endpoint 行为历史（不存在返回 `None`，不建桶——读不应有副作用）。
    pub fn behavior_for(&self, endpoint_id: &str) -> Option<&BehaviorHistory> {
        self.behavior_history.get(endpoint_id)
    }

    /// 可变访问（不存在则建桶），供轮次观测写入。
    pub fn behavior_for_mut(&mut self, endpoint_id: &str) -> &mut BehaviorHistory {
        self.behavior_history.entry(endpoint_id.to_string()).or_default()
    }

    /// 行为环升一档（Normal→Boosted→Forced；Forced 封顶）。返回新档。
    pub fn escalate_behavior(&mut self) -> BehaviorDim {
        self.behavior_dim = match self.behavior_dim {
            BehaviorDim::Normal => BehaviorDim::Boosted,
            BehaviorDim::Boosted => BehaviorDim::Forced,
            BehaviorDim::Forced => BehaviorDim::Forced,
        };
        self.behavior_dim
    }

    /// 行为环降一档（Forced→Boosted→Normal；Normal 封顶）。
    /// 一次成功调用清零该端点 forced_failure_count 与仲裁冷却（跟随健康环「成功清零」语义）。
    pub fn deescalate_behavior(&mut self, endpoint_id: &str) -> BehaviorDim {
        self.behavior_dim = match self.behavior_dim {
            BehaviorDim::Forced => BehaviorDim::Boosted,
            BehaviorDim::Boosted => BehaviorDim::Normal,
            BehaviorDim::Normal => BehaviorDim::Normal,
        };
        if let Some(h) = self.behavior_history.get_mut(endpoint_id) {
            h.forced_failure_count = 0;
            h.canary_cooling = false;
        }
        self.behavior_dim
    }

    fn idx(sig: DegradeSignal) -> usize {
        match sig {
            DegradeSignal::ToolProtocol => 0,
            DegradeSignal::ToolSchema => 1,
            DegradeSignal::FirstTokenTimeout => 2,
            DegradeSignal::ChunkStall => 3,
            DegradeSignal::Http5xx => 4,
        }
    }

    /// 记录一次失败信号。返回降级档位是否发生变化（供上层决定就地重试）。
    pub fn note_failure(&mut self, sig: DegradeSignal) -> bool {
        self.success_streak = 0;
        self.probe_ready = false;
        self.probing = false;
        let i = Self::idx(sig);
        self.fail_counts[i] = self.fail_counts[i].saturating_add(1);

        if sig == DegradeSignal::Http5xx && self.fail_counts[i] >= sig.threshold() {
            self.low_confidence = true;
            // 5xx 是轻度信号：只标记低置信，不升级档位。
            return false;
        }

        let threshold = sig.threshold();
        if self.fail_counts[i] < threshold {
            return false;
        }
        // 达标：升级一档（Ordering: Full → NoTools → BareText）。
        let before = self.mode;
        self.mode = match self.mode {
            DegradeMode::Full => DegradeMode::NoTools,
            DegradeMode::NoTools => DegradeMode::BareText,
            DegradeMode::BareText => DegradeMode::BareText,
        };
        self.probe_round = 0;
        self.fail_counts = [0; 5];
        before != self.mode
    }

    /// 记录一次成功。返回当前档位下连续成功是否触发"允许重探"。
    pub fn note_success(&mut self) -> bool {
        match self.mode {
            DegradeMode::Full => {
                self.fail_counts = [0; 5];
                self.success_streak = 0;
                self.probe_round = 0;
                self.probe_ready = false;
                false
            }
            _ => {
                // 按信号重置各自计数：任一失败信号在该成功下未达阈值，全部清零。
                self.fail_counts = [0; 5];
                self.success_streak = self.success_streak.saturating_add(1);
                let need = 1u32 << self.probe_round.min(5); // 1/2/4/8/16/32
                if self.success_streak >= need && !self.probing {
                    self.probe_ready = true;
                    true
                } else {
                    false
                }
            }
        }
    }

    /// 已降级且退避达标、可发起金丝雀重探。
    pub fn should_probe(&self) -> bool {
        self.mode != DegradeMode::Full && self.probe_ready && !self.probing
    }

    /// 开始金丝雀重探（防重复触发）。
    pub fn begin_probe(&mut self) {
        self.probing = true;
        self.probe_ready = false;
    }

    /// 结束重探：`restored` 表示探测通过、恢复 `Full`。
    pub fn end_probe(&mut self, restored: bool) {
        self.probing = false;
        if restored {
            self.mode = DegradeMode::Full;
            self.fail_counts = [0; 5];
            self.success_streak = 0;
            self.probe_round = 0;
            self.low_confidence = false;
        } else {
            // 失败 → 退避加倍，成功计数清零重来，等更长成功序列再探（防抖动）。
            self.success_streak = 0;
            self.probe_round = self.probe_round.saturating_add(1).min(10);
        }
    }

    /// 持久化快照（供 Kotlin 落盘；损坏时 `from_snapshot` 回退 Full，安全默认）。
    /// 序列化边界：新字段（5=行为维度、6=行为历史）追加在数组**尾部**，
    /// 旧消费端按位读前 5 项不受影响；旧快照（5 项）缺新字段回退默认。
    pub fn snapshot(&self) -> Value {
        let mut arr = vec![
            Value::String(self.mode.label().to_string()),
            Value::Array(self.fail_counts.iter().map(|&c| Value::from(c)).collect()),
            Value::from(self.success_streak),
            Value::from(self.probe_round),
            Value::from(self.low_confidence),
        ];
        arr.push(Value::String(self.behavior_dim.label().to_string()));
        arr.push(
            serde_json::to_value(&self.behavior_history)
                .unwrap_or_else(|_| Value::Object(serde_json::Map::new())),
        );
        Value::Array(arr)
    }

    /// 从快照恢复；非法/缺失回退 `Full`（安全默认）。
    pub fn from_snapshot(v: &Value) -> Self {
        let arr = v.as_array().and_then(|a| a.first()?.as_str());
        let Some(mode) = arr.and_then(DegradeMode::from_label) else {
            return Self::default();
        };
        let mut s = Self::default();
        s.mode = mode;
        if let Some(a) = v.as_array() {
            if let Some(counts) = a.get(1).and_then(|c| c.as_array()) {
                for (dst, src) in s.fail_counts.iter_mut().zip(counts.iter().take(5)) {
                    *dst = src.as_u64().unwrap_or(0) as u32;
                }
            }
            s.success_streak = a.get(2).and_then(|x| x.as_u64()).unwrap_or(0) as u32;
            s.probe_round = a.get(3).and_then(|x| x.as_u64()).unwrap_or(0) as u32;
            s.low_confidence = a.get(4).and_then(|x| x.as_bool()).unwrap_or(false);
            // 新字段在尾部（5/6）；旧快照缺失 → 保持默认（Normal / 空表）。
            if let Some(dim) = a.get(5).and_then(|x| x.as_str()).and_then(BehaviorDim::from_label) {
                s.behavior_dim = dim;
            }
            if let Some(obj) = a.get(6).and_then(|x| x.as_object()) {
                for (k, v) in obj {
                    if let Ok(h) = serde_json::from_value::<BehaviorHistory>(v.clone()) {
                        s.behavior_history.insert(k.clone(), h);
                    }
                }
            }
        }
        s.probe_ready = false;
        s.probing = false;
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fresh_state_is_full() {
        let s = DegradeState::new();
        assert_eq!(s.mode(), DegradeMode::Full);
        assert!(!s.should_probe());
    }

    #[test]
    fn tool_protocol_upgrade_after_three() {
        let mut s = DegradeState::new();
        assert!(!s.note_failure(DegradeSignal::ToolProtocol));
        assert!(!s.note_failure(DegradeSignal::ToolProtocol));
        assert!(s.note_failure(DegradeSignal::ToolProtocol));
        assert_eq!(s.mode(), DegradeMode::NoTools);
        assert!(!s.mode().tools_enabled());
        assert!(!s.mode().vision_enabled());
        assert!(s.mode().streaming_enabled());
    }

    #[test]
    fn first_token_upgrade_after_two() {
        let mut s = DegradeState::new();
        assert!(!s.note_failure(DegradeSignal::FirstTokenTimeout));
        assert!(s.note_failure(DegradeSignal::FirstTokenTimeout));
        assert_eq!(s.mode(), DegradeMode::NoTools);
    }

    #[test]
    fn no_tools_can_sink_to_bare_text() {
        let mut s = DegradeState::new();
        s.note_failure(DegradeSignal::ToolProtocol);
        s.note_failure(DegradeSignal::ToolProtocol);
        s.note_failure(DegradeSignal::ToolProtocol); // NoTools
        assert!(!s.note_failure(DegradeSignal::ToolProtocol));
        assert!(!s.note_failure(DegradeSignal::ToolProtocol));
        assert!(s.note_failure(DegradeSignal::ToolProtocol)); // BareText
        assert_eq!(s.mode(), DegradeMode::BareText);
        assert!(!s.mode().streaming_enabled());
    }

    #[test]
    fn schema_threshold_is_looser() {
        let mut s = DegradeState::new();
        for _ in 0..4 {
            assert!(!s.note_failure(DegradeSignal::ToolSchema));
        }
        assert!(s.note_failure(DegradeSignal::ToolSchema));
        assert_eq!(s.mode(), DegradeMode::NoTools);
    }

    #[test]
    fn http5xx_only_marks_low_confidence() {
        let mut s = DegradeState::new();
        for _ in 0..3 {
            s.note_failure(DegradeSignal::Http5xx);
        }
        assert_eq!(s.mode(), DegradeMode::Full); // 不降档
        assert!(s.low_confidence);
    }

    #[test]
    fn success_resets_counts_and_gates_probe() {
        let mut s = DegradeState::new();
        for _ in 0..3 {
            s.note_failure(DegradeSignal::ToolProtocol);
        }
        assert_eq!(s.mode(), DegradeMode::NoTools);
        // 成功一次不够（1 次），需 2^0=1 次 → 达标。
        assert!(s.note_success());
        assert!(s.should_probe());
        s.begin_probe();
        assert!(!s.should_probe());
        s.end_probe(true); // 探测通过 → 恢复
        assert_eq!(s.mode(), DegradeMode::Full);
        assert!(!s.low_confidence);
    }

    #[test]
    fn failed_probe_backs_off_exponentially() {
        let mut s = DegradeState::new();
        for _ in 0..3 {
            s.note_failure(DegradeSignal::ToolProtocol);
        }
        s.note_success();
        s.begin_probe();
        s.end_probe(false); // 探测失败 → 保持降级，退避翻倍
        assert_eq!(s.mode(), DegradeMode::NoTools);
        // 下一轮需 2 次成功才允许重探。
        assert!(!s.note_success());
        assert!(s.note_success());
        assert!(s.should_probe());
    }

    #[test]
    fn snapshot_roundtrip() {
        let mut s = DegradeState::new();
        for _ in 0..3 {
            s.note_failure(DegradeSignal::ToolProtocol);
        }
        let snap = s.snapshot();
        let r = DegradeState::from_snapshot(&snap);
        assert_eq!(r.mode(), DegradeMode::NoTools);
        assert_eq!(r.fail_counts, s.fail_counts);
        assert_eq!(r.success_streak, s.success_streak);
    }

    #[test]
    fn invalid_snapshot_falls_back_to_full() {
        let s = DegradeState::from_snapshot(&serde_json::json!({"garbage": 1}));
        assert_eq!(s.mode(), DegradeMode::Full);
    }

    #[test]
    fn downgrade_never_goes_below_bare_text() {
        let mut s = DegradeState::new();
        // 一路降到 BareText。
        for _ in 0..3 {
            s.note_failure(DegradeSignal::ToolProtocol);
        }
        for _ in 0..3 {
            s.note_failure(DegradeSignal::ToolProtocol);
        }
        assert_eq!(s.mode(), DegradeMode::BareText);
        // 继续失败仍停留 BareText。
        assert!(!s.note_failure(DegradeSignal::ToolProtocol));
        assert_eq!(s.mode(), DegradeMode::BareText);
    }

    // ---- 4C-2：端点归一化 ----

    #[test]
    fn normalize_endpoint_id_basic() {
        assert_eq!(
            normalize_endpoint_id("https://API.Example.COM/v1/chat?token=x#frag"),
            "https://api.example.com/v1/chat"
        );
        assert_eq!(
            normalize_endpoint_id("https://api.example.com/"),
            "https://api.example.com"
        );
        // 宁分勿合：path 大小写保留。
        assert_eq!(
            normalize_endpoint_id("HTTPS://Api.Example.COM/V1"),
            "https://api.example.com/V1"
        );
    }

    #[test]
    fn normalize_endpoint_id_no_scheme_and_empty() {
        assert_eq!(normalize_endpoint_id("api.example.com:8080/v1/"), "api.example.com:8080/v1");
        assert_eq!(normalize_endpoint_id(""), "");
        // 不同端点不得合并到同一桶。
        assert_ne!(
            normalize_endpoint_id("https://a.example.com"),
            normalize_endpoint_id("https://b.example.com")
        );
    }

    // ---- 4C-2：行为维度 / 行为历史 / 快照尾部追加 ----

    #[test]
    fn behavior_dim_default_is_normal() {
        assert_eq!(DegradeState::new().behavior_dim(), BehaviorDim::Normal);
        assert_eq!(BehaviorDim::Forced.label(), "forced");
        assert_eq!(BehaviorDim::from_label("boosted"), Some(BehaviorDim::Boosted));
        assert_eq!(BehaviorDim::from_label("?"), None);
    }

    #[test]
    fn behavior_history_note_round_counts_and_caps_window() {
        let mut h = BehaviorHistory::default();
        for i in 0..5 {
            h.note_round(
                if i % 2 == 0 { 1 } else { 0 },
                Some("已执行"),
                if i == 0 { Some("让我") } else { None },
                3,
            );
        }
        assert_eq!(h.rounds, 5);
        assert_eq!(h.tool_calls, 3);
        // 命中列表裁剪到窗口长度（保留最近 window 条）。
        assert_eq!(h.strong_verb_hits.len(), 3);
        assert_eq!(h.intent_hits, vec!["让我".to_string()]);
    }

    #[test]
    fn behavior_fields_roundtrip_in_snapshot_tail() {
        let mut s = DegradeState::new();
        s.set_behavior_dim(BehaviorDim::Forced);
        let h = s.behavior_for_mut("https://api.example.com/v1");
        h.note_round(2, Some("已运行"), Some("让我"), 50);
        h.forced_failure_count = 2;
        h.last_tool_call = Some("terminal_exec".into());

        let snap = s.snapshot();
        // 新字段在数组尾部（5/6），前 5 项顺序不变。
        let arr = snap.as_array().unwrap();
        assert_eq!(arr.len(), 7);
        assert_eq!(arr[5].as_str(), Some("forced"));
        assert!(arr[6].is_object());

        let r = DegradeState::from_snapshot(&snap);
        assert_eq!(r.behavior_dim(), BehaviorDim::Forced);
        let rh = r.behavior_for("https://api.example.com/v1").unwrap();
        assert_eq!(rh.rounds, 1);
        assert_eq!(rh.tool_calls, 2);
        assert_eq!(rh.forced_failure_count, 2);
        assert_eq!(rh.last_tool_call.as_deref(), Some("terminal_exec"));
    }

    #[test]
    fn legacy_five_element_snapshot_defaults_behavior() {
        // 旧格式快照（5 项）应正常解析，新字段回退默认。
        let old = serde_json::json!(["no_tools", [1, 2, 0, 0, 0], 3, 1, false]);
        let r = DegradeState::from_snapshot(&old);
        assert_eq!(r.mode(), DegradeMode::NoTools);
        assert_eq!(r.behavior_dim(), BehaviorDim::Normal);
        assert!(r.behavior_for("anything").is_none());
    }

    // ---- 4C-3：行为环升降档与成功清零 ----

    #[test]
    fn behavior_escalate_deescalate_and_clear_on_success() {
        let mut s = DegradeState::new();
        assert_eq!(s.escalate_behavior(), BehaviorDim::Boosted);
        assert_eq!(s.escalate_behavior(), BehaviorDim::Forced);
        assert_eq!(s.escalate_behavior(), BehaviorDim::Forced); // 封顶
        let h = s.behavior_for_mut("ep");
        h.forced_failure_count = 3;
        h.canary_cooling = true;
        // 一次成功调用降一档并清零计数与仲裁冷却。
        assert_eq!(s.deescalate_behavior("ep"), BehaviorDim::Boosted);
        let h = s.behavior_for("ep").unwrap();
        assert_eq!(h.forced_failure_count, 0);
        assert!(!h.canary_cooling);
        assert_eq!(s.deescalate_behavior("ep"), BehaviorDim::Normal);
        assert_eq!(s.deescalate_behavior("ep"), BehaviorDim::Normal); // 封顶
    }

    #[test]
    fn canary_cooling_roundtrips_in_snapshot_tail() {
        let mut s = DegradeState::new();
        s.behavior_for_mut("ep").canary_cooling = true;
        let r = DegradeState::from_snapshot(&s.snapshot());
        assert!(r.behavior_for("ep").unwrap().canary_cooling);
        // 旧快照（无该字段）回退默认 false。
        let old = serde_json::json!(["full", [0, 0, 0, 0, 0], 0, 0, false, "boosted", {"ep": {"rounds": 3, "tool_calls": 1}}]);
        let r2 = DegradeState::from_snapshot(&old);
        assert!(!r2.behavior_for("ep").unwrap().canary_cooling);
    }
}