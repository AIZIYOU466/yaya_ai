//! 工具层（AGENTS.md R6）：把模型的 function-calling 调用翻译成平台的执行动作，跨端唯一实现。
//!
//! 定位「聊天与开发者助手」：仅保留终端、剪贴板、通知与 MCP 工具；
//! 无障碍/屏幕操控类工具已移除。

use serde_json::{json, Value};

use super::executor::{Action, ActionExecutor};
use super::mcp::{self, McpClient, McpTool};
use super::memory::{self, MemoryStore};
use super::model::{ToolCall, ToolSpec};

pub const TOOL_TERMINAL_EXEC: &str = "terminal_exec";
pub const TOOL_CLIPBOARD_READ: &str = "clipboard_read";
pub const TOOL_CLIPBOARD_WRITE: &str = "clipboard_write";
pub const TOOL_NOTIFY: &str = "notify";

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: TOOL_TERMINAL_EXEC.into(),
            description: "在设备终端容器（proot Debian）中执行 shell 命令，返回其输出".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "command": {"type": "string"},
                    "timeout_ms": {"type": "integer", "minimum": 100, "maximum": 120000}
                },
                "required": ["command"],
                "additionalProperties": false
            }),
        },
        ToolSpec {
            name: TOOL_CLIPBOARD_READ.into(),
            description: "读取剪贴板中的文本内容".into(),
            parameters: json!({"type": "object", "properties": {}, "additionalProperties": false}),
        },
        ToolSpec {
            name: TOOL_CLIPBOARD_WRITE.into(),
            description: "将文本写入剪贴板（覆盖原内容），供用户快速粘贴".into(),
            parameters: json!({
                "type": "object",
                "properties": {"text": {"type": "string"}},
                "required": ["text"],
                "additionalProperties": false
            }),
        },
        ToolSpec {
            name: TOOL_NOTIFY.into(),
            description: "发送一条本地通知（标题 + 正文），用于向用户提示任务进度或结果".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "body": {"type": "string"}
                },
                "required": ["title", "body"],
                "additionalProperties": false
            }),
        },
    ]
}

/// 把 MCP 工具转成模型可见的规格（名字命名空间为 `mcp__<server>__<tool>`）。
/// 名字无法安全路由的工具（含 `__`）不暴露给模型。
pub fn mcp_specs(tools: &[McpTool]) -> Vec<ToolSpec> {
    tools
        .iter()
        .filter_map(|t| {
            let name = mcp::qualify(&t.server, &t.name).ok()?;
            Some(ToolSpec {
                name,
                description: format!("[MCP:{}] {}", t.server, t.description),
                parameters: t.parameters.clone(),
            })
        })
        .collect()
}

/// 执行一次工具调用，返回 (是否成功, 面向模型的结果文本)。
/// 路由顺序：记忆工具（若注册）→ MCP 工具 → 内置工具。
pub fn dispatch<'a>(
    call: &ToolCall,
    executor: &mut dyn ActionExecutor,
    mcp: Option<&mut (dyn McpClient + 'static)>,
    memory: Option<&'a mut Box<dyn MemoryStore>>,
) -> (bool, String) {
    let name = call.function.name.as_str();
    let args: Value = match serde_json::from_str(&call.function.arguments) {
        Ok(v) => v,
        Err(e) => return (false, format!("参数不是合法 JSON: {e}")),
    };

    // 记忆工具（平台注册了 MemoryStore 才可能命中）。
    if let Some(store) = memory {
        if let Some(result) = memory::dispatch(name, &args, store.as_mut()) {
            return result;
        }
    }

    if let Some((server, tool)) = mcp::parse(name) {
        let Some(client) = mcp else {
            return (false, format!("MCP 未启用，无法调用 {name}"));
        };
        return match client.call_tool(server, tool, &args) {
            Ok(text) => (true, text),
            Err(e) => (false, format!("MCP 调用失败: {e}")),
        };
    }

    let action = match build_action(name, &args) {
        Ok(a) => a,
        Err(e) => return (false, e),
    };
    match executor.execute(&action) {
        Ok(msg) => (
            true,
            if msg.is_empty() {
                "执行成功".to_string()
            } else {
                msg
            },
        ),
        Err(e) => (false, format!("执行失败: {e}")),
    }
}

