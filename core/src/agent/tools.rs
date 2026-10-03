//! 工具层（AGENTS.md R6）：把模型的 function-calling 调用翻译成平台的观察/动作，跨端唯一实现。

use serde_json::{json, Value};

use super::executor::{Action, ActionExecutor, ScrollDir, SystemKind};
use super::mcp::{self, McpClient, McpTool};
use super::model::{ToolCall, ToolSpec};
use super::observer::ScreenObserver;

pub const TOOL_OBSERVE_SCREEN: &str = "observe_screen";
pub const TOOL_TAP_NODE: &str = "tap_node";
pub const TOOL_INPUT_TEXT: &str = "input_text";
pub const TOOL_SCROLL_NODE: &str = "scroll_node";
pub const TOOL_SWIPE: &str = "swipe";
pub const TOOL_SYSTEM_ACTION: &str = "system_action";
pub const TOOL_LAUNCH_APP: &str = "launch_app";
pub const TOOL_TERMINAL_EXEC: &str = "terminal_exec";
pub const TOOL_OBSERVE_SCREEN_IMAGE: &str = "observe_screen_image";
pub const TOOL_CLIPBOARD_READ: &str = "clipboard_read";
pub const TOOL_CLIPBOARD_WRITE: &str = "clipboard_write";
pub const TOOL_NOTIFY: &str = "notify";

