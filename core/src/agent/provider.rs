//! 多模型提供商协议适配（ROADMAP 任务 25）：OpenAI / Anthropic / Gemini 三协议。
//!
//! - [`ApiProtocol`]：上游 API 协议族（请求构造 / 响应解析按族分派）。
//! - [`ApiRequest`]：协议无关的请求描述（路径 + body + 认证头），`cloud.rs` 只做传输。
//! - [`SseParser`]：流式解析统一接口（[`crate::agent::openai::ResponseAccumulator`] 为
//!   OpenAI 实现；本文件提供 Anthropic / Gemini 实现）。
//!
//! 消息转换为纯函数（Message → 各协议线上格式），可独立测试；工具结果的协议差异
//! （Anthropic 的 tool_result 需合并进同一条 user 消息）在此层消化，core 循环机无感。

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use super::model::{Content, ContentPart, Message, ModelOutput, ToolCall, ToolSpec};

/// 上游 API 协议族。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ApiProtocol {
    /// OpenAI 兼容（`/chat/completions`，中转站默认）。
    OpenAi,
    /// Anthropic Messages（`/v1/messages`）。
    Anthropic,
    /// Gemini（`/v1beta/models/{model}:streamGenerateContent`）。
    Gemini,
}

/// 认证头风格。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthStyle {
    /// `Authorization: Bearer <key>`（OpenAI）。
    Bearer,
    /// `x-api-key: <key>`（Anthropic）。
    XApiKey,
    /// `x-goog-api-key: <key>`（Gemini）。
    GoogApiKey,
}

/// 协议无关的请求描述：`cloud.rs` 按此构造 HTTP（路径 + body + 认证头）。
#[derive(Debug, Clone)]
pub struct ApiRequest {
    /// 相对 base 的路径（含查询串，如 Gemini 的 `?alt=sse`）。
    pub path: String,
    pub body: Value,
    pub auth: AuthStyle,
    /// 额外请求头（如 Anthropic 的 `anthropic-version`）。
    pub extra_headers: Vec<(String, String)>,
}

/// 构造三协议请求（纯函数）。
pub fn build_request(protocol: ApiProtocol, req: &super::model::GenerateRequest, model: &str) -> ApiRequest {
    match protocol {
        ApiProtocol::OpenAi => {
            let body = super::openai::request_body(req, model, req.stream);
            ApiRequest {
                path: "/chat/completions".to_string(),
                body,
                auth: AuthStyle::Bearer,
                extra_headers: vec![],
            }
        }
        ApiProtocol::Anthropic => {
            let body = anthropic_request_body(req, model);
            ApiRequest {
                path: "/v1/messages".to_string(),
                body,
                auth: AuthStyle::XApiKey,
                extra_headers: vec![("anthropic-version".into(), "2023-06-01".into())],
            }
        }
        ApiProtocol::Gemini => {
            let body = gemini_request_body(req, model);
            ApiRequest {
                path: format!("/v1beta/models/{model}:streamGenerateContent?alt=sse"),
                body,
                auth: AuthStyle::GoogApiKey,
                extra_headers: vec![],
            }
        }
    }
}

/// 流式解析统一接口（三协议各自实现；接口与 openai::ResponseAccumulator 对齐）。
pub trait SseParser {
    /// 喂一行（含换行），返回应立即交给 on_token 的增量文本。
    fn feed_line(&mut self, line: &str) -> Result<Option<String>, String>;
    /// 流结束，汇总输出。
    fn finish(&mut self, max_tokens: Option<u32>) -> Result<ModelOutput, String>;
}

// ── Anthropic ──

/// Anthropic Messages 请求体。
fn anthropic_request_body(req: &super::model::GenerateRequest, model: &str) -> Value {
    let mut body = json!({
        "model": model,
        "max_tokens": req.max_tokens.unwrap_or(1024),
        "stream": req.stream,
    });
    // system 消息 → 顶层 system 字段。
    for m in &req.messages {
        if m.role == "system" {
            body["system"] = json!(m.content.as_ref().map(|c| c.text_str()).unwrap_or(""));
            break;
        }
    }
    body["messages"] = Value::Array(
        req.messages
            .iter()
            .filter(|m| m.role != "system")
            .map(anthropic_message_to_value)
            .collect(),
    );
    if !req.tools.is_empty() {
        body["tools"] = Value::Array(req.tools.iter().map(anthropic_tool_to_value).collect());
    }
    body
}

