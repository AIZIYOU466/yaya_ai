//! 循环机对外事件（AGENTS.md R6）。
//!
//! 同一份 JSON 协议供两条链路复用：
//! - Android：经 JNI sink 回调 → Kotlin → Dart
//! - 桌面：编码进 gRPC `TaskEvent.output`

use serde::Serialize;

use super::state::TaskState;

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Event {
    State {
        state: TaskState,
    },
    Token {
        text: String,
    },
    ToolCall {
        name: String,
        args: serde_json::Value,
    },
    /// 工具调用的策略判定（决策原因码）：供追溯每次调用为何被放行/确认/拒绝。
    /// `verdict` ∈ `allow` / `ask` / `deny`；`reason` 说明判定依据。
    ToolPolicy {
        name: String,
        verdict: String,
        reason: String,
    },
    ToolResult {
        name: String,
        ok: bool,
        content: String,
    },
    /// 一次生成的 token 用量（后端提供时发出），供 UI 累计与成本估算。
    Usage {
        prompt_tokens: u32,
        completion_tokens: u32,
        total_tokens: u32,
    },
    /// 非致命提示（如 MCP 不可用）：不中断循环，仅供用户参考。
    Notice {
        message: String,
    },
    Done {
        text: String,
    },
    Error {
        message: String,
    },
}

impl Event {
    pub fn to_json(&self) -> String {
        serde_json::to_string(self)
            .unwrap_or_else(|_| "{\"type\":\"error\",\"message\":\"event 序列化失败\"}".to_string())
    }
}
