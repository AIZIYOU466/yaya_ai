//! OpenAI 兼容协议的纯函数层（AGENTS.md R6）：请求体构造、工具 schema、SSE 增量解析。
//!
//! 跨端唯一实现，避免各端各写一份解析器导致漂移。

use std::time::Duration;

use serde_json::{json, Value};

use super::model::{FailureKind, FunctionCall, GenerateRequest, Message, ModelOutput, ToolCall, ToolSpec, Usage};

/// OpenAI 官方 `finish_reason` 取值；兼容站可能返回 `end_turn` / `stop_sequence` 等非标值，
/// 此类值不在此集合内，按协议告警处理（写入 warnings）而非硬失败。
pub const KNOWN_FINISH_REASONS: [&str; 5] = ["stop", "length", "tool_calls", "content_filter", "function_call"];

/// HTTP 错误分类：`Fatal` 不重试；`Retry` 走退避重试。

/// 传输超时阶段。值决策集中于此，便于容器内测试；`cloud.rs` 只消费 `pick_timeout`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimeoutStage {
    /// TCP + TLS 建连。
    Connect,
    /// 请求发出到收到首个 `data:` 行（含 headers 等待）。
    FirstToken,
    /// 首个 `data:` 行到第二个之间（模型在"想"）。
    InterChunkFirst,
    /// 后续 `data:` 行之间（模型在"说"）。
    InterChunk,
}

pub const MAX_ATTEMPTS: u32 = 3;

pub fn pick_timeout(stage: TimeoutStage) -> Duration {
    match stage {
        TimeoutStage::Connect => Duration::from_secs(15),
        TimeoutStage::FirstToken => Duration::from_secs(60),
        TimeoutStage::InterChunkFirst => Duration::from_secs(90),
        TimeoutStage::InterChunk => Duration::from_secs(45),
    }
}

/// 退避 + 抖动（无随机依赖）：`1s/2s/4s/8s/16s/30s` 指数增长，加 0..=25% 抖动，总上限 30s。
pub fn backoff_delay(attempt: u32) -> Duration {
    let base_ms = (1000u64 << attempt.saturating_sub(1).min(5)).min(30_000);
    let seed = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(attempt);
    let jitter_ms = (seed as u64) % (base_ms / 4 + 1);
    Duration::from_millis((base_ms + jitter_ms).min(30_000))
}

/// HTTP 状态码分类。`body_head` 只传响应体前 4KB 即可（调用方负责截断）。
pub fn classify_status(status: u16, body_head: &str) -> FailureKind {
    // 配额耗尽重试无意义，即使走 429 也要 Fatal。
    if let Ok(v) = serde_json::from_str::<Value>(body_head) {
        if v.pointer("/error/type").and_then(|t| t.as_str()) == Some("insufficient_quota") {
            return FailureKind::Fatal;
        }
    }
    match status {
        429 | 408 | 500 | 502 | 503 | 504 => FailureKind::Retry,
        400 | 401 | 403 | 404 | 422 => FailureKind::Fatal,
        _ => FailureKind::Fatal,
    }
}

/// 检测中转站是否忽略 `max_tokens` 偷偷截断输出。
/// 判据：`completion_tokens ≈ 请求的 max_tokens` 且 `finish_reason != "length"` → 可疑。
/// `usage` / `finish_reason` 缺失或 `completion_tokens == 0` 时不可判，返回 None。
pub fn detect_max_tokens_tamper(
    requested: Option<u32>,
    usage: Option<&Usage>,
    finish_reason: Option<&str>,
) -> Option<String> {
    let req = requested?;
    let u = usage?;
    let got = u.completion_tokens;
    if got == 0 {
        return None;
    }
    let fr = finish_reason?;
    if req.abs_diff(got) <= 2 && fr != "length" {
        Some(format!(
            "协议告警: 请求 max_tokens={req}，实际完成 {got} 但 finish_reason 为 {fr}，可能被中转站截断"
        ))
    } else {
        None
    }
}

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

