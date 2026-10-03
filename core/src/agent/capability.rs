//! 能力探测与降级（ROADMAP 任务 13）：能力由 config 声明（`capabilities` 对象），
//! core 据此调整行为（上下文窗口钳制 token 预算等）。真实探测（HTTP）由平台完成；
//! 本模块只做能力模型的解析与应用，保证降级路径纯 core 可测。

use serde_json::Value;

/// 模型/端点能力集。
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
}

impl Default for Capabilities {
    fn default() -> Self {
        Capabilities {
            streaming_tool_calls: true,
            function_calling: true,
            usage_report: true,
            max_context_tokens: 128_000,
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_used_when_config_absent() {
        let c = Capabilities::from_config(None);
        assert!(c.function_calling);
        assert_eq!(c.max_context_tokens, 128_000);
    }

    #[test]
    fn parses_config_overrides() {
        let c = Capabilities::from_config(Some(&serde_json::json!({
            "streamingToolCalls": false,
            "maxContextTokens": 4096,
        })));
        assert!(!c.streaming_tool_calls);
        assert_eq!(c.max_context_tokens, 4096);
        assert!(c.function_calling);
    }

    #[test]
    fn clamps_max_tokens_to_half_window() {
        let c = Capabilities {
            max_context_tokens: 4096,
            ..Default::default()
        };
        assert_eq!(c.clamp_max_tokens(Some(8192)), Some(2048));
        assert_eq!(c.clamp_max_tokens(None), Some(2048));
        // 至少保留 1024（极小窗口场景）。
        let tiny = Capabilities { max_context_tokens: 100, ..Default::default() };
        assert_eq!(tiny.clamp_max_tokens(None), Some(1024));
    }

    #[test]
    fn small_window_shrinks_message_budget() {
        let small = Capabilities { max_context_tokens: 4096, ..Default::default() };
        assert!(small.resolved_max_messages(40) < 40);
        let big = Capabilities::default();
        assert_eq!(big.resolved_max_messages(40), 40);
    }
}
