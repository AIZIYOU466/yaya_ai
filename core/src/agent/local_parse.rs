//! 端侧小模型 function-calling（对应 AGENTS.md R6 工具层归属）。
//!
//! 2B-4B 端侧小模型（Qwen2.5-Instruct、Phi-3-mini 等）通常不原生返回 OpenAI 的
//! `tool_calls` 字段，而是以自然语言输出工具调用。本模块做两件事：
//! 1. 把工具 schema 与对话历史渲染成 ChatML/Hermes 风格的 function-calling prompt；
//! 2. 从模型纯文本输出中解析 `<tool_call>...</tool_call>` 块为 [`ToolCall`]。
//!
//! 跨端唯一实现：Android 经 JNI 的 `LocalBackend` 调用，禁止在 Kotlin/Dart 侧重复实现。

use serde_json::{json, Value};

use super::model::{Message, ToolCall, ToolSpec};
use super::openai::tool_schema;

const TC_OPEN: &str = "<tool_call>";
const TC_CLOSE: &str = "</tool_call>";

/// 把对话历史 + 工具 schema 渲染成端侧 function-calling prompt（ChatML 风格）。
/// `tools` 为空时退化为纯文本渲染（带角色标记）。
pub fn render_prompt(system: &str, tools: &[ToolSpec], messages: &[Message]) -> String {
    let mut out = String::new();
    out.push_str("<|im_start|>system\n");
    out.push_str(system);
    if !tools.is_empty() {
        out.push_str("\n\n你有以下可用工具（JSON Schema）：\n```json\n");
        out.push_str(&tool_schema(tools).to_string());
        out.push_str(
            "\n```\n需要在设备上执行动作或查询时，请调用工具。每个工具调用输出一个如下块：\n",
        );
        out.push_str(
            "<tool_call>\n{\"name\":\"工具名\",\"arguments\":{\"参数名\":\"值\"}}\n</tool_call>\n",
        );
        out.push_str("可以一次输出多个 <tool_call> 块，然后继续以文本总结。");
    }
    out.push_str("\n<|im_end|>\n");

    for m in messages {
        if m.role == "system" {
            continue; // system 已在开头单独放置
        }
        let role = match m.role.as_str() {
            "assistant" => "assistant",
            "tool" => "tool",
            _ => "user",
        };
        out.push_str("<|im_start|>");
        out.push_str(role);
        out.push('\n');
        if let Some(c) = &m.content {
            out.push_str(c.text_str());
            out.push('\n');
        }
        if let Some(calls) = &m.tool_calls {
            for c in calls {
                out.push_str(TC_OPEN);
                out.push('\n');
                out.push_str(&assistant_call_json(c).to_string());
                out.push('\n');
                out.push_str(TC_CLOSE);
                out.push('\n');
            }
        }
        out.push_str("<|im_end|>\n");
    }
    out.push_str("<|im_start|>assistant\n");
    out
}

/// 从模型纯文本输出中解析 `<tool_call>...</tool_call>` 块为工具调用。
/// 无法解析的块被跳过（不返回错误），供模型自我修正。
pub fn extract_tool_calls(text: &str) -> Vec<ToolCall> {
    let mut calls = Vec::new();
    let mut rest = text;
    while let Some(s) = rest.find(TC_OPEN) {
        let after = &rest[s + TC_OPEN.len()..];
        let Some(end) = after.find(TC_CLOSE) else {
            break;
        };
        let body = after[..end].trim();
        if let Ok(v) = serde_json::from_str::<Value>(body) {
            if let Some(call) = call_from_json(&v) {
                calls.push(call);
            }
        }
        rest = &after[end + TC_CLOSE.len()..];
    }
    calls
}

/// 助手 message 中的工具调用 → 端侧可见的 JSON（name + arguments 对象）。
fn assistant_call_json(c: &ToolCall) -> Value {
    let args: Value = serde_json::from_str(&c.function.arguments)
        .unwrap_or_else(|_| Value::String(c.function.arguments.clone()));
    json!({ "name": c.function.name, "arguments": args })
}