fn build_action(name: &str, args: &Value) -> Result<Action, String> {
    let str_opt = |k: &str| args.get(k).and_then(|v| v.as_str()).map(|s| s.to_string());
    // 钳制无符号时长参数到 [min, max]，防止模型传超大数据、或 `as u32` 溢出截断。
    let u32_clamp = |k: &str, default: u32, min: u32, max: u32| {
        args.get(k)
            .and_then(|v| v.as_u64())
            .and_then(|v| u32::try_from(v).ok())
            .map(|v| v.clamp(min, max))
            .unwrap_or(default)
    };
    let need_str = |k: &str| str_opt(k).ok_or_else(|| format!("缺少必填参数 {k}"));

    match name {
        TOOL_TERMINAL_EXEC => Ok(Action::Terminal {
            command: need_str("command")?,
            timeout_ms: u32_clamp("timeout_ms", 30000, 100, 120000),
        }),
        TOOL_CLIPBOARD_READ => Ok(Action::ClipboardRead),
        TOOL_CLIPBOARD_WRITE => Ok(Action::ClipboardWrite {
            text: need_str("text")?,
        }),
        TOOL_NOTIFY => Ok(Action::Notify {
            title: need_str("title")?,
            body: need_str("body")?,
        }),
        other => Err(format!("未知工具: {other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use crate::agent::mcp::{McpClient, McpTool};
    use crate::agent::model::{FunctionCall, ToolCall};
    use serde_json::{json, Value};

    struct FakeMcp {
        tools: Vec<McpTool>,
    }
    impl McpClient for FakeMcp {
        fn list_tools(&mut self) -> Result<Vec<McpTool>, String> {
            Ok(self.tools.clone())
        }
        fn call_tool(&mut self, server: &str, tool: &str, _args: &Value) -> Result<String, String> {
            Ok(format!("{server}/{tool}"))
        }
    }

    #[derive(Default)]
    struct RecordingExecutor {
        seen: Vec<Action>,
    }
    impl ActionExecutor for RecordingExecutor {
        fn execute(&mut self, action: &Action) -> Result<String, String> {
            self.seen.push(action.clone());
            Ok("ok".into())
        }
    }

    fn call(name: &str, args: &str) -> ToolCall {
        ToolCall {
            id: "c1".into(),
            kind: "function".into(),
            function: FunctionCall {
                name: name.into(),
                arguments: args.into(),
            },
        }
    }

    #[test]
    fn terminal_defaults_timeout() {
        let mut exec = RecordingExecutor::default();
        let (ok, _) = dispatch(&call(TOOL_TERMINAL_EXEC, r#"{"command":"ls"}"#), &mut exec, None, None);
        assert!(ok);
        assert_eq!(
            exec.seen,
            vec![Action::Terminal {
                command: "ls".into(),
                timeout_ms: 30000
            }]
        );
    }

    #[test]
    fn terminal_clamps_timeout() {
        let mut exec = RecordingExecutor::default();
        let (ok, _) = dispatch(
            &call(TOOL_TERMINAL_EXEC, r#"{"command":"ls","timeout_ms":99999999}"#),
            &mut exec,
            None,
            None,
        );
        assert!(ok);
        assert_eq!(
            exec.seen,
            vec![Action::Terminal {
                command: "ls".into(),
                timeout_ms: 120000
            }]
        );
    }

    #[test]
    fn unknown_tool_and_bad_args_are_reported_not_panicking() {
        let mut exec = RecordingExecutor::default();
        let (ok, msg) = dispatch(&call("nope", "{}"), &mut exec, None, None);
        assert!(!ok);
        assert!(msg.contains("未知工具"));

        let (ok2, msg2) = dispatch(&call(TOOL_NOTIFY, "not json"), &mut exec, None, None);
        assert!(!ok2);
        assert!(msg2.contains("合法 JSON"));
    }

    #[test]
    fn specs_cover_all_tool_names() {
        let names: Vec<String> = specs().into_iter().map(|s| s.name).collect();
        for want in [
            TOOL_TERMINAL_EXEC,
            TOOL_CLIPBOARD_READ,
            TOOL_CLIPBOARD_WRITE,
            TOOL_NOTIFY,
        ] {
            assert!(names.iter().any(|n| n == want), "缺少工具 {want}");
        }
        // 定位校验：不得再暴露设备操控类工具。
        for forbidden in [
            "observe_screen",
            "tap_node",
            "input_text",
            "scroll_node",
            "swipe",
            "system_action",
            "launch_app",
        ] {
            assert!(
                !names.iter().any(|n| n == forbidden),
                "设备操控工具 {forbidden} 应已移除"
            );
        }
    }

    #[test]
    fn mcp_specs_use_namespace_and_skip_unusable_names() {
        let tools = vec![
            McpTool {
                server: "files".into(),
                name: "read".into(),
                description: "读文件".into(),
                parameters: json!({"type": "object"}),
            },
            McpTool {
                server: "bad__name".into(),
                name: "x".into(),
                description: String::new(),
                parameters: json!({"type": "object"}),
            },
        ];
        let specs = mcp_specs(&tools);
        assert_eq!(specs.len(), 1);
        assert_eq!(specs[0].name, "mcp__files__read");
    }

    #[test]
    fn dispatch_routes_mcp_tool_to_client() {
        let mut exec = RecordingExecutor::default();
        let mut client = FakeMcp { tools: Vec::new() };
        let (ok, content) = dispatch(
            &call("mcp__files__read", r#"{"path":"a"}"#),
            &mut exec,
            Some(&mut client),
            None,
        );
        assert!(ok);
        assert_eq!(content, "files/read");
        assert!(exec.seen.is_empty());
    }

    #[test]
    fn dispatch_reports_mcp_tool_without_client() {
        let mut exec = RecordingExecutor::default();
        let (ok, msg) = dispatch(&call("mcp__files__read", "{}"), &mut exec, None, None);
        assert!(!ok);
        assert!(msg.contains("MCP 未启用"));
    }

    #[test]
    fn clipboard_and_notify_map_to_actions() {
        let mut exec = RecordingExecutor::default();

        let (ok, _) = dispatch(
            &call(TOOL_CLIPBOARD_WRITE, r#"{"text":"hi"}"#),
            &mut exec,
            None,
            None,
        );
        assert!(ok);
        assert!(exec
            .seen
            .iter()
            .any(|a| *a == Action::ClipboardWrite { text: "hi".into() }));

        let (ok, _) = dispatch(
            &call(TOOL_NOTIFY, r#"{"title":"t","body":"b"}"#),
            &mut exec,
            None,
            None,
        );
        assert!(ok);
        assert!(exec.seen.iter().any(|a| *a == Action::Notify {
            title: "t".into(),
            body: "b".into()
        }));
    }

    #[test]
    fn clipboard_read_requires_no_args() {
        let mut exec = RecordingExecutor::default();
        let (ok, _) = dispatch(&call(TOOL_CLIPBOARD_READ, "{}"), &mut exec, None, None);
        assert!(ok);
        assert!(exec.seen.iter().any(|a| *a == Action::ClipboardRead));
    }
}