/// 按 `ToolSpec.parameters`（JSON Schema）校验 tool_call 的 arguments（4C-2 行为观测）。
/// 只覆盖内置/平台工具 schema 实际使用的子集：object 容器、required、properties 类型、
/// `additionalProperties: false`、数值 `minimum` / `maximum`。不支持的约束忽略（观测层宁松勿错）。
/// 纯函数；调用方只发 Notice（仅观测不补偿，补偿在 4C-3）。
pub fn validate_call_against_schema(spec: &ToolSpec, args: &Value) -> Result<(), String> {
    let schema = &spec.parameters;
    let obj = match args {
        Value::Object(o) => o,
        _ => return Err(format!("参数应为 object，实际 {}", kind_of(args))),
    };
    if let Some(required) = schema.get("required").and_then(|r| r.as_array()) {
        for key in required.iter().filter_map(|k| k.as_str()) {
            if !obj.contains_key(key) {
                return Err(format!("缺少必填字段 `{key}`"));
            }
        }
    }
    let props = schema.get("properties").and_then(|p| p.as_object());
    let allow_extra = schema
        .get("additionalProperties")
        .and_then(|v| v.as_bool())
        .unwrap_or(true);
    if !allow_extra {
        if let Some(props) = props {
            for key in obj.keys() {
                if !props.contains_key(key) {
                    return Err(format!("未声明的额外字段 `{key}`"));
                }
            }
        }
    }
    if let Some(props) = props {
        for (key, value) in obj {
            if let Some(constraint) = props.get(key) {
                validate_value(constraint, value)
                    .map_err(|e| format!("字段 `{key}` {e}"))?;
            }
        }
    }
    Ok(())
}

fn kind_of(v: &Value) -> &'static str {
    match v {
        Value::Null => "null",
        Value::Bool(_) => "bool",
        Value::Number(_) => "number",
        Value::String(_) => "string",
        Value::Array(_) => "array",
        Value::Object(_) => "object",
    }
}

