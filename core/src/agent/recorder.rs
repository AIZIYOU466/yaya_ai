//! 事件录制与确定性回放（ROADMAP 任务 38）。
//!
//! 目标：任何真机上失败的多步任务，都能变成容器内一条确定性 core 回归测试。
//!
//! - 录制：用 [`RecordingBackend`] 包一个真实后端，把每次 `generate` 的
//!   （token 流 + 输出/错误）记录成 [`Turn`]；同时把 `on_event` 全部事件收集进
//!   [`Recording`]。core 不持久化（R13）：`Recording` 可 JSON 序列化，
//!   落盘由平台（Kotlin）完成，回放读文件喂 [`ReplayBackend`]。
//! - 回放：用录制的 turns 构造 [`ReplayBackend`]，以相同 `RunConfig` 喂回
//!   `run_loop` 重跑，产出事件序列应与录制一致（确定性）。
//!
//! 确定性保证：`Turn` 按序记录每次 generate 的 token 流与输出（含错误类别/信号），
//! 回放完全按序复现——模型行为、降级路径、重试次数均与录制一致。

use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};

use super::degrade::DegradeSignal;
use super::events::Event;
use super::model::{BackendError, FailureKind, ModelBackend, ModelOutput};
use super::router::Backend;

pub const RECORDING_VERSION: u32 = 1;

/// 一次 generate 的完整可复现记录。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Turn {
    /// 该次生成的 token 流（逐次 on_token 调用，回放逐字复现，保住 Token 事件粒度）。
    pub tokens: Vec<String>,
    pub output: TurnOutput,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum TurnOutput {
    Ok(ModelOutput),
    Err {
        kind: String,
        signal: Option<String>,
        message: String,
    },
}

impl TurnOutput {
    fn to_backend_error(&self) -> BackendError {
        match self {
            TurnOutput::Ok(_) => BackendError::fatal("非错误"),
            TurnOutput::Err { kind, signal, message } => {
                let k = match kind.as_str() {
                    "retry" => FailureKind::Retry,
                    "protocol" => FailureKind::Protocol,
                    _ => FailureKind::Fatal,
                };
                let mut e = BackendError {
                    kind: k,
                    signal: signal.as_deref().and_then(signal_from_label),
                    message: message.clone(),
                    retry_after: None,
                };
                if e.signal.is_none() {
                    // 未知信号名：按失败类别补一个默认信号（不阻塞回放的降级路径）。
                    e.signal = Some(match k {
                        FailureKind::Retry => DegradeSignal::Http5xx,
                        FailureKind::Protocol => DegradeSignal::ToolProtocol,
                        FailureKind::Fatal => DegradeSignal::ToolSchema,
                    });
                }
                e
            }
        }
    }
}

fn failure_kind_label(k: FailureKind) -> &'static str {
    match k {
        FailureKind::Fatal => "fatal",
        FailureKind::Retry => "retry",
        FailureKind::Protocol => "protocol",
    }
}

fn signal_from_label(s: &str) -> Option<DegradeSignal> {
    match s {
        "tool_protocol" => Some(DegradeSignal::ToolProtocol),
        "tool_schema" => Some(DegradeSignal::ToolSchema),
        "first_token_timeout" => Some(DegradeSignal::FirstTokenTimeout),
        "chunk_stall" => Some(DegradeSignal::ChunkStall),
        "http_5xx" => Some(DegradeSignal::Http5xx),
        _ => None,
    }
}

fn signal_label(s: DegradeSignal) -> &'static str {
    match s {
        DegradeSignal::ToolProtocol => "tool_protocol",
        DegradeSignal::ToolSchema => "tool_schema",
        DegradeSignal::FirstTokenTimeout => "first_token_timeout",
        DegradeSignal::ChunkStall => "chunk_stall",
        DegradeSignal::Http5xx => "http_5xx",
    }
}

/// 一次 run 的完整录制（JSON 可序列化，落盘由平台完成）。
/// `#[serde(default)]`：缺字段/旧版本录制安全解析，不阻断回放。
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(default)]
pub struct Recording {
    pub version: u32,
    pub system_prompt: String,
    pub user_prompt: String,
    /// 按序的模型生成记录（回放的完整凭据）。
    pub turns: Vec<Turn>,
    /// 录制时的事件流（on_event 全量，回放后对比）。
    pub events: Vec<Event>,
}

impl Recording {
    pub fn json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }

    pub fn from_json(s: &str) -> Option<Self> {
        serde_json::from_str(s).ok()
    }
}