fn anthropic_tool_to_value(s: &ToolSpec) -> Value {
    json!({
        "name": s.name,
        "description": s.description,
        "input_schema": s.parameters,
    })
}

/// Message → Anthropic 线上格式。连续 role=tool 的结果合并进同一条 user 消息
/// （Anthropic 要求同批 tool_result 在一个 user 消息的 content 数组里）。
fn anthropic_message_to_value(m: &Message) -> Value {
    match m.role.as_str() {
        "assistant" => {
            let mut content = Vec::new();
            if let Some(c) = &m.content {
                let t = c.text_str();
                if !t.is_empty() {
                    content.push(json!({"type": "text", "text": t}));
                }
            }
            if let Some(calls) = &m.tool_calls {
                for c in calls {
                    content.push(json!({
                        "type": "tool_use",
                        "id": c.id,
                        "name": c.function.name,
                        "input": serde_json::from_str::<Value>(&c.function.arguments)
                            .unwrap_or(Value::Object(Default::default())),
                    }));
                }
            }
            json!({"role": "assistant", "content": Value::Array(content)})
        }
        "tool" => json!({
            "role": "user",
            "content": [{
                "type": "tool_result",
                "tool_use_id": m.tool_call_id.clone().unwrap_or_default(),
                "content": m.content.as_ref().map(|c| c.text_str()).unwrap_or(""),
            }],
        }),
        _ => {
            // user：文本 + 图片（Anthropic base64 source）。
            let mut content = Vec::new();
            if let Some(c) = &m.content {
                match c {
                    Content::Text(t) => content.push(json!({"type": "text", "text": t})),
                    Content::Parts(parts) => {
                        for p in parts {
                            match p {
                                ContentPart::Text { text } => {
                                    content.push(json!({"type": "text", "text": text}))
                                }
                                ContentPart::ImageUrl { image_url } => {
                                    content.push(anthropic_image_to_value(&image_url.url))
                                }
                            }
                        }
                    }
                }
            }
            json!({"role": "user", "content": Value::Array(content)})
        }
    }
}

/// data URL → Anthropic image block（base64 source）。
fn anthropic_image_to_value(url: &str) -> Value {
    let (media, b64) = url
        .strip_prefix("data:")
        .and_then(|s| s.split_once(";base64,"))
        .unwrap_or(("image/jpeg", ""));
    json!({
        "type": "image",
        "source": {"type": "base64", "media_type": media, "data": b64},
    })
}

/// Anthropic SSE 解析器（message_start / content_block_delta / message_delta / message_stop）。
#[derive(Default)]
pub struct AnthropicAccumulator {
    text: String,
    /// index → (id, name, partial_json)。
    tool_parts: Vec<(String, String, String)>,
    finish_reason: String,
    prompt_tokens: u64,
    completion_tokens: u64,
    /// 提示词缓存命中（`cache_read_input_tokens`，任务 30）。
    cached_tokens: u64,
}

impl AnthropicAccumulator {
    pub fn new() -> Self {
        Self::default()
    }
}

