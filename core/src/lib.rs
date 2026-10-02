//! YAYai Agent Core（AGENTS.md R6 唯一规范源）。
//!
//! - [`router`]：三层模型路由策略（Android `ModelRouter.kt` 与其同构）
//! - [`state`]：任务状态机（桌面 AgentCore 服务与 Android `TaskState.kt` 与其同构）

pub mod router;
pub mod state;

pub use router::{complexity, route, Backend, Complexity, RouteHints, ROUTER_SPEC};
pub use state::{can_transition, TaskMachine, TaskState};
