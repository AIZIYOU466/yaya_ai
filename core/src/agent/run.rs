//! ReAct 循环机（AGENTS.md R6）：观察 → 决策 → 流式生成 → 执行工具 → 回填，驱动任务状态机。
//!
//! 循环机是编排者：只经 [`crate::agent::AgentCore`] 的三个 trait 与模型后端工作，
//! 不直接接触终端 / llama.cpp / 平台细节。

use serde_json::Value;

use crate::agent::events::Event;
use crate::agent::model::{Content, GenerateRequest, Message};
use crate::agent::router::route;
use crate::agent::state::{TaskMachine, TaskState};
use crate::agent::tools;
use crate::agent::AgentCore;

pub struct RunConfig {
    pub system_prompt: String,
    pub model: Option<String>,
    /// 决策-执行的最大轮数，防止不收敛的死循环。
    pub max_steps: usize,
    pub max_tokens: Option<u32>,
    /// 对话历史超过该条数时压缩（保留开头 + 最近轮次），防长任务 token 膨胀。
    pub max_messages: usize,
}

impl Default for RunConfig {
    fn default() -> Self {
        RunConfig {
            system_prompt: default_system_prompt(),
            model: None,
            max_steps: 12,
            max_tokens: Some(1024),
            max_messages: 40,
        }
    }
}

pub fn default_system_prompt() -> String {
    "你是一个运行在安卓设备上的 AI 编程与开发助手。你可以通过内置的 proot Linux 终端\
     执行 shell 命令、运行构建与测试；通过剪贴板读写帮助用户搬运文本；通过通知提示任务进度。\
     若用户配置了 MCP 服务器，你还可以使用其提供的开发工具（文件、git 等）。\
     优先使用工具获取真实信息，不要臆测；若工具报错，请调整参数或换用其它工具，\
     并把过程和结果以清晰的文本呈现给用户。"
        .to_string()
}

/// 压缩对话历史：保留开头（通常为 system）+ 最近 `max-2` 条，中间截断并插入说明。
///
/// 截断边界保证不留下悬空的 tool 结果：若截断点从 role="tool" 开始，
/// 向后跳过这些消息（其对应的 assistant tool_calls 已在截断区间内一并丢弃），
/// 避免出现只有结果没有调用的半对。
pub fn compact_messages(messages: &mut Vec<Message>, max: usize) {
    if messages.len() <= max || max < 3 {
        return;
    }
    let keep_from = messages.len() - (max - 2);
    let mut start = keep_from;
    // 若保留下界恰好落在 tool 结果上，其对应的 assistant(tool_calls) 在下界之前，
    // 会被 drain 掉，从而留下「只有结果没有调用」的悬空 tool 消息（多数 OpenAI 兼容
    // API 会因此返回 400）。向前回退到最近的 assistant，让其与结果成对保留。
    while start > 1 && messages[start].role == "tool" {
        start -= 1;
    }
    messages.drain(1..start);
    messages.insert(
        1,
        Message::system("[较早的对话已被截断，请基于剩余上下文继续完成当前任务]"),
    );
}