/// `<tool_call>` 块 JSON → ToolCall。arguments 可为对象或字符串。
fn call_from_json(v: &Value) -> Option<ToolCall> {
    let name = v.get("name")?.as_str()?.to_string();
    let arguments = match v.get("arguments") {
        Some(a) if a.is_string() => a.as_str().unwrap().to_string(),
        Some(a) => a.to_string(),
        None => "{}".to_string(),
    };
    Some(ToolCall {
        id: format!("call_{name}"),
        kind: "function".into(),
        function: super::model::FunctionCall { name, arguments },
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::model::FunctionCall;

    fn spec(name: &str) -> ToolSpec {
        ToolSpec {
            name: name.into(),
            description: "test".into(),
            parameters: json!({"type": "object"}),
        }
    }

    #[test]
    fn extracts_single_tool_call() {
        let text = "\n<tool_call>\n{\"name\":\"notify\",\"arguments\":{}}\n</tool_call>\n";
        let calls = extract_tool_calls(text);
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].function.name, "notify");
        assert_eq!(calls[0].function.arguments, "{}");
    }

    #[test]
    fn extracts_multiple_tool_calls_with_trailing_text() {
        let text = "先看结果\n<tool_call>\n{\"name\":\"notify\",\"arguments\":{}}\n</tool_call>\n然后\n<tool_call>\n{\"name\":\"clipboard_read\",\"arguments\":{}}\n</tool_call>\n完成";
        let calls = extract_tool_calls(text);
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].function.name, "notify");
        assert_eq!(calls[1].function.name, "clipboard_read");
        assert_eq!(calls[1].function.arguments, "{}");
    }

    #[test]
    fn object_arguments_serialized_to_json_string() {
        let text = "<tool_call>\n{\"name\":\"terminal_exec\",\"arguments\":{\"command\":\"ls\"}}\n</tool_call>";
        let calls = extract_tool_calls(text);
        assert_eq!(calls.len(), 1);
        let args: Value = serde_json::from_str(&calls[0].function.arguments).unwrap();
        assert_eq!(args["command"], json!("ls"));
    }

    #[test]
    fn malformed_blocks_are_skipped() {
        // 缺少结束标签 → 不解析；非法 JSON 块 → 跳过；普通文本不受影响
        assert!(extract_tool_calls("plain text no call").is_empty());
        assert!(extract_tool_calls("<tool_call>\nnot-json\n</tool_call>").is_empty());
        assert!(extract_tool_calls("<tool_call>\n{\"name\":\"x\"\n").is_empty());
    }

    #[test]
    fn render_prompt_injects_tool_schema_and_roles() {
        let messages = vec![
            Message::user("把字体调大"),
            Message::assistant_tool_calls(
                None,
                vec![ToolCall {
                    id: "c1".into(),
                    kind: "function".into(),
                    function: FunctionCall {
                        name: "observe_screen".into(),
                        arguments: "{}".into(),
                    },
                }],
            ),
            Message::tool_result("c1", "observe_screen", "读屏成功"),
        ];
        let prompt = render_prompt("你是 Agent", &[spec("notify")], &messages);
        assert!(prompt.contains("notify"), "应含工具 schema");
        assert!(prompt.contains("<|im_start|>user"));
        assert!(prompt.contains("把字体调大"));
        assert!(
            prompt.contains("<|im_start|>tool"),
            "tool 消息映射为 tool 角色"
        );
        assert!(prompt.contains("读屏成功"));
        assert!(
            prompt.contains("<|im_start|>assistant"),
            "应以 assistant 开头结束"
        );
        assert!(
            prompt.contains(TC_OPEN),
            "历史 tool_calls 附件应渲染为 <tool_call>"
        );
    }

    #[test]
    fn render_prompt_without_tools_is_plain() {
        let prompt = render_prompt("hi", &[], &[Message::user("hi")]);
        assert!(!prompt.contains("<tool_call>"));
        assert!(prompt.contains("<|im_start|>user"));
    }
}
