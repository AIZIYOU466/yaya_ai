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
    ToolResult {
        name: String,
        ok: bool,
        content: String,
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
