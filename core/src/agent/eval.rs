//! Agent 能力评测基准（ROADMAP 任务 45）。
//!
//! 复用 `recorder.rs` 的 `ReplayBackend` 作为确定性测试集：对录制（真机/脚本产出）重放，
//! 从事件流聚合核心指标——工具调用准确率 / 多步完成率 / 上下文保持（重放一致性）/ 失败恢复率，
//! 以及行为环聚合（按 `Notice.tag` 的 `behavior:` 前缀统计，判定补偿层是否按预期触发）。
//! 纯 core、CI 可跑：不依赖平台实现（评测方注入 mock 或真实 `ActionExecutor`）。

use std::collections::HashMap;

use super::events::{Event, NoticeTag};
use super::recorder::{Recording, ReplayBackend};
use super::router::Backend;
use super::router::RouteHints;
use super::run::{run_loop, RunConfig};
use super::{ActionExecutor, AgentCore};

/// 单个录制（一次 run）的评测报告。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct EvalReport {
    /// 是否以 `Done` 收尾（未触达 `max_steps` 超限）。
    pub completed: bool,
    /// 工具调用总数（`Event::ToolCall`）。
    pub tool_calls: u32,
    /// 执行成功数（对应 `Event::ToolResult { ok: true }`）。
    pub tool_success: u32,
    /// schema 校验失败数（`Notice.tag == behavior:schema:mismatch`）。
    pub schema_mismatches: u32,
    /// 降级事件数（降级 Notice）。
    pub degraded_runs: u32,
    /// 恢复事件数（恢复 Notice）。
    pub restored_runs: u32,
    /// 重放事件流与录制一致（任务 38 的确定性保证，此处作「上下文保持」代理）。
    pub replay_consistent: bool,
    /// 行为环聚合：`Notice.tag` → 次数（`behavior:` 前缀；L1/L2/L3 判定见 4C-3）。
    pub behavior_tags: HashMap<String, u32>,
}

impl EvalReport {
    /// 工具调用准确率：成功执行 / 总调用（无调用按 1.0，不虚低）。
    pub fn tool_accuracy(&self) -> f64 {
        if self.tool_calls == 0 {
            1.0
        } else {
            self.tool_success as f64 / self.tool_calls as f64
        }
    }

    /// 失败恢复率：恢复事件 / 降级事件（无降级按 1.0）。
    pub fn recovery_rate(&self) -> f64 {
        if self.degraded_runs == 0 {
            1.0
        } else {
            self.restored_runs as f64 / self.degraded_runs as f64
        }
    }
}

/// 多个录制（评测集）的聚合摘要。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct EvalSummary {
    pub runs: u32,
    pub completed_runs: u32,
    pub total_tool_calls: u32,
    pub total_tool_success: u32,
    pub total_schema_mismatches: u32,
    /// 行为环聚合（跨录制累加）。
    pub behavior_tags: HashMap<String, u32>,
}

impl EvalSummary {
    /// 多步任务完成率。
    pub fn completion_rate(&self) -> f64 {
        if self.runs == 0 {
            1.0
        } else {
            self.completed_runs as f64 / self.runs as f64
        }
    }

    /// 工具调用准确率（聚合）。
    pub fn tool_accuracy(&self) -> f64 {
        if self.total_tool_calls == 0 {
            1.0
        } else {
            self.total_tool_success as f64 / self.total_tool_calls as f64
        }
    }

    pub fn push(&mut self, r: EvalReport) {
        self.runs += 1;
        if r.completed {
            self.completed_runs += 1;
        }
        self.total_tool_calls += r.tool_calls;
        self.total_tool_success += r.tool_success;
        self.total_schema_mismatches += r.schema_mismatches;
        for (tag, n) in r.behavior_tags {
            *self.behavior_tags.entry(tag).or_default() += n;
        }
    }
}

/// 对单个录制做确定性评测（CI 可跑）：重放 → 聚合事件 → 报告。
pub fn evaluate(
    recording: &Recording,
    cfg: &RunConfig,
    executor: Box<dyn ActionExecutor>,
) -> EvalReport {
    let mut core = AgentCore::new(executor);
    core.register_backend(Box::new(ReplayBackend::new(recording, Backend::Cloud)));
    core.hints = RouteHints {
        force: Some(Backend::Cloud),
        ..Default::default()
    };
    let mut events = Vec::new();
    let result = run_loop(&mut core, &recording.user_prompt, cfg, &mut |e| {
        events.push(e);
        Ok(())
    });
    let mut report = report_from(&events, result.is_ok());
    report.replay_consistent = events == recording.events;
    report
}