impl SseParser for AnthropicAccumulator {
    fn feed_line(&mut self, line: &str) -> Result<Option<String>, String> {
        let line = line.trim_start_matches('\u{feff}').trim_end();
        let Some(data) = line.strip_prefix("data:").map(str::trim) else {
            return Ok(None); // event: 行 / 心跳 / 空行
        };
        if data.is_empty() || data == "[DONE]" {
            return Ok(None);
        }
        let v: Value =
            serde_json::from_str(data).map_err(|e| format!("Anthropic 事件非法 JSON: {e}"))?;
        match v.get("type").and_then(|t| t.as_str()) {
            Some("content_block_delta") => match v.get("delta").and_then(|d| d.get("type")).and_then(|t| t.as_str()) {
                Some("text_delta") => {
                    let t = v["delta"]["text"].as_str().unwrap_or("");
                    self.text.push_str(t);
                    return Ok(Some(t.to_string()));
                }
                Some("input_json_delta") => {
                    let idx = v.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
                    if idx < self.tool_parts.len() {
                        let pj = v["delta"]["partial_json"].as_str().unwrap_or("");
                        self.tool_parts[idx].2.push_str(pj);
                    }
                }
                _ => {}
            },
            Some("content_block_start") => {
                let idx = v.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
                if v["content_block"]["type"] == "tool_use" {
                    let id = v["content_block"]["id"].as_str().unwrap_or_default().to_string();
                    let name = v["content_block"]["name"].as_str().unwrap_or_default().to_string();
                    // index 是全局 block 序号（text 块也占位），空洞用占位符对齐，真实块按位写。
                    while self.tool_parts.len() <= idx {
                        self.tool_parts.push((String::new(), String::new(), String::new()));
                    }
                    self.tool_parts[idx] = (id, name, String::new());
                }
            }
            Some("message_delta") => {
                if let Some(sr) = v["delta"]["stop_reason"].as_str() {
                    self.finish_reason = sr.to_string();
                }
                if let Some(u) = v.get("usage") {
                    self.completion_tokens =
                        u.get("output_tokens").and_then(|x| x.as_u64()).unwrap_or(0);
                }
            }
            Some("message_start") => {
                if let Some(u) = v["message"].get("usage") {
                    self.prompt_tokens = u.get("input_tokens").and_then(|x| x.as_u64()).unwrap_or(0);
                    self.cached_tokens = u
                        .get("cache_read_input_tokens")
                        .and_then(|x| x.as_u64())
                        .unwrap_or(0);
                }
            }
            Some("error") => {
                let msg = v["error"]["message"].as_str().unwrap_or("未知错误");
                return Err(format!("Anthropic 错误事件: {msg}"));
            }
            _ => {}
        }
        Ok(None)
    }

    fn finish(&mut self, _max_tokens: Option<u32>) -> Result<ModelOutput, String> {
        let tool_calls: Vec<ToolCall> = self
            .tool_parts
            .drain(..)
            .filter(|(_, name, _)| !name.is_empty())
            .map(|(id, name, pj)| ToolCall {
                id: if id.is_empty() {
                    format!("call_{name}")
                } else {
                    id
                },
                kind: "function".into(),
                function: super::model::FunctionCall {
                    name,
                    arguments: if pj.trim().is_empty() {
                        "{}".into()
                    } else {
                        pj
                    },
                },
            })
            .collect();
        Ok(ModelOutput {
            text: std::mem::take(&mut self.text),
            tool_calls,
            usage: Some(super::model::Usage {
                prompt_tokens: self.prompt_tokens as u32,
                completion_tokens: self.completion_tokens as u32,
                total_tokens: (self.prompt_tokens + self.completion_tokens) as u32,
                cached_tokens: self.cached_tokens as u32,
            }),
            warnings: vec![],
        })
    }
}

// ── Gemini ──

/// Gemini generateContent 请求体。
fn gemini_request_body(req: &super::model::GenerateRequest, model: &str) -> Value {
    let _ = model;
    let mut body = json!({});
    let mut contents = Vec::new();
    for m in &req.messages {
        match m.role.as_str() {
            "system" => {
                body["systemInstruction"] = json!({
                    "parts": [{"text": m.content.as_ref().map(|c| c.text_str()).unwrap_or("")}],
                });
            }
            "assistant" => {
                let mut parts = Vec::new();
                if let Some(c) = &m.content {
                    let t = c.text_str();
                    if !t.is_empty() {
                        parts.push(json!({"text": t}));
                    }
                }
                if let Some(calls) = &m.tool_calls {
                    for c in calls {
                        parts.push(json!({
                            "functionCall": {
                                "name": c.function.name,
                                "args": serde_json::from_str::<Value>(&c.function.arguments)
                                    .unwrap_or(Value::Object(Default::default())),
                            }
                        }));
                    }
                }
                contents.push(json!({"role": "model", "parts": Value::Array(parts)}));
            }
            "tool" => {
                // tool 结果：functionResponse（Gemini 要求紧随 functionCall，role=user）。
                let name = m.name.clone().unwrap_or_default();
                let out = m.content.as_ref().map(|c| c.text_str()).unwrap_or("");
                contents.push(json!({
                    "role": "user",
                    "parts": [{
                        "functionResponse": {
                            "name": name,
                            "response": {"output": out},
                        }
                    }],
                }));
            }
            _ => {
                let mut parts = Vec::new();
                if let Some(c) = &m.content {
                    match c {
                        Content::Text(t) => parts.push(json!({"text": t})),
                        Content::Parts(ps) => {
                            for p in ps {
                                match p {
                                    ContentPart::Text { text } => parts.push(json!({"text": text})),
                                    ContentPart::ImageUrl { image_url } => {
                                        let (mime, b64) = image_url
                                            .url
                                            .strip_prefix("data:")
                                            .and_then(|s| s.split_once(";base64,"))
                                            .unwrap_or(("image/jpeg", ""));
                                        parts.push(json!({
                                            "inlineData": {"mimeType": mime, "data": b64},
                                        }));
                                    }
                                }
                            }
                        }
                    }
                }
                contents.push(json!({"role": "user", "parts": Value::Array(parts)}));
            }
        }
    }
    body["contents"] = Value::Array(contents);
    if !req.tools.is_empty() {
        body["tools"] = json!([{
            "functionDeclarations": req
                .tools
                .iter()
                .map(|s| json!({
                    "name": s.name,
                    "description": s.description,
                    "parameters": s.parameters,
                }))
                .collect::<Vec<_>>(),
        }]);
    }
    if let Some(max) = req.max_tokens {
        body["generationConfig"] = json!({"maxOutputTokens": max});
    }
    body
}

