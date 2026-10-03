//! 云端模型后端（feature `cloud-http`）：OpenAI 兼容 `/chat/completions` 流式。
//!
//! 该实现放在 core，使云端推理逻辑跨端唯一；Android 无需重复实现 HTTP 客户端。

use futures::StreamExt;
use reqwest::Client;
use serde_json::Value;

use crate::agent::model::{GenerateRequest, ModelBackend, ModelOutput};
use crate::agent::openai::{request_body, ResponseAccumulator};
use crate::agent::router::Backend;

pub struct CloudBackend {
    endpoint: String,
    api_key: String,
    model: String,
    client: Client,
    rt: tokio::runtime::Runtime,
}

impl CloudBackend {
    pub fn new(base_url: &str, api_key: &str, model: &str) -> Result<Self, String> {
        let base = base_url.trim().trim_end_matches('/');
        if base.is_empty() {
            return Err("Base URL 为空".to_string());
        }
        let client = Client::builder()
            .build()
            .map_err(|e| format!("HTTP 客户端创建失败: {e}"))?;
        let rt = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .map_err(|e| format!("tokio runtime 创建失败: {e}"))?;
        Ok(CloudBackend {
            endpoint: format!("{base}/chat/completions"),
            api_key: api_key.to_string(),
            model: model.to_string(),
            client,
            rt,
        })
    }

    pub fn set_model(&mut self, model: &str) {
        self.model = model.to_string();
    }
}

impl ModelBackend for CloudBackend {
    fn generate(
        &mut self,
        req: &GenerateRequest,
        on_token: &mut dyn FnMut(&str) -> Result<(), String>,
    ) -> Result<ModelOutput, String> {
        let body: Value = request_body(req, &self.model, true);
        let client = self.client.clone();
        let endpoint = self.endpoint.clone();
        let api_key = self.api_key.clone();

        self.rt.block_on(async move {
            let resp = client
                .post(&endpoint)
                .bearer_auth(&api_key)
                .json(&body)
                .send()
                .await
                .map_err(|e| format!("请求失败: {e}"))?;
            let status = resp.status();
            if !status.is_success() {
                let text = resp.text().await.unwrap_or_default();
                return Err(format!(
                    "HTTP {status}: {}",
                    text.chars().take(500).collect::<String>()
                ));
            }

            let mut acc = ResponseAccumulator::new();
            let mut stream = resp.bytes_stream();
            let mut buf = String::new();
            while let Some(chunk) = stream.next().await {
                let bytes = chunk.map_err(|e| format!("流读取失败: {e}"))?;
                buf.push_str(&String::from_utf8_lossy(&bytes));
                while let Some(pos) = buf.find('\n') {
                    let line: String = buf.drain(..=pos).collect();
                    if let Some(t) = acc.feed_line(&line)? {
                        on_token(&t)?;
                    }
                }
            }
            if !buf.trim().is_empty() {
                if let Some(t) = acc.feed_line(&buf)? {
                    on_token(&t)?;
                }
            }
            Ok(acc.finish())
        })
    }

    fn backend(&self) -> Backend {
        Backend::Cloud
    }
}