/// 从事件流聚合指标（纯函数，测试友好）。
pub fn report_from(events: &[Event], completed: bool) -> EvalReport {
    let mut r = EvalReport {
        completed,
        ..EvalReport::default()
    };
    for e in events {
        match e {
            Event::ToolCall { .. } => r.tool_calls += 1,
            Event::ToolResult { ok: true, .. } => r.tool_success += 1,
            Event::Notice { tag: Some(t), message, .. } => {
                *r.behavior_tags.entry(t.clone()).or_default() += 1;
                if t == NoticeTag::SchemaMismatch.as_str() {
                    r.schema_mismatches += 1;
                }
                if message.contains("已降级") {
                    r.degraded_runs += 1;
                } else if message.contains("已恢复") {
                    r.restored_runs += 1;
                }
            }
            Event::Notice {
                tag: None, message, ..
            } => {
                if message.contains("已降级") {
                    r.degraded_runs += 1;
                } else if message.contains("已恢复") {
                    r.restored_runs += 1;
                }
            }
            _ => {}
        }
    }
    r
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::executor::{Action, ActionExecutor, ExecResult};
    use crate::agent::model::{BackendError, GenerateRequest, ModelBackend, ModelOutput};
    use crate::agent::recorder::{RecordingBackend, RECORDING_VERSION};
    use crate::agent::tools::TOOL_NOTIFY;
    use std::collections::VecDeque;

    struct NoopExecutor;
    impl ActionExecutor for NoopExecutor {
        fn execute(&mut self, _a: &Action) -> Result<ExecResult, String> {
            Ok(ExecResult {
                text: "ok".into(),
                exit_code: Some(0),
            })
        }
    }

    /// 脚本化后端：先工具调用再收尾（多步任务）。
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
                on_token(&out.text).map_err(BackendError::fatal)?;
            }
            Ok(out)
        }
        fn backend(&self) -> Backend {
            Backend::Cloud
        }
    }

    fn notify_call(arguments: &str) -> ModelOutput {
        ModelOutput {
            text: String::new(),
            tool_calls: vec![crate::agent::model::ToolCall {
                id: "call_1".into(),
                kind: "function".into(),
                function: crate::agent::model::FunctionCall {
                    name: TOOL_NOTIFY.into(),
                    arguments: arguments.into(),
                },
            }],
            usage: None,
            warnings: vec![],
        }
    }

    /// 录制一次多步 run（notify → 收尾），返回完整 Recording + 配置。
    fn record_run(steps: Vec<ModelOutput>) -> (Recording, RunConfig) {
        let recorder = RecordingBackend::new(Scripted {
            steps: steps.into(),
        });
        let recorded = recorder.shared_turns();
        let mut core = AgentCore::new(Box::new(NoopExecutor));
        core.register_backend(Box::new(recorder));
        core.hints = RouteHints {
            force: Some(Backend::Cloud),
            ..Default::default()
        };
        let cfg = RunConfig::default();
        let mut events = Vec::new();
        run_loop(&mut core, "做一件事", &cfg, &mut |e| {
            events.push(e);
            Ok(())
        })
        .unwrap();
        let recording = Recording {
            version: RECORDING_VERSION,
            system_prompt: cfg.system_prompt.clone(),
            user_prompt: "做一件事".into(),
            turns: recorded.lock().unwrap().clone(),
            events,
        };
        (recording, cfg)
    }

    #[test]
    fn report_aggregates_tool_and_behavior_metrics() {
        let events = vec![
            Event::ToolCall {
                name: "notify".into(),
                args: serde_json::json!({}),
            },
            Event::ToolResult {
                name: "notify".into(),
                ok: true,
                content: "ok".into(),
            },
            Event::Notice {
                message: "工具 notify 参数 schema 校验失败：x".into(),
                tag: Some(NoticeTag::SchemaMismatch.as_str().to_string()),
            },
        ];
        let r = report_from(&events, true);
        assert!(r.completed);
        assert_eq!(r.tool_calls, 1);
        assert_eq!(r.tool_success, 1);
        assert_eq!(r.schema_mismatches, 1);
        assert_eq!(r.tool_accuracy(), 1.0);
        assert_eq!(
            r.behavior_tags.get(NoticeTag::SchemaMismatch.as_str()),
            Some(&1)
        );
    }

    #[test]
    fn evaluate_multistep_recording_scores_tool_accuracy() {
        let (recording, cfg) = record_run(vec![
            notify_call(r#"{"title":"t","body":"b"}"#),
            ModelOutput {
                text: "收尾".into(),
                tool_calls: vec![],
                usage: None,
                warnings: vec![],
            },
        ]);
        let report = evaluate(&recording, &cfg, Box::new(NoopExecutor));
        assert!(report.completed);
        assert_eq!(report.tool_calls, 1);
        assert_eq!(report.tool_success, 1);
        assert_eq!(report.tool_accuracy(), 1.0);
        // 上下文保持代理：重放与录制事件流一致。
        assert!(report.replay_consistent);
    }

    #[test]
    fn evaluate_detects_schema_mismatch_via_behavior_tag() {
        // 缺必填字段（title/body）的 notify 调用 → schema 校验失败 Notice。
        let (recording, cfg) = record_run(vec![
            notify_call(r#"{"title":"t"}"#),
            ModelOutput {
                text: "收尾".into(),
                tool_calls: vec![],
                usage: None,
                warnings: vec![],
            },
        ]);
        let report = evaluate(&recording, &cfg, Box::new(NoopExecutor));
        assert_eq!(report.schema_mismatches, 1);
        assert_eq!(
            report
                .behavior_tags
                .get(NoticeTag::SchemaMismatch.as_str()),
            Some(&1)
        );
    }

    #[test]
    fn summary_aggregates_rates_across_runs() {
        let mut s = EvalSummary::default();
        s.push(EvalReport {
            completed: true,
            tool_calls: 2,
            tool_success: 2,
            ..EvalReport::default()
        });
        s.push(EvalReport {
            completed: false,
            tool_calls: 1,
            tool_success: 0,
            schema_mismatches: 1,
            ..EvalReport::default()
        });
        assert_eq!(s.runs, 2);
        assert_eq!(s.completion_rate(), 0.5);
        assert_eq!(s.tool_accuracy(), 2.0 / 3.0);
        assert_eq!(s.total_schema_mismatches, 1);
    }

    #[test]
    fn recovery_rate_counts_degrade_restore_pairs() {
        let events = vec![
            Event::Notice {
                message: "端点异常（x），已降级为 no_tools 并重试".into(),
                tag: None,
            },
            Event::Notice {
                message: "端点质量已恢复，退出降级模式".into(),
                tag: None,
            },
        ];
        let r = report_from(&events, true);
        assert_eq!(r.degraded_runs, 1);
        assert_eq!(r.restored_runs, 1);
        assert_eq!(r.recovery_rate(), 1.0);
    }
}