/// Gemini SSE 解析器（data: {candidates:[...], usageMetadata}）。
#[derive(Default)]
pub struct GeminiAccumulator {
    text: String,
    tool_calls: Vec<(String, Value)>,
    finish_reason: String,
    prompt_tokens: u64,
    completion_tokens: u64,
    /// 提示词缓存命中（`cachedContentTokenCount`，任务 30）。
    cached_tokens: u64,
}

impl GeminiAccumulator {
    pub fn new() -> Self {
        Self::default()
    }
}

impl SseParser for GeminiAccumulator {
    fn feed_line(&mut self, line: &str) -> Result<Option<String>, String> {
        let line = line.trim_end();
        let Some(data) = line.strip_prefix("data:").map(str::trim) else {
            return Ok(None);
        };
        if data.is_empty() {
            return Ok(None);
        }
        let v: Value =
            serde_json::from_str(data).map_err(|e| format!("Gemini 事件非法 JSON: {e}"))?;
        if let Some(u) = v.get("usageMetadata") {
            self.prompt_tokens = u.get("promptTokenCount").and_then(|x| x.as_u64()).unwrap_or(0);
            self.completion_tokens = u
                .get("candidatesTokenCount")
                .and_then(|x| x.as_u64())
                .unwrap_or(0);
            self.cached_tokens = u
                .get("cachedContentTokenCount")
                .and_then(|x| x.as_u64())
                .unwrap_or(0);
        }
        let Some(cands) = v.get("candidates").and_then(|c| c.as_array()) else {
            return Ok(None);
        };
        let mut delta = String::new();
        for c in cands {
            if let Some(fr) = c.get("finishReason").and_then(|f| f.as_str()) {
                self.finish_reason = fr.to_string();
            }
            let Some(parts) = c
                .get("content")
                .and_then(|ct| ct.get("parts"))
                .and_then(|p| p.as_array())
            else {
                continue;
            };
            for p in parts {
                if let Some(t) = p.get("text").and_then(|t| t.as_str()) {
                    self.text.push_str(t);
                    delta.push_str(t);
                }
                if let Some(fc) = p.get("functionCall") {
                    let name = fc.get("name").and_then(|n| n.as_str()).unwrap_or_default().to_string();
                    let args = fc.get("args").cloned().unwrap_or(Value::Object(Default::default()));
                    self.tool_calls.push((name, args));
                }
            }
        }
        if delta.is_empty() {
            Ok(None)
        } else {
            Ok(Some(delta))
        }
    }