/// 录制用后端包装：记录每次 generate 的 token 流与输出，并透传行为。
/// `turns` 用 `Arc<Mutex>` 共享：录制方（平台/测试）持有副本在 run 结束后读取快照。
pub struct RecordingBackend<B: ModelBackend> {
    inner: B,
    turns: Arc<Mutex<Vec<Turn>>>,
}

impl<B: ModelBackend> RecordingBackend<B> {
    pub fn new(inner: B) -> Self {
        Self {
            inner,
            turns: Arc::new(Mutex::new(Vec::new())),
        }
    }

    /// 录制快照（run 结束后读取）。
    pub fn snapshot(&self) -> Vec<Turn> {
        self.turns.lock().unwrap().clone()
    }

    /// 共享句柄：注册进 core 前取副本，run 结束后读取录制。
    pub fn shared_turns(&self) -> Arc<Mutex<Vec<Turn>>> {
        self.turns.clone()
    }
}

impl<B: ModelBackend> ModelBackend for RecordingBackend<B> {
    fn generate(
        &mut self,
        req: &super::model::GenerateRequest,
        on_token: &mut dyn FnMut(&str) -> Result<(), String>,
    ) -> Result<ModelOutput, BackendError> {
        let mut tokens = Vec::new();
        let mut sink = |t: &str| {
            tokens.push(t.to_string());
            on_token(t)
        };
        match self.inner.generate(req, &mut sink) {
            Ok(out) => {
                self.turns.lock().unwrap().push(Turn {
                    tokens,
                    output: TurnOutput::Ok(out.clone()),
                });
                Ok(out)
            }
            Err(e) => {
                self.turns.lock().unwrap().push(Turn {
                    tokens,
                    output: TurnOutput::Err {
                        kind: failure_kind_label(e.kind).to_string(),
                        signal: e.signal.map(|s| signal_label(s).to_string()),
                        message: e.message.clone(),
                    },
                });
                Err(e)
            }
        }
    }

    fn backend(&self) -> Backend {
        self.inner.backend()
    }

    fn is_stub(&self) -> bool {
        self.inner.is_stub()
    }
}

/// 回放用后端：按录制顺序复现 generate（token 流 + 输出/错误），行为完全确定。
pub struct ReplayBackend {
    turns: Vec<Turn>,
    idx: usize,
    kind: Backend,
}

impl ReplayBackend {
    pub fn new(recording: &Recording, kind: Backend) -> Self {
        Self {
            turns: recording.turns.clone(),
            idx: 0,
            kind,
        }
    }

    /// 回放越界：录制 turns 数与重跑请求数不一致（如 run 参数变了）→ 明确失败，不静默。
    fn next_turn(&mut self) -> Result<Turn, BackendError> {
        let t = self
            .turns
            .get(self.idx)
            .cloned()
            .ok_or_else(|| BackendError::fatal("回放越界：录制 turns 不足以重放本次 run"))?;
        self.idx += 1;
        Ok(t)
    }
}

impl ModelBackend for ReplayBackend {
    fn generate(
        &mut self,
        _req: &super::model::GenerateRequest,
        on_token: &mut dyn FnMut(&str) -> Result<(), String>,
    ) -> Result<ModelOutput, BackendError> {
        let turn = self.next_turn()?;
        for t in &turn.tokens {
            on_token(t).map_err(BackendError::fatal)?;
        }
        match &turn.output {
            TurnOutput::Ok(out) => Ok(out.clone()),
            TurnOutput::Err { .. } => Err(turn.output.to_backend_error()),
        }
    }

    fn backend(&self) -> Backend {
        self.kind
    }

