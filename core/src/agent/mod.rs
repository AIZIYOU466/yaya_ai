//! Agent Core（AGENTS.md R6）：循环机 + 任务状态机 + 模型路由 + 工具层（含 MCP）+ OpenAI 解析器。
//!
//! 跨端唯一实现：Android 经 JNI 调用本模块；平台差异由 [`ScreenObserver`] /
//! [`ActionExecutor`] / [`ModelBackend`] / [`McpClient`] 四个 trait 注入。

pub mod events;
pub mod executor;
pub mod local_parse;
pub mod mcp;
pub mod model;
pub mod observer;
pub mod openai;
pub mod router;
pub mod run;
pub mod state;
pub mod tools;

#[cfg(feature = "cloud-http")]
pub mod cloud;

use std::collections::HashMap;

pub use model::ModelBackend;
pub use router::{Backend, RouteHints};

pub use events::Event;
pub use executor::{Action, ActionExecutor, ScrollDir, SystemKind};
pub use mcp::{McpClient, McpTool};
pub use model::{
    Content, ContentPart, GenerateRequest, ImageUrl, Message, ModelOutput, ToolCall, ToolSpec,
};
pub use observer::{Node, Rect, ScreenObserver};
pub use router::{complexity, route, Complexity};
pub use run::{run_loop, RunConfig};
pub use state::{TaskMachine, TaskState};

/// 循环机运行所需的四类平台能力 + 已注册的模型后端。
pub struct AgentCore {
    backends: HashMap<Backend, Box<dyn ModelBackend>>,
    pub observer: Box<dyn ScreenObserver>,
    pub executor: Box<dyn ActionExecutor>,
    /// MCP 客户端（可选）；未注册时工具集仅含内置工具。
    pub mcp: Option<Box<dyn McpClient>>,
    /// 调用方填入的静态路由信号（force / network_ok / latency_sensitive）；
    /// 可用性信号（local_ok / desktop_ok / cloud_ok）由 [`AgentCore::effective_hints`] 依后端注册情况推导。
    pub hints: RouteHints,
}

impl AgentCore {
    pub fn new(observer: Box<dyn ScreenObserver>, executor: Box<dyn ActionExecutor>) -> Self {
        AgentCore {
            backends: HashMap::new(),
            observer,
            executor,
            mcp: None,
            hints: RouteHints::default(),
        }
    }

    /// 注册 MCP 客户端；未调用则工具集仅含内置工具。
    pub fn register_mcp(&mut self, mcp: Box<dyn McpClient>) {
        self.mcp = Some(mcp);
    }

    /// 注册一个模型后端；同身份后注册者覆盖先注册者。
    pub fn register_backend(&mut self, backend: Box<dyn ModelBackend>) {
        self.backends.insert(backend.backend(), backend);
    }

    pub fn has_backend(&self, backend: Backend) -> bool {
        self.backends.contains_key(&backend)
    }

    /// 由后端注册情况 + 静态信号推导出路由输入。
    pub fn effective_hints(&self) -> RouteHints {
        let usable = |b: Backend| self.backends.get(&b).map(|m| !m.is_stub()).unwrap_or(false);
        RouteHints {
            force: self.hints.force,
            cloud_ok: self.backends.contains_key(&Backend::Cloud),
            desktop_ok: self.backends.contains_key(&Backend::Desktop),
            local_ok: usable(Backend::Jni),
            network_ok: self.hints.network_ok,
            latency_sensitive: self.hints.latency_sensitive,
        }
    }

    pub(crate) fn backend_mut(&mut self, backend: Backend) -> Option<&mut Box<dyn ModelBackend>> {
        self.backends.get_mut(&backend)
    }
}
