//! 工具授权策略：撤销成本分级 + 运行模式判定。
//!
//! 内置工具与 MCP 工具统一在此决策：PLAN 模式拦截写操作，BUILD 模式按撤销成本
//! 要求确认，AUTO 模式全部放行。需要确认时经 [`Approver`] 回调平台（Android：
//! 弹窗等待用户选择），未注册 `Approver` 时按拒绝处理（安全默认）。

use serde::{Deserialize, Serialize};

use super::memory;
use super::subagent;
use super::tools;
use super::workspace;

/// 工具的撤销成本等级：决定 BUILD 模式下是否需要执行前确认。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Reversibility {
    /// 可逆：执行后能直接撤销或副作用可忽略（通知、只读操作）。
    Reversible,
    /// 半可逆：需用户手动处理才能恢复（覆盖剪贴板等）。
    PartiallyReversible,
    /// 不可逆：无法自动恢复（任意终端命令、MCP 工具）。
    Irreversible,
}

/// 运行模式：决定 Agent 的自主程度。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum RunMode {
    /// 正常开发：写操作按撤销成本决定是否确认。
    #[default]
    Build,
    /// 只读规划：拦截一切写操作（工具层强制，非仅提示词约束）。
    Plan,
    /// 免授权：跳过所有确认。
    Auto,
}

/// 内置工具的撤销成本；未知工具（含全部 MCP 工具）按最坏情况处理。
pub fn reversibility_of(tool: &str) -> Reversibility {
    match tool {
        tools::TOOL_NOTIFY | tools::TOOL_CLIPBOARD_READ => Reversibility::Reversible,
        tools::TOOL_CLIPBOARD_WRITE => Reversibility::PartiallyReversible,
        // 记忆工具：内容可随时覆盖/删除，撤销成本低。
        memory::TOOL_MEMORY_LIST
        | memory::TOOL_MEMORY_READ
        | memory::TOOL_MEMORY_SAVE
        | memory::TOOL_MEMORY_EDIT
        | memory::TOOL_MEMORY_DELETE => Reversibility::Reversible,
        // 子代理：编排操作本身可撤销（其内部工具各自受策略约束）。
        subagent::TOOL_SUBAGENT => Reversibility::Reversible,
        // 工作区文件：读/列只读放行；写/编辑/删除不可逆需确认。
        workspace::TOOL_FILE_LIST | workspace::TOOL_FILE_READ => Reversibility::Reversible,
        workspace::TOOL_FILE_WRITE
        | workspace::TOOL_FILE_EDIT
        | workspace::TOOL_FILE_DELETE => Reversibility::Irreversible,
        _ => Reversibility::Irreversible,
    }
}

/// 工具是否可能产生写入/副作用（PLAN 模式据此拦截；只读记忆操作放行）。
pub fn is_write(tool: &str) -> bool {
    match tool {
        tools::TOOL_NOTIFY | tools::TOOL_CLIPBOARD_READ => false,
        memory::TOOL_MEMORY_LIST | memory::TOOL_MEMORY_READ => false,
        workspace::TOOL_FILE_LIST | workspace::TOOL_FILE_READ => false,
        _ => true,
    }
}

/// 单次调用的策略判定结果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    /// 直接执行。
    Allow,
    /// 需用户确认后执行。
    Ask,
    /// 拒绝执行（附面向模型的原因）。
    Deny(String),
}

impl Verdict {
    /// 面向事件流的短标识（`allow` / `ask` / `deny`）。
    pub fn as_str(&self) -> &'static str {
        match self {
            Verdict::Allow => "allow",
            Verdict::Ask => "ask",
            Verdict::Deny(_) => "deny",
        }
    }
}

/// 依运行模式与工具判定单次调用：放行 / 需确认 / 拒绝。
///
/// 判定顺序：PLAN 模式的写操作一律拒绝；其余模式按撤销成本决定
/// （BUILD 下不可逆操作需确认，AUTO 全部放行）。
pub fn verdict(mode: RunMode, tool: &str) -> Verdict {
    match mode {
        RunMode::Plan if is_write(tool) => {
            Verdict::Deny(format!("PLAN 模式为只读，禁止执行写操作工具 {tool}"))
        }
        RunMode::Plan | RunMode::Auto => Verdict::Allow,
        RunMode::Build => match reversibility_of(tool) {
            Reversibility::Irreversible => Verdict::Ask,
            _ => Verdict::Allow,
        },
    }
}

/// 一次待确认的工具调用（供平台向用户展示）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ApprovalRequest {
    pub tool: String,
    #[serde(default)]
    pub args: serde_json::Value,
    pub reversibility: Reversibility,
}

/// 平台实现：向用户请求确认，返回 `true` 表示允许执行。
pub trait Approver: Send {
    fn approve(&mut self, request: &ApprovalRequest) -> bool;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_mode_denies_writes_allows_reads() {
        assert!(matches!(
            verdict(RunMode::Plan, tools::TOOL_TERMINAL_EXEC),
            Verdict::Deny(_)
        ));
        assert!(matches!(
            verdict(RunMode::Plan, tools::TOOL_CLIPBOARD_WRITE),
            Verdict::Deny(_)
        ));
        assert!(matches!(
            verdict(RunMode::Plan, "mcp__files__read"),
            Verdict::Deny(_)
        ));
        assert_eq!(
            verdict(RunMode::Plan, tools::TOOL_CLIPBOARD_READ),
            Verdict::Allow
        );
        assert_eq!(verdict(RunMode::Plan, tools::TOOL_NOTIFY), Verdict::Allow);
    }

    #[test]
    fn build_mode_asks_only_for_irreversible() {
        assert_eq!(
            verdict(RunMode::Build, tools::TOOL_TERMINAL_EXEC),
            Verdict::Ask
        );
        assert_eq!(
            verdict(RunMode::Build, tools::TOOL_CLIPBOARD_WRITE),
            Verdict::Allow
        );
        assert_eq!(verdict(RunMode::Build, tools::TOOL_NOTIFY), Verdict::Allow);
        assert_eq!(
            verdict(RunMode::Build, tools::TOOL_CLIPBOARD_READ),
            Verdict::Allow
        );
    }

    #[test]
    fn auto_mode_allows_everything() {
        for t in [
            tools::TOOL_TERMINAL_EXEC,
            tools::TOOL_CLIPBOARD_WRITE,
            tools::TOOL_NOTIFY,
            "mcp__srv__danger",
        ] {
            assert_eq!(verdict(RunMode::Auto, t), Verdict::Allow, "AUTO 应放行 {t}");
        }
    }

    #[test]
    fn reversibility_mapping_is_conservative_for_unknown() {
        assert_eq!(
            reversibility_of(tools::TOOL_TERMINAL_EXEC),
            Reversibility::Irreversible
        );
        assert_eq!(
            reversibility_of(tools::TOOL_CLIPBOARD_WRITE),
            Reversibility::PartiallyReversible
        );
        assert_eq!(
            reversibility_of("mcp__x__y"),
            Reversibility::Irreversible
        );
        assert_eq!(reversibility_of("自定义"), Reversibility::Irreversible);
    }
}