    fn is_stub(&self) -> bool {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::executor::{Action, ActionExecutor, ExecResult};
    use crate::agent::model::{GenerateRequest, ModelBackend};
    use crate::agent::router::RouteHints;
    use crate::agent::{run_loop, AgentCore, Event, RunConfig};
    use std::collections::VecDeque;

    /// 脚本化后端：按预定步骤输出（先工具调用再收尾），用于构造多步任务录制。
    struct Scripted {
        steps: VecDeque<ModelOutput>,
    }

    impl ModelBackend for Scripted {
        fn generate(
            &mut self,
            _req: &GenerateRequest,
            on_token: &mut dyn FnMut(&str) -> Result<(), String>,
        ) -> Result<ModelOutput, BackendError> {
            let out = self.steps.pop_front().unwrap_or_else(|| ModelOutput {
                text: "完成".into(),
                tool_calls: vec![],
                usage: None,
                warnings: vec![],
            });
            if !out.text.is_empty() {
                for ch in out.text.chars() {
                    on_token(&ch.to_string()).map_err(BackendError::fatal)?;
                }
            }
            Ok(out)
        }

        fn backend(&self) -> Backend {
            Backend::Cloud
        }
    }

    struct NoopExecutor;
    impl ActionExecutor for NoopExecutor {
        fn execute(&mut self, _action: &Action) -> Result<ExecResult, String> {
            Ok(ExecResult {
                text: "ok".into(),
                exit_code: Some(0),
            })
        }
    }

    fn tool_call_output() -> ModelOutput {
        ModelOutput {
            text: String::new(),
            tool_calls: vec![crate::agent::model::ToolCall {
                id: "call_1".into(),
                kind: "function".into(),
                function: crate::agent::model::FunctionCall {
                    name: "notify".into(),
                    arguments: r#"{"title":"t","body":"b"}"#.into(),
                },
            }],
            usage: None,
            warnings: vec![],
        }
    }

    fn run_with(
        core: &mut AgentCore,
        prompt: &str,
        cfg: &RunConfig,
        events: &mut Vec<Event>,
    ) -> Result<String, String> {
        run_loop(core, prompt, cfg, &mut |e| {
            events.push(e);
            Ok(())
        })
    }

    #[test]
    fn recording_replay_produces_identical_events() {
        // 构造一个多步任务（工具调用 → 结果回填 → 收尾）。
        let recorder = RecordingBackend::new(Scripted {
            steps: VecDeque::from(vec![tool_call_output(), ModelOutput {
                text: "收尾文本".into(),
                tool_calls: vec![],
                usage: Some(crate::agent::model::Usage {
                    prompt_tokens: 10,
                    completion_tokens: 5,
                    total_tokens: 15,
                    cached_tokens: 0,
                }),
                warnings: vec![],
            }]),
        });
        // 注册前取共享句柄，run 结束后读取真实录制。
        let recorded = recorder.shared_turns();

        let mut core = AgentCore::new(Box::new(NoopExecutor));
        core.register_backend(Box::new(recorder));
        core.hints = RouteHints {
            force: Some(Backend::Cloud),
            ..Default::default()
        };
        let cfg = RunConfig::default();

        let mut events = Vec::new();
        let text = run_with(&mut core, "做件事", &cfg, &mut events).unwrap();
        assert_eq!(text, "收尾文本");

        // 用真实录制（turns + 事件流）构造 Recording，JSON 往返后回放。
        let recording = Recording {
            version: RECORDING_VERSION,
            system_prompt: cfg.system_prompt.clone(),
            user_prompt: "做件事".into(),
            turns: recorded.lock().unwrap().clone(),
            events: events.clone(),
        };
        assert!(!recording.turns.is_empty(), "录制应含至少一次模型生成");

        let json = recording.json();
        let parsed = Recording::from_json(&json).expect("录制 JSON 应可解析");
        assert_eq!(parsed, recording);

        // 回放：同一 cfg 重跑，事件序列应与录制一致（确定性）。
        let mut core2 = AgentCore::new(Box::new(NoopExecutor));
        core2.register_backend(Box::new(ReplayBackend::new(&parsed, Backend::Cloud)));
        core2.hints = RouteHints {
            force: Some(Backend::Cloud),
            ..Default::default()
        };
        let mut events2 = Vec::new();
        let text2 = run_with(&mut core2, "做件事", &cfg, &mut events2).unwrap();
        assert_eq!(text2, "收尾文本");
        assert_eq!(events2, events, "回放事件序列应与录制一致");

        // 回放应复现工具调用事件（含参数文本）。
        assert!(
            events2.iter().any(|e| matches!(e, Event::ToolCall { name, .. } if name == "notify")),
            "回放应复现工具调用事件"
        );
    }

    #[test]
    fn recording_json_roundtrip() {
        let r = Recording {
            version: RECORDING_VERSION,
            system_prompt: "sys".into(),
            user_prompt: "hi".into(),
            turns: vec![Turn {
                tokens: vec!["a".into()],
                output: TurnOutput::Err {
                    kind: "retry".into(),
                    signal: Some("http_5xx".into()),
                    message: "boom".into(),
                },
            }],
            events: vec![Event::Token {
                text: "x".into(),
            }],
        };
        let parsed = Recording::from_json(&r.json()).unwrap();
        assert_eq!(parsed, r);
        // 未知版本/缺字段不应 panic（安全默认）。
        assert!(Recording::from_json("not json").is_none());
        assert!(Recording::from_json("{}").is_some());
    }
}