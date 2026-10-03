//! YAYai Agent Core（AGENTS.md R6 唯一规范源）。
//!
//! - [`agent::router`]：三层模型路由策略
//! - [`agent::state`]：任务状态机
//! - [`agent::run`]：ReAct 循环机
//! - [`agent::tools`]：统一工具层（function-calling）
//! - [`agent::openai`]：OpenAI 兼容协议解析（流式 tool_calls）
//!
//! 定位：聊天与开发者助手。平台差异经 [`ActionExecutor`] / [`ModelBackend`] /
//! [`McpClient`] 三个 trait 注入（Android 经 JNI 调用）。

pub mod agent;

pub use agent::{
    complexity, route, run_loop, Action, ActionExecutor, AgentCore, Backend, Complexity, Event,
    GenerateRequest, Message, ModelBackend, ModelOutput, RouteHints, RunConfig, TaskMachine,
    TaskState, ToolCall, ToolSpec,
};