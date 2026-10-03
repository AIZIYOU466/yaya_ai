//! OpenAI 兼容协议的纯函数层（AGENTS.md R6）：请求体构造、工具 schema、SSE 增量解析。
//!
//! 跨端唯一实现，避免各端各写一份解析器导致漂移。

use serde_json::{json, Value};

use super::model::{FunctionCall, GenerateRequest, Message, ModelOutput, ToolCall, ToolSpec};

/// 工具 schema → OpenAI `tools` 数组。
pub fn tool_schema(specs: &[ToolSpec]) -> Value {
    Value::Array(
        specs
            .iter()
            .map(|s| {
                json!({
                    "type": "function",
                    "function": {
                        "name": s.name,
                        "description": s.description,
                        "parameters": s.parameters,
                    }
                })
            })
            .collect(),
    )
}

/// 构造 `/chat/completions` 请求体。
pub fn request_body(req: &GenerateRequest, model: &str, stream: bool) -> Value {
    let messages: Vec<Value> = req.messages.iter().map(message_to_value).collect();
    let mut body = json!({
        "model": model,
        "messages": messages,
        "stream": stream,
    });
    if !req.tools.is_empty() {
        body["tools"] = tool_schema(&req.tools);
        body["tool_choice"] = json!("auto");
    }
    if let Some(max) = req.max_tokens {
        body["max_tokens"] = json!(max);
    }
    body
}

/// Message → OpenAI 线上格式（`tool` 角色输出 `tool_call_id`）。
pub fn message_to_value(m: &Message) -> Value {
    let mut v = json!({ "role": m.role });
    if let Some(c) = &m.content {
        v["content"] = json!(c);
    }
    if let Some(calls) = &m.tool_calls {
        v["tool_calls"] = Value::Array(calls.iter().map(tool_call_to_value).collect());
    }
    if let Some(id) = &m.tool_call_id {
        v["tool_call_id"] = json!(id);
    }
    if let Some(n) = &m.name {
        v["name"] = json!(n);
    }
    v
}

fn tool_call_to_value(c: &ToolCall) -> Value {
    json!({
        "id": c.id,
        "type": if c.kind.is_empty() { "function" } else { &c.kind },
        "function": { "name": c.function.name, "arguments": c.function.arguments }
    })
}

#[derive(Default)]
struct PartialToolCall {
    id: String,
    name: String,
    arguments: String,
}

/// 流式响应累积器：喂入 SSE 行，产出内容增量并累积工具调用分片。
#[derive(Default)]
pub struct ResponseAccumulator {
    content: String,
    tool_calls: Vec<PartialToolCall>,
    done: bool,
}

impl ResponseAccumulator {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn is_done(&self) -> bool {
        self.done
    }

    /// 喂入一行 SSE。返回本行产生的内容增量（无则 None）。
    pub fn feed_line(&mut self, line: &str) -> Result<Option<String>, String> {
        let trimmed = line.trim();
        if trimmed.is_empty() || !trimmed.starts_with("data:") {
            return Ok(None); // keep-alive / 非数据行
        }
        let payload = trimmed[5..].trim();
        if payload == "[DONE]" {
            self.done = true;
            return Ok(None);
        }
        let chunk: Value =
            serde_json::from_str(payload).map_err(|e| format!("SSE 解析失败: {e}"))?;
        let Some(choices) = chunk.get("choices").and_then(|c| c.as_array()) else {
            return Ok(None);
        };
        let Some(choice) = choices.first() else {
            return Ok(None);
        };

        if let Some(delta) = choice.get("delta") {
            self.absorb_tool_calls(delta.get("tool_calls"));
            if let Some(text) = delta.get("content").and_then(|c| c.as_str()) {
                if !text.is_empty() {
                    self.content.push_str(text);
                    return Ok(Some(text.to_string()));
                }
            }
        }
        Ok(None)
    }

    fn absorb_tool_calls(&mut self, calls: Option<&Value>) {
        let Some(arr) = calls.and_then(|c| c.as_array()) else {
            return;
        };
        for call in arr {
            let idx = call.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
            while self.tool_calls.len() <= idx {
                self.tool_calls.push(PartialToolCall::default());
            }
            let slot = &mut self.tool_calls[idx];
            if let Some(id) = call.get("id").and_then(|v| v.as_str()) {
                if !id.is_empty() {
                    slot.id = id.to_string();
                }
            }
            if let Some(func) = call.get("function") {
                if let Some(name) = func.get("name").and_then(|v| v.as_str()) {
                    slot.name.push_str(name);
                }
                if let Some(args) = func.get("arguments").and_then(|v| v.as_str()) {
                    slot.arguments.push_str(args);
                }
            }
        }
    }