    fn finish(&mut self, _max_tokens: Option<u32>) -> Result<ModelOutput, String> {
        let tool_calls: Vec<ToolCall> = self
            .tool_calls
            .drain(..)
            .enumerate()
            .map(|(i, (name, args))| ToolCall {
                id: format!("call_{}_{}", name, i),
                kind: "function".into(),
                function: super::model::FunctionCall {
                    name,
                    arguments: args.to_string(),
                },
            })
            .collect();
        Ok(ModelOutput {
            text: std::mem::take(&mut self.text),
            tool_calls,
            usage: Some(super::model::Usage {
                prompt_tokens: self.prompt_tokens as u32,
                completion_tokens: self.completion_tokens as u32,
                total_tokens: (self.prompt_tokens + self.completion_tokens) as u32,
                cached_tokens: self.cached_tokens as u32,
            }),
            warnings: if self.finish_reason.is_empty() {
                vec![]
            } else {
                vec![format!("Gemini finishReason: {}", self.finish_reason)]
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::model::{FunctionCall, GenerateRequest, ToolCall};

    /// 喂一行并取增量文本（忽略错误/无增量）。
    fn line(p: &mut dyn SseParser, s: &str) -> String {
        p.feed_line(s)
            .map(|o| o.unwrap_or_default())
            .unwrap_or_default()
    }

    fn req_with_tools() -> GenerateRequest {
        GenerateRequest {
            messages: vec![
                Message::system("你是助手"),
                Message::user("hi"),
                Message::assistant_tool_calls(
                    Some(Content::text("我来调工具")),
                    vec![ToolCall {
                        id: "call_1".into(),
                        kind: "function".into(),
                        function: FunctionCall {
                            name: "notify".into(),
                            arguments: r#"{"title":"t","body":"b"}"#.into(),
                        },
                    }],
                ),
                Message::tool_result("call_1", "notify", "ok"),
            ],
            tools: vec![ToolSpec {
                name: "notify".into(),
                description: "通知".into(),
                parameters: json!({"type":"object","properties":{}}),
            }],
            model: None,
            max_tokens: Some(512),
            stream: true,
            tool_choice: None,
        }
    }

    #[test]
    fn openai_request_keeps_existing_shape() {
        let r = build_request(ApiProtocol::OpenAi, &req_with_tools(), "gpt-4o");
        assert_eq!(r.path, "/chat/completions");
        assert_eq!(r.auth, AuthStyle::Bearer);
        assert_eq!(r.body["model"], json!("gpt-4o"));
        assert!(r.body.get("system").is_none(), "OpenAI system 在 messages 里");
    }

    /// 任务 26：首条 user 消息的图片按协议族分别序列化为 image_url / image / inlineData。
    fn req_with_image() -> GenerateRequest {
        GenerateRequest {
            messages: vec![
                Message::system("你是助手"),
                Message::user_with_images(
                    "看图",
                    vec![Content::image("data:image/png;base64,AAAA")],
                ),
            ],
            tools: vec![],
            model: None,
            max_tokens: Some(512),
            stream: true,
            tool_choice: None,
        }
    }

    #[test]
    fn openai_request_sends_image_url_parts() {
        let r = build_request(ApiProtocol::OpenAi, &req_with_image(), "gpt-4o");
        let content = &r.body["messages"][1]["content"];
        assert_eq!(content[0]["type"], json!("text"));
        assert_eq!(content[0]["text"], json!("看图"));
        assert_eq!(content[1]["type"], json!("image_url"));
        assert_eq!(
            content[1]["image_url"]["url"],
            json!("data:image/png;base64,AAAA")
        );
    }

    #[test]
    fn anthropic_request_sends_base64_image_block() {
        let r = build_request(ApiProtocol::Anthropic, &req_with_image(), "claude-x");
        let content = &r.body["messages"][0]["content"];
        assert_eq!(content[0], json!({"type": "text", "text": "看图"}));
        assert_eq!(
            content[1],
            json!({
                "type": "image",
                "source": {"type": "base64", "media_type": "image/png", "data": "AAAA"},
            })
        );
    }

    #[test]
    fn gemini_request_sends_inline_data() {
        let r = build_request(ApiProtocol::Gemini, &req_with_image(), "gemini-x");
        let parts = &r.body["contents"][0]["parts"];
        assert_eq!(parts[0], json!({"text": "看图"}));
        assert_eq!(
            parts[1],
            json!({"inlineData": {"mimeType": "image/png", "data": "AAAA"}})
        );
    }

    #[test]
    fn anthropic_request_shape() {
        let r = build_request(ApiProtocol::Anthropic, &req_with_tools(), "claude-x");
        assert_eq!(r.path, "/v1/messages");
        assert_eq!(r.auth, AuthStyle::XApiKey);
        assert!(r
            .extra_headers
            .iter()
            .any(|(k, v)| k == "anthropic-version" && v == "2023-06-01"));
        assert_eq!(r.body["system"], json!("你是助手"));
        assert_eq!(r.body["max_tokens"], json!(512));
        assert_eq!(r.body["tools"][0]["input_schema"], json!({"type":"object","properties":{}}));
        // assistant 的 tool_use + tool 结果的 tool_result。
        let msgs = r.body["messages"].as_array().unwrap();
        assert_eq!(msgs[1]["role"], json!("assistant"));
        assert_eq!(msgs[1]["content"][1]["type"], json!("tool_use"));
        assert_eq!(msgs[1]["content"][1]["input"], json!({"title":"t","body":"b"}));
        assert_eq!(msgs[2]["role"], json!("user"));
        assert_eq!(msgs[2]["content"][0]["type"], json!("tool_result"));
        assert_eq!(msgs[2]["content"][0]["tool_use_id"], json!("call_1"));
    }

    #[test]
    fn gemini_request_shape() {
        let r = build_request(ApiProtocol::Gemini, &req_with_tools(), "gemini-x");
        assert_eq!(
            r.path,
            "/v1beta/models/gemini-x:streamGenerateContent?alt=sse"
        );
        assert_eq!(r.auth, AuthStyle::GoogApiKey);
        assert_eq!(r.body["systemInstruction"]["parts"][0]["text"], json!("你是助手"));
        assert!(r.body["tools"][0]["functionDeclarations"].is_array());
        let contents = r.body["contents"].as_array().unwrap();
        assert_eq!(contents[1]["role"], json!("model"));
        assert_eq!(contents[1]["parts"][1]["functionCall"]["name"], json!("notify"));
        assert_eq!(contents[2]["parts"][0]["functionResponse"]["name"], json!("notify"));
    }

    #[test]
    fn anthropic_parser_text_and_tool_use() {
        let mut p = AnthropicAccumulator::new();
        let mut out = String::new();
        out.push_str(&line(&mut p, "event: message_start\n"));
        out.push_str(&line(
            &mut p,
            r#"data: {"type":"message_start","message":{"usage":{"input_tokens":7}}}"#,
        ));
        out.push_str(&line(
            &mut p,
            r#"data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"#,
        ));
        out.push_str(&line(
            &mut p,
            r#"data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}"#,
        ));
        p.feed_line(r#"data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"call_9","name":"notify"}}"#)
            .unwrap();
        p.feed_line(r#"data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"title\":\"t\""}}"#)
            .unwrap();
        p.feed_line(r#"data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"}"}}"#)
            .unwrap();
        p.feed_line(r#"data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":3}}"#)
            .unwrap();
        let out = p.finish(None).unwrap();
        assert_eq!(out.text, "你好");
        assert_eq!(out.tool_calls.len(), 1);
        assert_eq!(out.tool_calls[0].id, "call_9");
        assert_eq!(out.tool_calls[0].function.arguments, r#"{"title":"t"}"#);
        assert_eq!(out.usage.unwrap().total_tokens, 10);
    }

    #[test]
    fn gemini_parser_text_and_function_call() {
        let mut p = GeminiAccumulator::new();
        let d1 = line(&mut p, r#"data: {"candidates":[{"content":{"role":"model","parts":[{"text":"正在"}]}}]}"#);
        assert_eq!(d1, "正在");
        p.feed_line(r#"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"notify","args":{"title":"t"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":6}}"#)
            .unwrap();
        let out = p.finish(None).unwrap();
        assert_eq!(out.text, "正在");
        assert_eq!(out.tool_calls.len(), 1);
        assert_eq!(out.tool_calls[0].function.name, "notify");
        assert_eq!(
            out.tool_calls[0].function.arguments,
            r#"{"title":"t"}"#
        );
        assert_eq!(out.usage.unwrap().total_tokens, 10);
    }

    #[test]
    fn parser_error_events_bubble() {
        let mut p = AnthropicAccumulator::new();
        assert!(p
            .feed_line(r#"data: {"type":"error","error":{"message":"额度耗尽"}}"#)
            .is_err());
        let mut g = GeminiAccumulator::new();
        assert!(g.feed_line("data: not-json").is_err());
    }
}
