//! 子代理（ROADMAP 任务 8）：把子任务交给独立的循环实例执行（独立上下文）。
//!
//! 实现为 core 内**顺序递归**：`subagent` 工具调用时以同一组平台能力（executor /
//! backends / approver / memory）递归运行 `run_loop`，子任务完成后把最终文本回填
//! 给主任务。子任务受同一运行模式策略约束（BUILD 下其内部不可逆工具仍需确认）。
//! 并行化（真多线程）留待后续：顺序递归已保证功能闭环且纯 core 可测。

use serde_json::{json, Value};

use super::model::{ToolCall, ToolSpec};
use super::{run_loop, AgentCore, Event, RunConfig};

pub const TOOL_SUBAGENT: &str = "subagent";

/// 子代理工具的模型可见规格。
pub fn spec() -> ToolSpec {
    ToolSpec {
        name: TOOL_SUBAGENT.into(),
        description: "把子任务交给一个独立的子代理循环执行（独立上下文），返回其最终结果文本；\
                      子任务内的工具调用同样受当前运行模式策略约束"
            .into(),
        parameters: json!({
            "type": "object",
            "properties": {
                "task": {"type": "string", "description": "子任务指令"},
                "max_steps": {"type": "integer", "description": "子任务最大步数（默认 5）"}
            },
            "required": ["task"],
            "additionalProperties": false
        }),
    }
}

/// 执行一次 `subagent` 调用：递归运行子循环，返回 (是否成功, 最终文本)。
/// 子任务配置继承父级（system prompt / model / mode / skills），步数取参数与父级上限的较小者。
pub fn run_subagent(
    core: &mut AgentCore,
    call: &ToolCall,
    parent_cfg: &RunConfig,
    on_event: &mut dyn FnMut(Event) -> Result<(), String>,
) -> (bool, String) {
    let args: Value = serde_json::from_str(&call.function.arguments).unwrap_or(Value::Null);
    let task = args.get("task").and_then(|t| t.as_str()).unwrap_or("");
    if task.is_empty() {
        return (false, "subagent 缺少必填参数 task".to_string());
    }
    let want_steps = args
        .get("max_steps")
        .and_then(|s| s.as_u64())
        .map(|s| s as usize)
        .unwrap_or(5);
    let sub_cfg = RunConfig {
        system_prompt: parent_cfg.system_prompt.clone(),
        model: parent_cfg.model.clone(),
        max_steps: want_steps.min(parent_cfg.max_steps),
        max_tokens: parent_cfg.max_tokens,
        max_messages: parent_cfg.max_messages,
        mode: parent_cfg.mode,
        skills_dir: parent_cfg.skills_dir.clone(),
        mcp_tool_allowlist: parent_cfg.mcp_tool_allowlist.clone(),
        capabilities: parent_cfg.capabilities,
        canary: false,
        degrade_state: parent_cfg.degrade_state.clone(),
    };
    match run_loop(core, task, &sub_cfg, on_event) {
        Ok(text) => (true, text),
        Err(e) => (false, e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spec_requires_task() {
        let s = spec();
        assert_eq!(s.name, "subagent");
        let required = s.parameters["required"].as_array().unwrap();
        assert!(required.iter().any(|v| v == "task"));
    }
}
