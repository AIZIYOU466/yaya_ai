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

use serde_json::Value;

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
}

impl DegradeState {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn mode(&self) -> DegradeMode {
        self.mode
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
    pub fn snapshot(&self) -> Value {
        Value::Array(vec![
            Value::String(self.mode.label().to_string()),
            Value::Array(self.fail_counts.iter().map(|&c| Value::from(c)).collect()),
            Value::from(self.success_streak),
            Value::from(self.probe_round),
            Value::from(self.low_confidence),
        ])
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
}