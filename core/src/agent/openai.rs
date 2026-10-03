//! OpenAI 兼容协议的纯函数层（AGENTS.md R6）：请求体构造、工具 schema、SSE 增量解析。
//!
//! 跨端唯一实现，避免各端各写一份解析器导致漂移。

use serde_json::{json, Value};

use super::model::{FunctionCall, GenerateRequest, Message, ModelOutput, ToolCall, ToolSpec, Usage};

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
    // 流式请求用量统计（OpenAI 在最后一个 chunk 携带 usage）。
    if stream {
        body["stream_options"] = json!({"include_usage": true});
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
    usage: Option<Usage>,
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
        // 用量可在任意 chunk 出现（OpenAI 置于末个）；先吸收再处理内容。
        if let Some(u) = chunk.get("usage").and_then(parse_usage) {
            self.usage = Some(u);
        }
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
            usage: self.usage,
        }
    }
}

/// 解析 `usage` 对象；缺 `prompt_tokens` 视为无效。
fn parse_usage(v: &Value) -> Option<Usage> {
    let prompt = v.get("prompt_tokens").and_then(|x| x.as_u64())?;
    let completion = v
        .get("completion_tokens")
        .and_then(|x| x.as_u64())
        .unwrap_or(0);
    let total = v
        .get("total_tokens")
        .and_then(|x| x.as_u64())
        .unwrap_or(prompt + completion);
    Some(Usage {
        prompt_tokens: u32::try_from(prompt).unwrap_or(u32::MAX),
        completion_tokens: u32::try_from(completion).unwrap_or(u32::MAX),
        total_tokens: u32::try_from(total).unwrap_or(u32::MAX),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn assembles_split_tool_call_arguments() {
        let mut acc = ResponseAccumulator::new();
        // 首片：带 id/name + 半个 arguments
        acc.feed_line(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"notify","arguments":"{\"title\":\"t\""}}]}}]}"#).unwrap();
        // 次片：仅 arguments 续片
        acc.feed_line(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}"#).unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        assert!(acc.is_done());
        let out = acc.finish();
        assert_eq!(out.tool_calls.len(), 1);
        assert_eq!(out.tool_calls[0].id, "call_1");
        assert_eq!(out.tool_calls[0].function.name, "notify");
        assert_eq!(out.tool_calls[0].function.arguments, r#"{"title":"t"}"#);
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
                name: "notify".into(),
                description: "通知".into(),
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
            json!("notify")
        );
        assert_eq!(body["messages"][0]["role"], json!("user"));
    }

    #[test]
    fn tool_message_serializes_tool_call_id() {
        let m = Message::tool_result("call_1", "notify", "ok");
        let v = message_to_value(&m);
        assert_eq!(v["role"], json!("tool"));
        assert_eq!(v["tool_call_id"], json!("call_1"));
        assert_eq!(v["name"], json!("notify"));
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

    #[test]
    fn parses_usage_from_stream_chunk() {
        let mut acc = ResponseAccumulator::new();
        acc.feed_line(r#"data: {"choices":[{"delta":{"content":"hi"}}]}"#)
            .unwrap();
        acc.feed_line(
            r#"data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"#,
        )
        .unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        let u = acc.finish().usage.expect("应解析出 usage");
        assert_eq!(u.prompt_tokens, 10);
        assert_eq!(u.completion_tokens, 5);
        assert_eq!(u.total_tokens, 15);
    }

    #[test]
    fn requests_usage_only_when_streaming() {
        let req = GenerateRequest {
            messages: vec![Message::user("hi")],
            tools: vec![],
            model: None,
            max_tokens: None,
        };
        let streamed = request_body(&req, "m", true);
        assert_eq!(streamed["stream_options"]["include_usage"], json!(true));
        let non_stream = request_body(&req, "m", false);
        assert!(non_stream.get("stream_options").is_none());
    }
}