pub fn specs() -> Vec<ToolSpec> {
    vec![
        ToolSpec {
            name: TOOL_OBSERVE_SCREEN.into(),
            description: "读取当前屏幕的界面树（含稳定节点 id、文本、可点击/可编辑/可滚动标记）。执行任何点击/输入前应先调用。".into(),
            parameters: json!({"type": "object", "properties": {}, "additionalProperties": false}),
        },
        ToolSpec {
            name: TOOL_TAP_NODE.into(),
            description: "点击界面树中的节点。优先用 observe_screen 返回的 id；也可给绝对坐标 x/y。".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "id": {"type": "string", "description": "节点 id（来自 observe_screen）"},
                    "x": {"type": "integer"},
                    "y": {"type": "integer"}
                },
                "additionalProperties": false
            }),
        },
        ToolSpec {
            name: TOOL_INPUT_TEXT.into(),
            description: "向可编辑节点写入文本（覆盖原内容）".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "text": {"type": "string"}
                },
                "required": ["id", "text"],
                "additionalProperties": false
            }),
        },
        ToolSpec {
            name: TOOL_SCROLL_NODE.into(),
            description: "滚动指定节点（或当前可滚动容器）".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "direction": {"type": "string", "enum": ["forward", "backward"]}
                },
                "required": ["direction"],
                "additionalProperties": false
            }),
        },
        ToolSpec {
            name: TOOL_SWIPE.into(),
            description: "按坐标滑动手势（用于列表翻页、拖拽等）".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "from_x": {"type": "integer"},
                    "from_y": {"type": "integer"},
                    "to_x": {"type": "integer"},
                    "to_y": {"type": "integer"},
                    "duration_ms": {"type": "integer", "minimum": 1}
                },
                "required": ["from_x", "from_y", "to_x", "to_y"],
                "additionalProperties": false
            }),
        },
        ToolSpec {
            name: TOOL_SYSTEM_ACTION.into(),
            description: "系统导航操作：返回、回到主屏、最近任务".into(),
            parameters: json!({
                "type": "object",
                "properties": {"action": {"type": "string", "enum": ["back", "home", "recents"]}},
                "required": ["action"],
                "additionalProperties": false
            }),
        },
        ToolSpec {
            name: TOOL_LAUNCH_APP.into(),
            description: "按包名启动应用".into(),
            parameters: json!({
                "type": "object",
                "properties": {"package": {"type": "string"}},
                "required": ["package"],
                "additionalProperties": false
            }),
        },
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
            name: TOOL_OBSERVE_SCREEN_IMAGE.into(),
            description: "返回屏幕截图的 base64 编码供视觉分析。观察图片后如需点击图中元素（尤其无障碍树中不可见的区域），请用 tap_node 的 x/y 坐标参数定位，屏幕左上角为坐标原点。".into(),
            parameters: json!({"type": "object", "properties": {}, "additionalProperties": false}),
        },
        ToolSpec {
            name: TOOL_CLIPBOARD_READ.into(),
            description: "读取剪贴板中的文本内容".into(),
            parameters: json!({"type": "object", "properties": {}, "additionalProperties": false}),
        },
        ToolSpec {
            name: TOOL_CLIPBOARD_WRITE.into(),
            description: "将文本写入剪贴板（覆盖原内容），供用户在其它应用中粘贴".into(),
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
/// `observe_screen` 走观察 trait，其余内置工具走动作 trait；`mcp__*` 走 MCP 客户端。
pub fn dispatch(
    call: &ToolCall,
    observer: &mut dyn ScreenObserver,
    executor: &mut dyn ActionExecutor,
    mcp: Option<&mut (dyn McpClient + 'static)>,
) -> (bool, String) {
    let name = call.function.name.as_str();

    if let Some((server, tool)) = mcp::parse(name) {
        let Some(client) = mcp else {
            return (false, format!("MCP 未启用，无法调用 {name}"));
        };
        let args: Value = match serde_json::from_str(&call.function.arguments) {
            Ok(v) => v,
            Err(e) => return (false, format!("参数不是合法 JSON: {e}")),
        };
        return match client.call_tool(server, tool, &args) {
            Ok(text) => (true, text),
            Err(e) => (false, format!("MCP 调用失败: {e}")),
        };
    }

    let args: Value = match serde_json::from_str(&call.function.arguments) {
        Ok(v) => v,
        Err(e) => return (false, format!("参数不是合法 JSON: {e}")),
    };

    if name == TOOL_OBSERVE_SCREEN {
        return match observer.observe() {
            Ok(node) => (true, node.to_prompt_text()),
            Err(e) => (false, format!("读屏失败: {e}")),
        };
    }

    if name == TOOL_OBSERVE_SCREEN_IMAGE {
        return match observer.observe_image() {
            Ok(img_base64) => (true, img_base64),
            Err(e) => (false, format!("读屏失败: {e}")),
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
    // 拒绝超出 i32 范围的值，避免 `as i32` 把超大坐标静默截断成错误坐标。
    let i32_opt = |k: &str| {
        args.get(k)
            .and_then(|v| v.as_i64())
            .filter(|v| i32::try_from(*v).is_ok())
            .map(|v| v as i32)
    };
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
        TOOL_TAP_NODE => {
            let id = str_opt("id");
            let (x, y) = (i32_opt("x"), i32_opt("y"));
            if id.is_none() && (x.is_none() || y.is_none()) {
                return Err("tap_node 需要 id，或同时给出 x/y".to_string());
            }
            Ok(Action::Tap { id, x, y })
        }
        TOOL_INPUT_TEXT => Ok(Action::Input {
            id: need_str("id")?,
            text: need_str("text")?,
        }),
        TOOL_SCROLL_NODE => {
            let direction = match need_str("direction")?.as_str() {
                "forward" => ScrollDir::Forward,
                "backward" => ScrollDir::Backward,
                other => return Err(format!("未知滚动方向: {other}")),
            };
            Ok(Action::Scroll {
                id: str_opt("id"),
                direction,
            })
        }
        TOOL_SWIPE => Ok(Action::Swipe {
            from_x: i32_opt("from_x").ok_or("缺少 from_x")?,
            from_y: i32_opt("from_y").ok_or("缺少 from_y")?,
            to_x: i32_opt("to_x").ok_or("缺少 to_x")?,
            to_y: i32_opt("to_y").ok_or("缺少 to_y")?,
            duration_ms: u32_clamp("duration_ms", 300, 1, 5000),
        }),
        TOOL_SYSTEM_ACTION => {
            let kind = match need_str("action")?.as_str() {
                "back" => SystemKind::Back,
                "home" => SystemKind::Home,
                "recents" => SystemKind::Recents,
                other => return Err(format!("未知系统操作: {other}")),
            };
            Ok(Action::System { kind })
        }
        TOOL_LAUNCH_APP => Ok(Action::Launch {
            package: need_str("package")?,
        }),
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
    use crate::agent::observer::Node;
    use serde_json::{json, Value};

    struct FakeObserver;
    impl ScreenObserver for FakeObserver {
        fn observe(&mut self) -> Result<Node, String> {
            Ok(Node {
                id: "0".into(),
                class: "Frame".into(),
                clickable: true,
                ..Default::default()
            })
        }
        fn observe_image(&mut self) -> Result<String, String> {
            Ok("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcS9AAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==".to_string())
        }
    }

    struct FakeObserverNoImage;
    impl ScreenObserver for FakeObserverNoImage {
        fn observe(&mut self) -> Result<Node, String> {
            Ok(Node {
                id: "0".into(),
                class: "Frame".into(),
                clickable: true,
                ..Default::default()
            })
        }
        fn observe_image(&mut self) -> Result<String, String> {
            Err("图片观察未实现".to_string())
        }
    }

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
    fn observe_screen_renders_tree_text() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, content) = dispatch(&call(TOOL_OBSERVE_SCREEN, "{}"), &mut obs, &mut exec, None);
        assert!(ok);
        assert!(content.contains("[0] Frame"));
        assert!(content.contains("clickable"));
        assert!(exec.seen.is_empty());
    }

    #[test]
    fn observe_image_returns_base64() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, content) = dispatch(
            &call(TOOL_OBSERVE_SCREEN_IMAGE, "{}"),
            &mut obs,
            &mut exec,
            None,
        );
        assert!(ok);
        assert!(content.starts_with("data:image/"));
        assert!(exec.seen.is_empty());
    }

    #[test]
    fn observe_image_fails_gracefully() {
        let mut obs = FakeObserverNoImage;
        let mut exec = RecordingExecutor::default();
        let (ok, msg) = dispatch(
            &call(TOOL_OBSERVE_SCREEN_IMAGE, "{}"),
            &mut obs,
            &mut exec,
            None,
        );
        assert!(!ok);
        assert!(msg.contains("图片观察"));
        assert!(exec.seen.is_empty());
    }

    #[test]
    fn tap_maps_to_action() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, _) = dispatch(
            &call(TOOL_TAP_NODE, r#"{"id":"0/1"}"#),
            &mut obs,
            &mut exec,
            None,
        );
        assert!(ok);
        assert_eq!(
            exec.seen,
            vec![Action::Tap {
                id: Some("0/1".into()),
                x: None,
                y: None
            }]
        );
    }

    #[test]
    fn tap_requires_id_or_coords() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, msg) = dispatch(&call(TOOL_TAP_NODE, "{}"), &mut obs, &mut exec, None);
        assert!(!ok);
        assert!(msg.contains("需要 id"));
        assert!(exec.seen.is_empty());
    }

    #[test]
    fn terminal_defaults_timeout() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, _) = dispatch(
            &call(TOOL_TERMINAL_EXEC, r#"{"command":"ls"}"#),
            &mut obs,
            &mut exec,
            None,
        );
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
    fn unknown_tool_and_bad_args_are_reported_not_panicking() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, msg) = dispatch(&call("nope", "{}"), &mut obs, &mut exec, None);
        assert!(!ok);
        assert!(msg.contains("未知工具"));

        let (ok2, msg2) = dispatch(&call(TOOL_TAP_NODE, "not json"), &mut obs, &mut exec, None);
        assert!(!ok2);
        assert!(msg2.contains("合法 JSON"));
    }

    #[test]
    fn specs_cover_all_tool_names() {
        let names: Vec<String> = specs().into_iter().map(|s| s.name).collect();
        for want in [
            TOOL_OBSERVE_SCREEN,
            TOOL_TAP_NODE,
            TOOL_INPUT_TEXT,
            TOOL_SCROLL_NODE,
            TOOL_SWIPE,
            TOOL_SYSTEM_ACTION,
            TOOL_LAUNCH_APP,
            TOOL_TERMINAL_EXEC,
            TOOL_OBSERVE_SCREEN_IMAGE,
            TOOL_CLIPBOARD_READ,
            TOOL_CLIPBOARD_WRITE,
            TOOL_NOTIFY,
        ] {
            assert!(names.iter().any(|n| n == want), "缺少工具 {want}");
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
        assert!(specs[0].description.contains("files"));
    }

    #[test]
    fn dispatch_routes_mcp_tool_to_client() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let mut client = FakeMcp { tools: Vec::new() };
        let (ok, content) = dispatch(
            &call("mcp__files__read", r#"{"path":"a"}"#),
            &mut obs,
            &mut exec,
            Some(&mut client),
        );
        assert!(ok);
        assert_eq!(content, "files/read");
        assert!(exec.seen.is_empty());
    }

    #[test]
    fn dispatch_reports_mcp_tool_without_client() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, msg) = dispatch(&call("mcp__files__read", "{}"), &mut obs, &mut exec, None);
        assert!(!ok);
        assert!(msg.contains("MCP 未启用"));
    }

    #[test]
    fn clipboard_and_notify_map_to_actions() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, _) = dispatch(
            &call(TOOL_CLIPBOARD_WRITE, r#"{"text":"hi"}"#),
            &mut obs,
            &mut exec,
            None,
        );
        assert!(ok);
        assert!(exec
            .seen
            .iter()
            .any(|a| *a == Action::ClipboardWrite { text: "hi".into() }));

        let (ok, _) = dispatch(
            &call(TOOL_NOTIFY, r#"{"title":"t","body":"b"}"#),
            &mut obs,
            &mut exec,
            None,
        );
        assert!(ok);
        assert!(exec.seen.iter().any(|a| *a
            == Action::Notify {
                title: "t".into(),
                body: "b".into()
            }));
    }

    #[test]
    fn clipboard_read_requires_no_args() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, _) = dispatch(&call(TOOL_CLIPBOARD_READ, "{}"), &mut obs, &mut exec, None);
        assert!(ok);
        assert!(exec.seen.iter().any(|a| *a == Action::ClipboardRead));
    }

    #[test]
    fn dispatch_routes_observe_screen_image() {
        let mut obs = FakeObserver;
        let mut exec = RecordingExecutor::default();
        let (ok, content) = dispatch(
            &call(TOOL_OBSERVE_SCREEN_IMAGE, "{}"),
            &mut obs,
            &mut exec,
            None,
        );
        assert!(ok);
        assert!(content.starts_with("data:image/"));
    }
}