/// 运行一次任务循环，事件经 `on_event` 实时上报。
/// 成功返回最终文本；`on_event` 返回 Err 表示调用方要求中止（取消）。
pub fn run_loop(
    core: &mut AgentCore,
    task: &str,
    cfg: &RunConfig,
    on_event: &mut dyn FnMut(Event) -> Result<(), String>,
) -> Result<String, String> {
    let mut machine = TaskMachine::new();
    let mut messages = vec![
        Message::system(cfg.system_prompt.clone()),
        Message::user(task),
    ];
    let mut tool_specs = tools::specs();
    if let Some(client) = core.mcp.as_mut() {
        match client.list_tools() {
            Ok(list) => tool_specs.extend(tools::mcp_specs(&list)),
            // 不静默：MCP 不可用时明确告知，并退回内置工具继续。
            Err(e) => on_event(Event::Notice {
                message: format!("MCP 工具不可用（{e}），本次仅使用内置工具"),
            })?,
        }
    }

    if let Err(e) = machine.transition(TaskState::Planning) {
        return fail(on_event, e);
    }
    emit_state(on_event, &machine)?;

    let mut final_text = String::new();

    for _step in 0..cfg.max_steps {
        // 每轮重新路由：后端可用性可能随网络/后端加载变化。
        let backend = match route(task, &core.effective_hints()) {
            Ok(b) => b,
            Err(e) => return fail(on_event, e),
        };

        if let Err(e) = machine.transition(TaskState::Executing) {
            return fail(on_event, e);
        }
        emit_state(on_event, &machine)?;

        let req = GenerateRequest {
            messages: messages.clone(),
            tools: tool_specs.clone(),
            model: cfg.model.clone(),
            max_tokens: cfg.max_tokens,
        };

        let output = {
            let Some(backend_impl) = core.backend_mut(backend) else {
                return fail(on_event, format!("路由选中的后端 {backend:?} 未注册"));
            };
            let mut on_token = |t: &str| {
                on_event(Event::Token {
                    text: t.to_string(),
                })
            };
            match backend_impl.generate(&req, &mut on_token) {
                Ok(o) => o,
                Err(e) => return fail(on_event, format!("生成失败（{backend:?}）: {e}")),
            }
        };

        if let Err(e) = machine.transition(TaskState::Streaming) {
            return fail(on_event, e);
        }
        emit_state(on_event, &machine)?;

        if !output.text.is_empty() {
            final_text = output.text.clone();
        }
        messages.push(Message::assistant_tool_calls(
            if output.text.is_empty() {
                None
            } else {
                Some(Content::text(output.text.clone()))
            },
            output.tool_calls.clone(),
        ));

        if output.tool_calls.is_empty() {
            if let Err(e) = machine.transition(TaskState::Done) {
                return fail(on_event, e);
            }
            emit_state(on_event, &machine)?;
            let _ = on_event(Event::Done {
                text: final_text.clone(),
            });
            return Ok(final_text);
        }

        // 本轮流式产出工具调用 → 回到 Executing 逐个执行并回填。
        if let Err(e) = machine.transition(TaskState::Executing) {
            return fail(on_event, e);
        }
        emit_state(on_event, &machine)?;

        // 执行工具并回填结果。
        for call in &output.tool_calls {
            let args: Value = serde_json::from_str(&call.function.arguments).unwrap_or(Value::Null);
            on_event(Event::ToolCall {
                name: call.function.name.clone(),
                args,
            })?;
            let (ok, content) = tools::dispatch(call, &mut *core.executor, core.mcp.as_deref_mut());
            on_event(Event::ToolResult {
                name: call.function.name.clone(),
                ok,
                content: content.clone(),
            })?;
            messages.push(Message::tool_result(&call.id, &call.function.name, content));
        }

        // 长任务：历史超出阈值时压缩，避免 token 膨胀导致后续轮次退化。
        compact_messages(&mut messages, cfg.max_messages);
    }

    fail(
        on_event,
        format!("达到最大步数 {}，任务未收敛", cfg.max_steps),
    )
}

fn emit_state(
    on_event: &mut dyn FnMut(Event) -> Result<(), String>,
    machine: &TaskMachine,
) -> Result<(), String> {
    on_event(Event::State {
        state: machine.state(),
    })
}

