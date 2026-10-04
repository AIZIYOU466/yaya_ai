//! 云端模型后端（feature `cloud-http`）：OpenAI 兼容 `/chat/completions` 流式。
//!
//! 该实现放在 core，使云端推理逻辑跨端唯一；Android 无需重复实现 HTTP 客户端。
//!
//! 针对中转站的防御（见记忆 yaya-ai-relay-defense-layers）：
//! - 首 token / 分片间隔超时（`pick_timeout`），防「生成到一半停住」；
//! - 失败分级：`BackendError::Retry` 退避重试、`Fatal` 不重试、`Protocol` 判协议污染；
//! - 仅在未收到任何 body 字节时允许重试（已交付 token 后禁止，防重复/乱序）；
//! - 200 但 content-type 非 SSE 白名单（HTML/JSON 错误页）→ 判协议错误，不让上层拿到脏数据；
//! - 网络信号槽检查：onLost 后快速失败而非干等超时（信号只传状态，决策在 core）。

use futures::StreamExt;
use reqwest::Client;
use serde_json::Value;
use std::sync::Arc;
use tokio::time::{timeout, Duration};

use crate::agent::degrade::DegradeSignal;
use crate::agent::model::{BackendError, FailureKind, GenerateRequest, ModelBackend, ModelOutput};
use crate::agent::openai::{
    backoff_delay, classify_status, pick_timeout, request_body, ResponseAccumulator, TimeoutStage,
    MAX_ATTEMPTS,
};
use crate::agent::router::Backend;
use crate::agent::signals::SignalSlots;

/// 解析 `Retry-After` 头（仅整数秒；HTTP-date 等其它形式忽略）。
fn parse_retry_after(headers: &reqwest::header::HeaderMap) -> Option<u64> {
    let v = headers.get(reqwest::header::RETRY_AFTER)?.to_str().ok()?;
    v.trim().parse::<u64>().ok()
}

/// 校验响应 content-type 白名单。
/// 允许 `text/event-stream`（含参数）与空值；`text/html` / `application/json` 判协议错误；
/// 其它类型（如 `application/octet-stream`）放行，交给解析层决定。
fn check_content_type(headers: &reqwest::header::HeaderMap) -> Result<(), BackendError> {
    let Some(ct) = headers
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
    else {
        return Ok(()); // 老式服务器不返回 content-type
    };
    let base = ct.split(';').next().unwrap_or("").trim().to_ascii_lowercase();
    match base.as_str() {
        "text/event-stream" | "" => Ok(()),
        // 中转站错误页常伪装成 200 + HTML/JSON，直接判协议污染。
        "text/html" | "application/json" => {
            Err(BackendError::protocol(format!("响应 content-type 异常: {ct}")))
        }
        _ => Ok(()),
    }
}

/// 把 openai.rs 的协议错误文案归类为降级信号。
/// 文案来自 `ResponseAccumulator::{feed_line,finish}`（改动需同步此处匹配）。
fn classify_protocol_err(e: &str) -> (BackendError, bool) {
    let err = BackendError::protocol(e.to_string());
    if e.contains("tool_call arguments") {
        // arguments 未闭合 / 非法 JSON：schema 层面，阈值宽松。
        (err.with_signal(DegradeSignal::ToolSchema), true)
    } else {
        (err.with_signal(DegradeSignal::ToolProtocol), true)
    }
}

