//! Agent Core（AGENTS.md R6）：循环机 + 任务状态机 + 模型路由 + 工具层（含 MCP）+ OpenAI 解析器。
//!
//! 跨端唯一实现：Android 经 JNI 调用本模块；平台差异由 [`ActionExecutor`] /
//! [`ModelBackend`] / [`McpClient`] 三个 trait 注入。

pub mod canary;
pub mod capability;
pub mod degrade;
pub mod eval;
pub mod events;
pub mod executor;
pub mod local_parse;
pub mod mcp;
pub mod memory;
pub mod model;
pub mod openai;
pub mod permission;
pub mod pool;
pub mod provider;
pub mod recorder;
pub mod router;
pub mod run;
pub mod signals;
pub mod skill;
pub mod state;
pub mod subagent;
pub mod tools;
pub mod verifier;
pub mod workspace;

#[cfg(feature = "cloud-http")]
pub mod cloud;

use std::collections::HashMap;
use std::sync::Arc;

pub use model::ModelBackend;
pub use router::{Backend, RouteHints};

pub use events::Event;
pub use events::NoticeTag;
pub use executor::{Action, ActionExecutor};
pub use mcp::{McpClient, McpTool};
pub use model::{
    BackendError, Content, ContentPart, FailureKind, GenerateRequest, ImageUrl, Message,
    ModelOutput, ToolCall, ToolSpec,
};
pub use permission::{ApprovalRequest, Approver, Reversibility, RunMode, Verdict};
pub use memory::MemoryStore;
pub use workspace::FileAccess;
pub use pool::{EndpointHealth, EndpointPool, EndpointProfile};
pub use provider::{ApiProtocol, ApiRequest, AuthStyle, SseParser};
pub use router::{complexity, route, Complexity};
pub use run::{run_loop, ActivitySignal, ActivityTracker, LoopStage, LoopState, RunConfig};
pub use signals::SignalSlots;
pub use state::{TaskMachine, TaskState};
pub use degrade::{BehaviorDim, BehaviorHistory, DegradeMode, DegradeSignal, DegradeState};
pub use eval::{evaluate, report_from, EvalReport, EvalSummary};

/// 循环机运行所需的三类平台能力 + 已注册的模型后端。
pub struct AgentCore {
    backends: HashMap<Backend, Box<dyn ModelBackend>>,
    /// 多端点实例（任务 25，按 EndpointProfile.id）：池选中端点后优先取实例，
    /// 缺省回退 `backends`（per-kind 单实例，兼容现状）。
    pub(crate) endpoint_backends: HashMap<String, Box<dyn ModelBackend>>,
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
    /// 降级状态机（capability/mode 分离）：记录失败信号、决定当前 mode。
    pub degrade: DegradeState,
    /// 平台环境信号槽（Android 层写入，core 决策）。
    pub signals: Arc<SignalSlots>,
    /// 子代理并行环境工厂（任务 32）：平台注入；存在时为每个并行子代理派生独立
    /// `AgentCore`，缺省为 `None`（子代理回退顺序递归）。
    pub(crate) subagent_fork: Option<Box<dyn subagent::SubagentFork>>,
}

impl AgentCore {
    pub fn new(executor: Box<dyn ActionExecutor>) -> Self {
        AgentCore {
            backends: HashMap::new(),
            endpoint_backends: HashMap::new(),
            executor,
            mcp: None,
            approver: None,
            memory_store: None,
            file_access: None,
            hints: RouteHints::default(),
            degrade: DegradeState::new(),
            signals: SignalSlots::new(),
            subagent_fork: None,
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

    /// 注册子代理并行环境工厂（任务 32）；未注册时子代理回退顺序递归。
    pub fn register_subagent_fork(&mut self, fork: Box<dyn subagent::SubagentFork>) {
        self.subagent_fork = Some(fork);
    }

    /// 注册 MCP 客户端；未调用则工具集仅含内置工具。
    pub fn register_mcp(&mut self, mcp: Box<dyn McpClient>) {
        self.mcp = Some(mcp);
    }

    /// 注册一个模型后端；同身份后注册者覆盖先注册者。
    pub fn register_backend(&mut self, backend: Box<dyn ModelBackend>) {
        self.backends.insert(backend.backend(), backend);
    }

    /// 注册按端点 id 命名的实例（任务 25 / 42 配套）：多提供商各有独立配置。
    pub fn register_endpoint(&mut self, endpoint_id: String, backend: Box<dyn ModelBackend>) {
        self.endpoint_backends.insert(endpoint_id, backend);
    }

    /// 按端点 id 取实例（只读探测用）。
    pub fn endpoint_backend(&self, id: &str) -> Option<&Box<dyn ModelBackend>> {
        self.endpoint_backends.get(id)
    }

    /// 按端点 id 取可变实例（生成用，任务 25）。
    pub fn endpoint_backend_mut(&mut self, id: &str) -> Option<&mut Box<dyn ModelBackend>> {
        self.endpoint_backends.get_mut(id)
    }

    /// 池中可用的实例 id 集（供路由校验端点 id 与同名后端一致）。
    pub fn has_endpoint(&self, id: &str) -> bool {
        self.endpoint_backends.contains_key(id)
    }

    pub fn has_backend(&self, backend: Backend) -> bool {
        self.backends.contains_key(&backend)
    }

    /// 由后端注册情况 + 静态信号推导出路由输入。
    pub fn effective_hints(&self) -> RouteHints {
        // 25：多端点实例（endpoint_backends）同样参与可用性判定。
        let has_kind = |k: Backend| {
            self.backends.get(&k).is_some()
                || self.endpoint_backends.values().any(|b| b.backend() == k)
        };
        let usable = |b: Backend| {
            self.backends.get(&b).map(|m| !m.is_stub()).unwrap_or(false)
                || self
                    .endpoint_backends
                    .values()
                    .any(|m| m.backend() == b && !m.is_stub())
        };
        RouteHints {
            force: self.hints.force,
            cloud_ok: has_kind(Backend::Cloud) && !self.signals.network_lost(),
            desktop_ok: has_kind(Backend::Desktop),
            local_ok: usable(Backend::Jni),
            network_ok: self.hints.network_ok && !self.signals.network_lost(),
            latency_sensitive: self.hints.latency_sensitive,
        }
    }

    pub(crate) fn backend_mut(&mut self, backend: Backend) -> Option<&mut Box<dyn ModelBackend>> {
        self.backends.get_mut(&backend)
    }
}