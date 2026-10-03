//! 模型后端抽象（AGENTS.md R6）：循环机经此 trait 生成，不感知平台与传输。

use serde::{Deserialize, Serialize};

use super::router::Backend;

/// OpenAI 多模态 content：字符串或内容部件数组。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum Content {
    Text(String),
    Parts(Vec<ContentPart>),
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ContentPart {
    Text { text: String },
    ImageUrl { image_url: ImageUrl },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ImageUrl {
    pub url: String,
    #[serde(default = "default_detail")]
    pub detail: String,
}

fn default_detail() -> String {
    "auto".into()
}

impl Content {
    pub fn text(s: impl Into<String>) -> Self {
        Content::Text(s.into())
    }

    pub fn image(base64_url: impl Into<String>) -> ContentPart {
        ContentPart::ImageUrl {
            image_url: ImageUrl {
                url: base64_url.into(),
                detail: "auto".into(),
            },
        }
    }

    /// 提取纯文本（图片部件被跳过）。
    pub fn text_str(&self) -> &str {
        match self {
            Content::Text(s) => s,
            Content::Parts(parts) => parts
                .iter()
                .find_map(|p| match p {
                    ContentPart::Text { text } => Some(text.as_str()),
                    _ => None,
                })
                .unwrap_or(""),
        }
    }

    pub fn is_empty(&self) -> bool {
        match self {
            Content::Text(s) => s.is_empty(),
            Content::Parts(p) => p.is_empty(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FunctionCall {
    pub name: String,
    /// 原始 JSON 字符串（与 OpenAI 契约一致）
    pub arguments: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ToolCall {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub function: FunctionCall,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Message {
    pub role: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub content: Option<Content>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_calls: Option<Vec<ToolCall>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tool_call_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl Message {
    pub fn system(content: impl Into<String>) -> Self {
        Message::text("system", content)
    }

    pub fn user(content: impl Into<String>) -> Self {
        Message::text("user", content)
    }

    pub fn assistant(content: impl Into<String>) -> Self {
        Message::text("assistant", content)
    }

    pub fn text(role: &str, content: impl Into<String>) -> Self {
        Message {
            role: role.to_string(),
            content: Some(Content::text(content)),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    /// 带图片的 user message（多模态）。
    pub fn user_with_images(text: &str, image_parts: Vec<ContentPart>) -> Self {
        let mut parts = vec![ContentPart::Text {
            text: text.to_string(),
        }];
        parts.extend(image_parts);
        Message {
            role: "user".to_string(),
            content: Some(Content::Parts(parts)),
            tool_calls: None,
            tool_call_id: None,
            name: None,
        }
    }

    pub fn assistant_tool_calls(content: Option<Content>, calls: Vec<ToolCall>) -> Self {
        Message {
            role: "assistant".to_string(),
            content,
            tool_calls: Some(calls),
            tool_call_id: None,
            name: None,
        }
    }

    pub fn tool_result(
        tool_call_id: impl Into<String>,
        name: impl Into<String>,
        content: impl Into<String>,
    ) -> Self {
        Message {
            role: "tool".to_string(),
            content: Some(Content::text(content)),
            tool_calls: None,
            tool_call_id: Some(tool_call_id.into()),
            name: Some(name.into()),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ToolSpec {
    pub name: String,
    pub description: String,
    /// JSON Schema（object）
    pub parameters: serde_json::Value,
}

#[derive(Debug, Clone)]
pub struct GenerateRequest {
    pub messages: Vec<Message>,
    pub tools: Vec<ToolSpec>,
    pub model: Option<String>,
    pub max_tokens: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct ModelOutput {
    pub text: String,
    pub tool_calls: Vec<ToolCall>,
    /// 本次生成消耗的 token（后端提供时携带；端侧通常为 None）。
    pub usage: Option<Usage>,
}

/// 一次生成消耗的 token。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Usage {
    #[serde(default)]
    pub prompt_tokens: u32,
    #[serde(default)]
    pub completion_tokens: u32,
    #[serde(default)]
    pub total_tokens: u32,
}

pub trait ModelBackend: Send {
    /// 流式生成：每段文本回调一次 `on_token`（回调返回 Err 则中止）。
    /// 返回值含完整文本与工具调用。
    fn generate(
        &mut self,
        req: &GenerateRequest,
        on_token: &mut dyn FnMut(&str) -> Result<(), String>,
    ) -> Result<ModelOutput, String>;

    /// 该后端是否桩实现（不可用）。路由据此避开（AGENTS.md R2/R4）。
    fn is_stub(&self) -> bool {
        false
    }

    /// 该后端的路由身份。
    fn backend(&self) -> Backend;
}