/// 单次尝试。返回 `(BackendError, body_received)`：`body_received` 表示本尝试是否已收到任何 body 字节，
/// 供外层判定是否允许重试（未收到字节才可重试，避免重复/乱序 token）。
#[allow(clippy::too_many_arguments)]
async fn run_attempt(
    client: &Client,
    endpoint: &str,
    api_key: &str,
    body: &Value,
    requested_max_tokens: Option<u32>,
    slots: &SignalSlots,
    on_token: &mut dyn FnMut(&str) -> Result<(), String>,
) -> Result<ModelOutput, (BackendError, bool)> {
    // 网络已断开：立即失败（Retry），避免连失效链路干等首 token。
    if slots.network_lost() {
        return Err((
            BackendError::retry("网络已断开，跳过云端请求")
                .with_signal(DegradeSignal::FirstTokenTimeout),
            false,
        ));
    }
    // send() 到响应头：首 token 超时兜底（connect_timeout 由 Client 配置）。
    let resp = timeout(
        pick_timeout(TimeoutStage::FirstToken),
        client.post(endpoint).bearer_auth(api_key).json(body).send(),
    )
    .await
    .map_err(|_| {
        (
            BackendError::retry("首 token 超时（等待响应头）")
                .with_signal(DegradeSignal::FirstTokenTimeout),
            false,
        )
    })?
    .map_err(|e| {
        (
            BackendError::retry(format!("请求发送失败: {e}"))
                .with_signal(DegradeSignal::FirstTokenTimeout),
            false,
        )
    })?;
    // 响应头已到但网络在发送期间断开：同样快速中止，让外层退避重试。
    if slots.network_lost() {
        return Err((
            BackendError::retry("响应等待期间网络断开").with_signal(DegradeSignal::ChunkStall),
            false,
        ));
    }

    let status = resp.status();
    let retry_after = if status.as_u16() == 429 {
        parse_retry_after(resp.headers())
    } else {
        None
    };
    if !status.is_success() {
        let text = resp.text().await.unwrap_or_default();
        // 只取前 4KB 做分类，避免大 HTML 错误页拖慢。
        let head: String = text.chars().take(4096).collect();
        let kind = classify_status(status.as_u16(), &head);
        let mut err = match kind {
            FailureKind::Fatal => BackendError::fatal(format!("HTTP {status}: {head}")),
            FailureKind::Retry => BackendError::retry(format!("HTTP {status}: {head}"))
                .with_signal(DegradeSignal::Http5xx),
            FailureKind::Protocol => unreachable!("classify_status 不返回 Protocol"),
        };
        err.retry_after = retry_after;
        // 错误体不是流式 token，未交付任何内容 → 允许重试（body_received=false）。
        return Err((err, false));
    }

    // 200 但 content-type 异常 → 协议污染，不重试。
    check_content_type(resp.headers()).map_err(|e| (e, false))?;

    let mut acc = ResponseAccumulator::new();
    let mut stream = resp.bytes_stream();
    let mut buf = String::new();
    let mut first_chunk = true;
    let mut body_received = false;

    loop {
        // 每个分片前检查网络丢失 → 立即中止而非等停顿超时（45s）。
        if slots.network_lost() {
            return Err((
                BackendError::retry("流式响应期间网络断开").with_signal(DegradeSignal::ChunkStall),
                body_received,
            ));
        }
        let stage = if first_chunk {
            TimeoutStage::InterChunkFirst
        } else {
            TimeoutStage::InterChunk
        };
        let next = timeout(pick_timeout(stage), stream.next()).await;
        match next {
            Err(_) => {
                // 分片间隔超时：中转站常见「返回一半卡住」。
                let message = if first_chunk {
                    "等待首个分片超时".to_string()
                } else {
                    "流式响应停顿超时".to_string()
                };
                let signal = if first_chunk {
                    DegradeSignal::FirstTokenTimeout
                } else {
                    DegradeSignal::ChunkStall
                };
                return Err((BackendError::retry(message).with_signal(signal), body_received));
            }
            Ok(None) => break, // 流自然结束
            Ok(Some(chunk)) => {
                body_received = true;
                let bytes = chunk.map_err(|e| {
                    (
                        BackendError::retry(format!("流读取失败: {e}"))
                            .with_signal(DegradeSignal::ChunkStall),
                        body_received,
                    )
                })?;
                buf.push_str(&String::from_utf8_lossy(&bytes));
                while let Some(pos) = buf.find('\n') {
                    let line: String = buf.drain(..=pos).collect();
                    if let Some(t) = acc
                        .feed_line(&line)
                        .map_err(|e| classify_protocol_err(&e))?
                    {
                        on_token(&t).map_err(|e| {
                            (
                                BackendError::fatal(format!("回调中止: {e}")),
                                body_received,
                            )
                        })?;
                    }
                }
                first_chunk = false;
            }
        }
    }
    if !buf.trim().is_empty() {
        if let Some(t) = acc
            .feed_line(&buf)
            .map_err(|e| classify_protocol_err(&e))?
        {
            on_token(&t).map_err(|e| {
                (
                    BackendError::fatal(format!("回调中止: {e}")),
                    body_received,
                )
            })?;
        }
    }
    let out = acc
        .finish(requested_max_tokens)
        .map_err(|e| classify_protocol_err(&e))?;
    Ok(out)
}

pub struct CloudBackend {
    endpoint: String,
    api_key: String,
    model: String,
    client: Client,
    rt: tokio::runtime::Runtime,
    slots: Arc<SignalSlots>,
}

impl CloudBackend {
    pub fn new(
        base_url: &str,
        api_key: &str,
        model: &str,
        slots: Arc<SignalSlots>,
    ) -> Result<Self, String> {
        let base = base_url.trim().trim_end_matches('/');
        if base.is_empty() {
            return Err("Base URL 为空".to_string());
        }
        let client = Client::builder()
            .connect_timeout(pick_timeout(TimeoutStage::Connect))
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
            slots,
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
    ) -> Result<ModelOutput, BackendError> {
        let body: Value = request_body(req, &self.model, req.stream);
        let client = self.client.clone();
        let endpoint = self.endpoint.clone();
        let api_key = self.api_key.clone();
        let requested_max_tokens = req.max_tokens;
        let slots = self.slots.clone();

        self.rt.block_on(async move {
            let mut attempt = 0u32;
            let mut body_received = false;
            loop {
                attempt += 1;
                match run_attempt(
                    &client,
                    &endpoint,
                    &api_key,
                    &body,
                    requested_max_tokens,
                    &slots,
                    on_token,
                )
                .await
                {
                    Ok(out) => return Ok(out),
                    Err((err, got_body)) => {
                        body_received |= got_body;
                        if err.kind == FailureKind::Retry
                            && !body_received
                            && attempt < MAX_ATTEMPTS
                        {
                            let base = backoff_delay(attempt);
                            let wait = err
                                .retry_after
                                .map(|secs| base.max(Duration::from_secs(secs)))
                                .unwrap_or(base);
                            tokio::time::sleep(wait).await;
                            continue;
                        }
                        return Err(err);
                    }
                }
            }
        })
    }

    fn backend(&self) -> Backend {
        Backend::Cloud
    }
}