/// 单值约束校验：type 匹配（integer 允许 number 的整数值）+ 数值边界。
fn validate_value(constraint: &Value, value: &Value) -> Result<(), String> {
    let expected = constraint.get("type").and_then(|t| t.as_str());
    if let Some(expected) = expected {
        let ok = match expected {
            "string" => value.is_string(),
            "integer" => value.as_i64().is_some() || value.as_u64().is_some(),
            "number" => value.is_number(),
            "boolean" => value.is_boolean(),
            "array" => value.is_array(),
            "object" => value.is_object(),
            _ => true, // 未知类型不校验（宁松勿错）。
        };
        if !ok {
            return Err(format!("类型应为 {expected}，实际 {}", kind_of(value)));
        }
        if expected == "integer" && value.is_number() {
            if let Some(f) = value.as_f64() {
                if f.fract() != 0.0 {
                    return Err(format!("类型应为 integer，实际 number({f})"));
                }
            }
        }
    }
    if value.is_number() {
        if let Some(n) = value.as_f64() {
            if let Some(min) = constraint.get("minimum").and_then(|m| m.as_f64()) {
                if n < min {
                    return Err(format!("值 {n} 小于 minimum {min}"));
                }
            }
            if let Some(max) = constraint.get("maximum").and_then(|m| m.as_f64()) {
                if n > max {
                    return Err(format!("值 {n} 大于 maximum {max}"));
                }
            }
        }
    }
    Ok(())
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
        // 4C-3 行为补偿：Forced 档下可强制 required；默认 auto。
        body["tool_choice"] = match req.tool_choice.as_deref() {
            Some(tc) => json!(tc),
            None => json!("auto"),
        };
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
    /// 是否收到过含 `choices` 或 `error` 字段的合法载荷（`[DONE]` / 空行不计）。
    saw_payload: bool,
    finish_reason: Option<String>,
    /// 协议告警（未知 finish_reason、异常工具调用 ID 等），由上层转 Notice，不阻断本次生成。
    pub warnings: Vec<String>,
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
        let chunk: Value = serde_json::from_str(payload)
            .map_err(|e| format!("协议污染: SSE payload 非 JSON: {e}"))?;
        // 含 choices 或 error 的合法载荷才视为有效数据（[DONE]/空行不计）。
        if chunk.get("choices").is_some() || chunk.get("error").is_some() {
            self.saw_payload = true;
        }
        if let Some(err) = chunk.get("error") {
            return Err(format!("流内错误(协议): {err}"));
        }
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

        if let Some(fr) = choice.get("finish_reason").and_then(|v| v.as_str()) {
            if !fr.is_empty() {
                self.finish_reason = Some(fr.to_string());
                if !KNOWN_FINISH_REASONS.contains(&fr) {
                    self.warnings
                        .push(format!("协议告警: 未知 finish_reason: {fr}"));
                }
            }
        }

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

    pub fn finish(self, requested_max_tokens: Option<u32>) -> Result<ModelOutput, String> {
        if !self.saw_payload && self.content.is_empty() && self.tool_calls.is_empty() {
            return Err("协议污染: 流结束但未收到任何有效载荷".to_string());
        }
        let mut warnings = self.warnings;
        // max_tokens 被偷偷截断检测（模型知道 finish_reason，cloud.rs 不知）。
        if let Some(w) = detect_max_tokens_tamper(
            requested_max_tokens,
            self.usage.as_ref(),
            self.finish_reason.as_deref(),
        ) {
            warnings.push(w);
        }
        let mut tool_calls: Vec<ToolCall> = Vec::new();
        for c in self.tool_calls {
            if c.name.is_empty() {
                continue;
            }
            if !c.arguments.trim().is_empty() {
                if serde_json::from_str::<Value>(&c.arguments).is_err() {
                    return Err(format!(
                        "协议污染: tool_call arguments 非合法 JSON: {}",
                        c.arguments
                    ));
                }
            }
            let id = if c.id.is_empty() {
                format!("call_{}", c.name)
            } else {
                c.id
            };
            if id.len() > 256 {
                warnings.push("协议告警: tool_call ID 超过 256 字符".to_string());
            }
            if !id.chars().all(|ch| ch.is_ascii_alphanumeric() || ch == '-' || ch == '_') {
                warnings.push(format!("协议告警: tool_call ID 含非法字符: {id}"));
            }
            tool_calls.push(ToolCall {
                id,
                kind: "function".to_string(),
                function: FunctionCall {
                    name: c.name,
                    arguments: if c.arguments.is_empty() {
                        "{}".to_string()
                    } else {
                        c.arguments
                    },
                },
            });
        }
        Ok(ModelOutput {
            text: self.content,
            tool_calls,
            usage: self.usage,
            warnings,
        })
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
    // 任务 30：缓存命中明细（OpenAI 兼容端点常有；缺失为 0）。
    let cached = v
        .get("prompt_tokens_details")
        .and_then(|d| d.get("cached_tokens"))
        .and_then(|x| x.as_u64())
        .unwrap_or(0);
    Some(Usage {
        prompt_tokens: u32::try_from(prompt).unwrap_or(u32::MAX),
        completion_tokens: u32::try_from(completion).unwrap_or(u32::MAX),
        total_tokens: u32::try_from(total).unwrap_or(u32::MAX),
        cached_tokens: u32::try_from(cached).unwrap_or(u32::MAX),
    })
}

/// 统一流式解析接口适配（任务 25）：与 Anthropic/Gemini 解析器同接口，供 cloud.rs 分派。
impl crate::agent::provider::SseParser for ResponseAccumulator {
    fn feed_line(&mut self, line: &str) -> Result<Option<String>, String> {
        ResponseAccumulator::feed_line(self, line)
    }

    fn finish(&mut self, requested_max_tokens: Option<u32>) -> Result<ModelOutput, String> {
        // 原实现为消费型（self），经 Default::take 适配为 &mut self。
        std::mem::take(self).finish(requested_max_tokens)
    }
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
        let out = acc.finish(None).unwrap();
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
        assert_eq!(acc.finish(None).unwrap().text, "你好，世界");
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
            stream: true,
            tool_choice: None,
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
    fn tool_choice_required_overrides_default() {
        let req = GenerateRequest {
            messages: vec![Message::user("hi")],
            tools: vec![ToolSpec {
                name: "notify".into(),
                description: "通知".into(),
                parameters: json!({"type":"object","properties":{}}),
            }],
            model: None,
            max_tokens: None,
            stream: false,
            tool_choice: Some("required".into()),
        };
        let body = request_body(&req, "gpt-4o", false);
        assert_eq!(body["tool_choice"], json!("required"));
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
        let u = acc.finish(None).unwrap().usage.expect("应解析出 usage");
        assert_eq!(u.prompt_tokens, 10);
        assert_eq!(u.completion_tokens, 5);
        assert_eq!(u.total_tokens, 15);
        assert_eq!(u.cached_tokens, 0);
    }

    #[test]
    fn parses_cached_tokens_from_prompt_details() {
        let mut acc = ResponseAccumulator::new();
        acc.feed_line(
            r#"data: {"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105,"prompt_tokens_details":{"cached_tokens":64}}}"#,
        )
        .unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        let u = acc.finish(None).unwrap().usage.expect("应解析出 usage");
        assert_eq!(u.cached_tokens, 64);
    }

    #[test]
    fn requests_usage_only_when_streaming() {
        let req = GenerateRequest {
            messages: vec![Message::user("hi")],
            tools: vec![],
            model: None,
            max_tokens: None,
            stream: true,
            tool_choice: None,
        };
        let streamed = request_body(&req, "m", true);
        assert_eq!(streamed["stream_options"]["include_usage"], json!(true));
        let non_stream = request_body(&req, "m", false);
        assert!(non_stream.get("stream_options").is_none());
    }

    #[test]
    fn rejects_non_json_payload() {
        let mut acc = ResponseAccumulator::new();
        let err = acc.feed_line("data: <html>错误页</html>").unwrap_err();
        assert!(err.contains("协议污染"), "错误应标注协议污染: {err}");
    }

    #[test]
    fn rejects_in_stream_error_payload() {
        let mut acc = ResponseAccumulator::new();
        let err = acc
            .feed_line(r#"data: {"error":{"message":"上游超时","type":"upstream_error"}}"#)
            .unwrap_err();
        assert!(err.contains("流内错误"), "错误应标注流内错误: {err}");
    }

    #[test]
    fn warns_but_accepts_unknown_finish_reason() {
        let mut acc = ResponseAccumulator::new();
        // Anthropic 系兼容值，不在 OpenAI 官方集合内。
        acc.feed_line(r#"data: {"choices":[{"delta":{"content":"hi"},"finish_reason":"end_turn"}]}"#)
            .unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        let out = acc.finish(None).unwrap();
        assert_eq!(out.text, "hi");
        assert!(
            out.warnings.iter().any(|w| w.contains("未知 finish_reason")),
            "warnings 应含未知 finish_reason: {:?}",
            out.warnings
        );
    }

    #[test]
    fn rejects_empty_stream() {
        let mut acc = ResponseAccumulator::new();
        acc.feed_line("data: [DONE]").unwrap();
        let err = acc.finish(None).unwrap_err();
        assert!(err.contains("未收到任何有效载荷"), "空流应报错: {err}");
    }

    #[test]
    fn rejects_unclosed_tool_call_arguments() {
        let mut acc = ResponseAccumulator::new();
        acc.feed_line(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"notify","arguments":"{\"title\":\"t\""}}]}}]}"#)
            .unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        let err = acc.finish(None).unwrap_err();
        assert!(
            err.contains("arguments 非合法 JSON"),
            "未闭合 arguments 应报协议污染: {err}"
        );
    }

    #[test]
    fn warns_on_invalid_tool_call_id() {
        let mut acc = ResponseAccumulator::new();
        acc.feed_line(r#"data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call 1;drop","function":{"name":"notify","arguments":"{}"}}]}}]}"#)
            .unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        let out = acc.finish(None).unwrap();
        assert!(
            out.warnings.iter().any(|w| w.contains("非法字符")),
            "ID 含非法字符应软警告: {:?}",
            out.warnings
        );
        // 弱校验：不阻断，ID 原样保留。
        assert_eq!(out.tool_calls[0].id, "call 1;drop");
    }

    #[test]
    fn classifies_http_status() {
        assert_eq!(classify_status(429, ""), FailureKind::Retry);
        assert_eq!(classify_status(504, ""), FailureKind::Retry);
        assert_eq!(classify_status(408, ""), FailureKind::Retry);
        assert_eq!(classify_status(401, ""), FailureKind::Fatal);
        assert_eq!(classify_status(400, ""), FailureKind::Fatal);
        // 配额耗尽即使 429 也不重试。
        assert_eq!(
            classify_status(429, r#"{"error":{"type":"insufficient_quota"}}"#),
            FailureKind::Fatal
        );
        // 大 HTML 错误页：只取头部不影响判断。
        let html = format!("<html>{}</html>", "x".repeat(6000));
        assert_eq!(classify_status(502, &html), FailureKind::Retry);
    }

    #[test]
    fn detects_max_tokens_tamper() {
        let usage = Usage {
            prompt_tokens: 10,
            completion_tokens: 1024,
            total_tokens: 1034,
            cached_tokens: 0,
        };
        // 完成数 ≈ max_tokens 且 finish_reason 非 length → 可疑。
        assert!(detect_max_tokens_tamper(Some(1024), Some(&usage), Some("stop")).is_some());
        // 容差内（差 2）也算。
        let usage2 = Usage {
            completion_tokens: 1022,
            ..usage
        };
        assert!(detect_max_tokens_tamper(Some(1024), Some(&usage2), Some("stop")).is_some());
        // finish_reason = length → 正常截断。
        assert!(detect_max_tokens_tamper(Some(1024), Some(&usage), Some("length")).is_none());
        // 差距大 → 不可疑。
        let usage3 = Usage {
            completion_tokens: 800,
            ..usage
        };
        assert!(detect_max_tokens_tamper(Some(1024), Some(&usage3), Some("stop")).is_none());
        // 信息缺失 → 不可判。
        assert!(detect_max_tokens_tamper(None, Some(&usage), Some("stop")).is_none());
        assert!(detect_max_tokens_tamper(Some(1024), None, Some("stop")).is_none());
        assert!(detect_max_tokens_tamper(Some(1024), Some(&usage), None).is_none());
    }

    #[test]
    fn finish_integration_reports_tamper_warning() {
        let mut acc = ResponseAccumulator::new();
        acc.feed_line(r#"data: {"choices":[{"delta":{"content":"hi"}}]}"#)
            .unwrap();
        acc.feed_line(r#"data: {"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}"#)
            .unwrap();
        acc.feed_line(
            r#"data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":64,"total_tokens":74}}"#,
        )
        .unwrap();
        acc.feed_line("data: [DONE]").unwrap();
        // 请求 max_tokens=64，完成 64，finish_reason=stop → 告警。
        let out = acc.finish(Some(64)).unwrap();
        assert!(
            out.warnings.iter().any(|w| w.contains("可能被中转站截断")),
            "warnings 应含 tamper 告警: {:?}",
            out.warnings
        );
    }

    #[test]
    fn timeout_values_are_graduated() {
        assert_eq!(pick_timeout(TimeoutStage::Connect), Duration::from_secs(15));
        assert_eq!(pick_timeout(TimeoutStage::FirstToken), Duration::from_secs(60));
        assert_eq!(
            pick_timeout(TimeoutStage::InterChunkFirst),
            Duration::from_secs(90)
        );
        assert_eq!(pick_timeout(TimeoutStage::InterChunk), Duration::from_secs(45));
    }

    #[test]
    fn backoff_is_exponential_with_cap() {
        // attempt 1: 1s ± 25%，上限 1.25s；attempt 2: 2s ± 25%。
        let d1 = backoff_delay(1);
        assert!(d1 >= Duration::from_secs(1) && d1 <= Duration::from_millis(1250), "{d1:?}");
        let d2 = backoff_delay(2);
        assert!(d2 >= Duration::from_secs(2) && d2 <= Duration::from_millis(2500), "{d2:?}");
        // 上限 30s，即使 attempt 很大。
        assert!(backoff_delay(u32::MAX) <= Duration::from_secs(30));
    }
}
