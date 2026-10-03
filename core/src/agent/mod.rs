//! Agent Core（AGENTS.md R6）：循环机 + 任务状态机 + 模型路由 + 工具层（含 MCP）+ OpenAI 解析器。
//!
//! 跨端唯一实现：Android 经 JNI 调用本模块；平台差异由 [`ActionExecutor`] /
//! [`ModelBackend`] / [`McpClient`] 三个 trait 注入。

pub mod canary;
pub mod capability;
pub mod events;
pub mod executor;
pub mod local_parse;
pub mod mcp;
pub mod memory;
pub mod model;
pub mod openai;
pub mod permission;
pub mod router;
pub mod run;
pub mod skill;
pub mod state;
pub mod subagent;
pub mod tools;
pub mod verifier;
pub mod workspace;

#[cfg(feature = "cloud-http")]
pub mod cloud;

use std::collections::HashMap;

pub use model::ModelBackend;
pub use router::{Backend, RouteHints};

pub use events::Event;
pub use executor::{Action, ActionExecutor};
pub use mcp::{McpClient, McpTool};
pub use model::{
    Content, ContentPart, GenerateRequest, ImageUrl, Message, ModelOutput, ToolCall, ToolSpec,
};
pub use permission::{ApprovalRequest, Approver, Reversibility, RunMode, Verdict};
pub use memory::MemoryStore;
pub use workspace::FileAccess;
pub use router::{complexity, route, Complexity};
pub use run::{run_loop, RunConfig};
pub use state::{TaskMachine, TaskState};

/// 循环机运行所需的三类平台能力 + 已注册的模型后端。
pub struct AgentCore {
    backends: HashMap<Backend, Box<dyn ModelBackend>>,
    pub executor: Box<dyn ActionExecutor>,
    /// MCP 客户端（可选）；未注册时工具集仅含内置工具。
    pub mcp: Option<Box<dyn McpClient>>,
    /// 授权确认回调（可选）；未注册时需确认的工具按拒绝处理（安全默认）。
    pub approver: Option<Box<dyn Approver>>,
    /// 自动记忆存储（可选）；未注册时记忆工具不暴露、无清单注入。
    pub memory_store: Option<Box<dyn MemoryStore>>,
    /// 工作区文件访问（可选）；未注册时文件工具不暴露。
    pub file_access: Option<Box<dyn FileAccess>>,
    /// 调用方填入的静态路由信号（force / network_ok / latency_sensitive）；
    /// 可用性信号（local_ok / desktop_ok / cloud_ok）由 [`AgentCore::effective_hints`] 依后端注册情况推导。
    pub hints: RouteHints,
}

impl AgentCore {
    pub fn new(executor: Box<dyn ActionExecutor>) -> Self {
        AgentCore {
            backends: HashMap::new(),
            executor,
            mcp: None,
            approver: None,
            memory_store: None,
            file_access: None,
            hints: RouteHints::default(),
        }
    }

    /// 注册授权确认回调；未注册时需确认的工具按拒绝处理。
    pub fn register_approver(&mut self, approver: Box<dyn Approver>) {
        self.approver = Some(approver);
    }

    /// 注册自动记忆存储；未注册时记忆工具不暴露。
    pub fn register_memory_store(&mut self, store: Box<dyn MemoryStore>) {
        self.memory_store = Some(store);
    }

    /// 注册工作区文件访问；未注册时文件工具不暴露。
    pub fn register_file_access(&mut self, access: Box<dyn FileAccess>) {
        self.file_access = Some(access);
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