    pub fn finish(self) -> ModelOutput {
        let tool_calls = self
            .tool_calls
            .into_iter()
            .filter(|c| !c.name.is_empty())
            .map(|c| ToolCall {
                id: if c.id.is_empty() {
                    format!("call_{}", c.name)
                } else {
                    c.id
                },
                kind: "function".to_string(),
                function: FunctionCall {
                    name: c.name,
                    arguments: if c.arguments.is_empty() {
                        "{}".to_string()
                    } else {
                        c.arguments
                    },
                },
            })
            .collect();
        ModelOutput {
            text: self.content,
            tool_calls,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assembles_split_tool_call_arguments() {
        let mut acc = ResponseAccumulator::new();
        // 首片：带 id/name + 半个 arguments
        acc.feed_line(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"tap_node","arguments":"{\"id\":\"0/"}}]}}]}"#).unwrap();
        // 次片：仅 arguments 续片
        acc.feed_line(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1\"}"}}]}}]}"#).unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        assert!(acc.is_done());
        let out = acc.finish();
        assert_eq!(out.tool_calls.len(), 1);
        assert_eq!(out.tool_calls[0].id, "call_1");
        assert_eq!(out.tool_calls[0].function.name, "tap_node");
        assert_eq!(out.tool_calls[0].function.arguments, r#"{"id":"0/1"}"#);
    }

    #[test]
    fn collects_content_deltas() {
        let mut acc = ResponseAccumulator::new();
        assert_eq!(
            acc.feed_line(r#"data: {"choices":[{"delta":{"content":"你好"}}]}"#)
                .unwrap(),
            Some("你好".to_string())
        );
        acc.feed_line(r#"data: {"choices":[{"delta":{"content":"，世界"}}]}"#)
            .unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        assert_eq!(acc.finish().text, "你好，世界");
    }

    #[test]
    fn ignores_keepalive_and_blank_lines() {
        let mut acc = ResponseAccumulator::new();
        assert_eq!(acc.feed_line("").unwrap(), None);
        assert_eq!(acc.feed_line(": keep-alive").unwrap(), None);
        assert!(!acc.is_done());
    }

    #[test]
    fn builds_request_with_tools() {
        let req = GenerateRequest {
            messages: vec![Message::user("hi")],
            tools: vec![ToolSpec {
                name: "observe_screen".into(),
                description: "读屏".into(),
                parameters: json!({"type":"object","properties":{}}),
            }],
            model: None,
            max_tokens: Some(64),
        };
        let body = request_body(&req, "gpt-4o", true);
        assert_eq!(body["stream"], json!(true));
        assert_eq!(body["tool_choice"], json!("auto"));
        assert_eq!(body["max_tokens"], json!(64));
        assert_eq!(
            body["tools"][0]["function"]["name"],
            json!("observe_screen")
        );
        assert_eq!(body["messages"][0]["role"], json!("user"));
    }

    #[test]
    fn tool_message_serializes_tool_call_id() {
        let m = Message::tool_result("call_1", "tap_node", "ok");
        let v = message_to_value(&m);
        assert_eq!(v["role"], json!("tool"));
        assert_eq!(v["tool_call_id"], json!("call_1"));
        assert_eq!(v["name"], json!("tap_node"));
    }

    #[test]
    fn multimodal_content_serializes_as_parts_array() {
        use crate::agent::model::Content;
        let msg =
            Message::user_with_images("看这", vec![Content::image("data:image/png;base64,AAA")]);
        let v = message_to_value(&msg);
        assert_eq!(v["role"], json!("user"));
        let content = v["content"].as_array().expect("多模态 content 应为数组");
        assert_eq!(content[0]["type"], json!("text"));
        assert_eq!(content[0]["text"], json!("看这"));
        assert_eq!(content[1]["type"], json!("image_url"));
        assert_eq!(
            content[1]["image_url"]["url"],
            json!("data:image/png;base64,AAA")
        );
    }

    #[test]
    fn plain_text_message_content_stays_string() {
        let m = Message::user("hi");
        let v = message_to_value(&m);
        assert_eq!(v["content"], json!("hi"));
        assert!(v["content"].is_string(), "纯文本 content 应序列化为字符串");
    }
}