fn fail(
    on_event: &mut dyn FnMut(Event) -> Result<(), String>,
    message: String,
) -> Result<String, String> {
    let _ = on_event(Event::Error {
        message: message.clone(),
    });
    Err(message)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::executor::{Action, ActionExecutor};
    use crate::agent::mcp::{McpClient, McpTool};
    use crate::agent::model::{FunctionCall, ModelBackend, ModelOutput, ToolCall};
    use crate::agent::router::{Backend, RouteHints};
    use serde_json::json;
    use std::collections::VecDeque;
    use std::sync::{Arc, Mutex};

    struct ScriptedBackend {
        steps: VecDeque<ModelOutput>,
    }
    impl ModelBackend for ScriptedBackend {
        fn generate(
            &mut self,
            _req: &GenerateRequest,
            on_token: &mut dyn FnMut(&str) -> Result<(), String>,
        ) -> Result<ModelOutput, String> {
            let out = self.steps.pop_front().unwrap_or_default();
            if !out.text.is_empty() {
                on_token(&out.text)?;
            }
            Ok(out)
        }
        fn backend(&self) -> Backend {
            Backend::Cloud
        }
    }

    struct NoopExecutor;
    impl ActionExecutor for NoopExecutor {
        fn execute(&mut self, _a: &Action) -> Result<String, String> {
            Ok("done".into())
        }
    }

    fn core_with(steps: Vec<ModelOutput>) -> AgentCore {
        let mut core = AgentCore::new(Box::new(NoopExecutor));
        core.register_backend(Box::new(ScriptedBackend {
            steps: steps.into(),
        }));
        core.hints = RouteHints {
            force: Some(Backend::Cloud),
            ..Default::default()
        };
        core
    }

    fn tool_call_output() -> ModelOutput {
        ModelOutput {
            text: String::new(),
            tool_calls: vec![ToolCall {
                id: "c1".into(),
                kind: "function".into(),
                function: FunctionCall {
                    name: tools::TOOL_NOTIFY.into(),
                    arguments: r#"{"title":"t","body":"b"}"#.into(),
                },
            }],
        }
    }

    #[test]
    fn compact_messages_keeps_head_and_recent_tail() {
        let mut messages = vec![Message::system("sys")];
        for i in 0..10 {
            messages.push(Message::user(format!("turn{i}")));
            messages.push(Message::assistant(format!("reply{i}")));
        }
        compact_messages(&mut messages, 5);
        assert_eq!(messages[0].role, "system");
        assert_eq!(messages[0].content.as_ref().unwrap().text_str(), "sys");
        assert!(messages[1]
            .content
            .as_ref()
            .unwrap()
            .text_str()
            .contains("截断"));
        // 总长不超过 max（system + 标记 + 尾部）
        assert!(messages.len() <= 5);
        // 尾部保留最新轮次
        assert!(messages
            .last()
            .unwrap()
            .content
            .as_ref()
            .unwrap()
            .text_str()
            .contains("reply9"));
    }

    #[test]
    fn compact_messages_never_leaves_dangling_tool_results() {
        // 构造：system + 若干轮，每轮 assistant 带 tool_calls 并紧跟一条 tool 结果（合法序列），
        // 截断后不得留下只有结果没有调用的悬空 tool。
        let mut messages = vec![Message::system("sys")];
        for i in 0..5 {
            messages.push(Message::user(format!("u{i}")));
            messages.push(Message::assistant_tool_calls(
                None,
                vec![ToolCall {
                    id: format!("c{i}"),
                    kind: "function".into(),
                    function: FunctionCall {
                        name: "tap_node".into(),
                        arguments: "{}".into(),
                    },
                }],
            ));
            messages.push(Message::tool_result(
                format!("c{i}"),
                "tap_node",
                format!("r{i}"),
            ));
        }
        compact_messages(&mut messages, 5);
        assert!(messages.len() <= 5);
        assert!(
            !has_dangling_tool(&messages),
            "压缩后不得出现悬空的 tool 结果"
        );
    }

    /// 是否存在无前置 assistant(tool_calls) 的悬空 tool 结果。
    fn has_dangling_tool(messages: &[Message]) -> bool {
        messages
            .iter()
            .enumerate()
            .any(|(i, m)| m.role == "tool" && !messages[..i].iter().any(|p| p.tool_calls.is_some()))
    }

    #[test]
    fn compact_trailing_tool_run_keeps_assistant_pair() {
        // 单条 assistant 响应产出 40 个 tool_calls → 尾部 40 条 tool 结果全部挤在保留下界内，
        // 正是触发悬空 tool 回归的场景。
        let mut messages = vec![Message::system("sys"), Message::user("u")];
        messages.push(Message::assistant_tool_calls(None, vec![ToolCall {
            id: "c0".into(),
            kind: "function".into(),
            function: FunctionCall {
                name: "tap_node".into(),
                arguments: "{}".into(),
            },
        }]));
        for i in 0..40 {
            messages.push(Message::tool_result(format!("c{i}"), "tap_node", format!("r{i}")));
        }
        compact_messages(&mut messages, 40);
        assert!(
            !has_dangling_tool(&messages),
            "压缩后不应出现悬空的 tool 结果"
        );
    }

    #[test]
    fn compact_messages_noop_when_under_limit() {
        let mut messages = vec![Message::system("s"), Message::user("u")];
        let before = messages.clone();
        compact_messages(&mut messages, 40);
        assert_eq!(messages, before);
    }

    #[test]
    fn executes_tool_then_finishes() {
        let mut core = core_with(vec![
                tool_call_output(),
                ModelOutput {
                    text: "搞定".into(),
                    tool_calls: vec![],
                },
            ],
        );
        let mut events = Vec::new();
        let result = run_loop(&mut core, "看屏幕", &RunConfig::default(), &mut |e| {
            events.push(e);
            Ok(())
        });
        assert_eq!(result.unwrap(), "搞定");
        assert!(events
            .iter()
            .any(|e| matches!(e, Event::ToolResult { ok: true, .. })));
        assert!(events.iter().any(|e| matches!(e, Event::Done { .. })));
    }

    #[test]
    fn stops_at_max_steps_when_never_converges() {
        let mut cfg = RunConfig::default();
        cfg.max_steps = 3;
        let steps = vec![
            tool_call_output(),
            tool_call_output(),
            tool_call_output(),
            tool_call_output(),
        ];
        let mut core = core_with(steps);
        let err = run_loop(&mut core, "循环", &cfg, &mut |_| Ok(())).unwrap_err();
        assert!(err.contains("最大步数"));
    }

    #[test]
    fn cancel_via_event_sink_aborts() {
        let mut core = core_with(vec![
                tool_call_output(),
                ModelOutput {
                    text: "x".into(),
                    tool_calls: vec![],
                },
            ],
        );
        let err = run_loop(&mut core, "取消", &RunConfig::default(), &mut |e| match e {
            Event::ToolCall { .. } => Err("用户取消".to_string()),
            _ => Ok(()),
        })
        .unwrap_err();
        assert!(err.contains("取消"));
    }

    #[test]
    fn reports_error_when_no_backend_registered() {
        let mut core = AgentCore::new(Box::new(NoopExecutor));
        let err = run_loop(&mut core, "hi", &RunConfig::default(), &mut |_| Ok(())).unwrap_err();
        assert!(err.contains("无可用后端"));
    }

    struct FakeMcp {
        tools: Vec<McpTool>,
        fail_list: bool,
        calls: Arc<Mutex<Vec<(String, String)>>>,
    }
    impl McpClient for FakeMcp {
        fn list_tools(&mut self) -> Result<Vec<McpTool>, String> {
            if self.fail_list {
                return Err("服务器未启动".to_string());
            }
            Ok(self.tools.clone())
        }
        fn call_tool(&mut self, server: &str, tool: &str, _args: &Value) -> Result<String, String> {
            self.calls
                .lock()
                .unwrap()
                .push((server.to_string(), tool.to_string()));
            Ok(format!("mcp:{server}/{tool}"))
        }
    }

    fn mcp_call_output() -> ModelOutput {
        ModelOutput {
            text: String::new(),
            tool_calls: vec![ToolCall {
                id: "c9".into(),
                kind: "function".into(),
                function: FunctionCall {
                    name: "mcp__srv__echo".into(),
                    arguments: r#"{"v":1}"#.into(),
                },
            }],
        }
    }

    #[test]
    fn mcp_tool_call_is_routed_and_fed_back() {
        let calls = Arc::new(Mutex::new(Vec::new()));
        let mut core = core_with(vec![
                mcp_call_output(),
                ModelOutput {
                    text: "完成".into(),
                    tool_calls: vec![],
                },
            ],
        );
        core.register_mcp(Box::new(FakeMcp {
            tools: vec![McpTool {
                server: "srv".into(),
                name: "echo".into(),
                description: "回显".into(),
                parameters: json!({"type": "object"}),
            }],
            fail_list: false,
            calls: calls.clone(),
        }));

        let mut events = Vec::new();
        let out = run_loop(&mut core, "回声", &RunConfig::default(), &mut |e| {
            events.push(e);
            Ok(())
        })
        .unwrap();

        assert_eq!(out, "完成");
        assert_eq!(
            &*calls.lock().unwrap(),
            &[("srv".to_string(), "echo".to_string())]
        );
        assert!(events.iter().any(
            |e| matches!(e, Event::ToolResult { ok: true, content, .. } if content == "mcp:srv/echo")
        ));
    }

    #[test]
    fn mcp_list_failure_emits_notice_and_keeps_builtin_tools() {
        let mut core = core_with(vec![
                tool_call_output(),
                ModelOutput {
                    text: "ok".into(),
                    tool_calls: vec![],
                },
            ],
        );
        core.register_mcp(Box::new(FakeMcp {
            tools: Vec::new(),
            fail_list: true,
            calls: Arc::new(Mutex::new(Vec::new())),
        }));

        let mut events = Vec::new();
        let out = run_loop(&mut core, "看屏幕", &RunConfig::default(), &mut |e| {
            events.push(e);
            Ok(())
        })
        .unwrap();

        assert_eq!(out, "ok");
        assert!(events.iter().any(|e| matches!(e, Event::Notice { .. })));
        assert!(events
            .iter()
            .any(|e| matches!(e, Event::ToolResult { ok: true, .. })));
    }
}
