//! 金丝雀检测（ROADMAP 任务 14）：用已知答案的探测题监控输出质量漂移。
//!
//! 默认关闭；启用时在任务开始前运行探测，结果异常经 `Event::Notice` 上报（不阻断任务）。
//! 探测题走一次生成（单步、低 token），成本可忽略。

use super::{run_loop, AgentCore, Event, RunConfig};

/// 一道探测题（prompt + 期望输出包含的关键字）。
pub struct CanaryProbe {
    pub prompt: String,
    pub expect: String,
}

/// 默认探测题：简单算术（强模型与降级模型应可区分）。
pub fn default_probes() -> Vec<CanaryProbe> {
    vec![CanaryProbe {
        prompt: "只输出一个数字：1+1 等于几？".into(),
        expect: "2".into(),
    }]
}

/// 运行金丝雀：逐题跑一轮生成，检查输出含期望关键字。
/// 全部通过返回 `None`；有失败返回告警文本（供 `Notice` 上报）。
pub fn run(
    core: &mut AgentCore,
    cfg: &RunConfig,
    on_event: &mut dyn FnMut(Event) -> Result<(), String>,
) -> Option<String> {
    let probes = default_probes();
    let mut failed = Vec::new();
    for p in &probes {
        let sub_cfg = RunConfig {
            system_prompt: cfg.system_prompt.clone(),
            model: cfg.model.clone(),
            max_steps: 1,
            max_tokens: Some(64),
            max_messages: 8,
            mode: cfg.mode,
            skills_dir: None,
            mcp_tool_allowlist: None,
            capabilities: cfg.capabilities,
            canary: false,
        };
        match run_loop(core, &p.prompt, &sub_cfg, on_event) {
            Ok(text) if text.contains(&p.expect) => {}
            Ok(_) | Err(_) => failed.push(p.prompt.clone()),
        }
    }
    if failed.is_empty() {
        None
    } else {
        Some(format!(
            "金丝雀检测：{} 道探测未达预期（模型输出质量可能漂移）",
            failed.len()
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::executor::{Action, ActionExecutor};
    use crate::agent::model::{GenerateRequest, ModelBackend, ModelOutput};
    use crate::agent::router::Backend;
    use crate::agent::RouteHints;

    struct Scripted {
        text: String,
    }
    impl ModelBackend for Scripted {
        fn generate(
            &mut self,
            _req: &GenerateRequest,
            _on_token: &mut dyn FnMut(&str) -> Result<(), String>,
        ) -> Result<ModelOutput, String> {
            Ok(ModelOutput {
                text: self.text.clone(),
                tool_calls: vec![],
                usage: None,
            })
        }
        fn backend(&self) -> Backend {
            Backend::Cloud
        }
    }

    struct NoopExecutor;
    impl ActionExecutor for NoopExecutor {
        fn execute(&mut self, _a: &Action) -> Result<String, String> {
            Ok("done".into())
        }
    }

    fn core_with_answer(text: &str) -> AgentCore {
        let mut core = AgentCore::new(Box::new(NoopExecutor));
        core.register_backend(Box::new(Scripted {
            text: text.to_string(),
        }));
        core.hints = RouteHints {
            force: Some(Backend::Cloud),
            ..Default::default()
        };
        core
    }

    #[test]
    fn canary_passes_when_output_matches() {
        let mut core = core_with_answer("2");
        assert!(run(&mut core, &RunConfig::default(), &mut |_| Ok(())).is_none());
    }

    #[test]
    fn canary_reports_when_output_diverges() {
        let mut core = core_with_answer("x");
        let warn = run(&mut core, &RunConfig::default(), &mut |_| Ok(())).unwrap();
        assert!(warn.contains("金丝雀检测"));
    }
}
