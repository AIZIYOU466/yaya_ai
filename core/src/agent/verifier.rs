//! 环境验证 oracle（ROADMAP 任务 20）：工具执行后自动验证效果，不假设成功。
//!
//! 可验证的工具有客观完成信号（如剪贴板写入后可读回比较）；其余工具视执行成功为
//! 完成（命令已返回输出、通知已发送）。验证失败会把原因回填给模型并标记失败。

use serde_json::Value;

use super::executor::{Action, ActionExecutor};

/// 验证计划：由工具名与参数决定。
pub enum VerifyPlan {
    /// 无需验证（通知、只读操作、命令已返回输出）。
    NoCheck,
    /// 读回比较：写入后读回须等于期望值（`clipboard_write`）。
    ReadBack { expected: String },
}

/// 依据工具名与参数决定验证计划。
pub fn plan_for(name: &str, args: &Value) -> VerifyPlan {
    match name {
        super::tools::TOOL_CLIPBOARD_WRITE => VerifyPlan::ReadBack {
            expected: args
                .get("text")
                .and_then(|t| t.as_str())
                .unwrap_or("")
                .to_string(),
        },
        _ => VerifyPlan::NoCheck,
    }
}

/// 执行验证：`Ok(())` 通过；`Err(原因)` 验证失败。
pub fn verify(
    plan: &VerifyPlan,
    executor: &mut dyn ActionExecutor,
) -> Result<(), String> {
    match plan {
        VerifyPlan::NoCheck => Ok(()),
        VerifyPlan::ReadBack { expected } => {
            let actual = executor
                .execute(&Action::ClipboardRead)
                .map_err(|e| format!("验证读回失败: {e}"))?;
            if actual == *expected {
                Ok(())
            } else {
                Err(format!(
                    "验证失败：写入后读回为「{actual}」，与预期「{expected}」不一致"
                ))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clipboard_write_plans_readback() {
        let plan = plan_for(super::super::tools::TOOL_CLIPBOARD_WRITE, &serde_json::json!({"text":"hi"}));
        match plan {
            VerifyPlan::ReadBack { expected } => assert_eq!(expected, "hi"),
            _ => panic!("clipboard_write 应有读回验证"),
        }
    }

    #[test]
    fn notify_is_no_check() {
        assert!(matches!(
            plan_for(super::super::tools::TOOL_NOTIFY, &serde_json::json!({})),
            VerifyPlan::NoCheck
        ));
    }

    struct ReadbackExecutor {
        current: String,
    }
    impl ActionExecutor for ReadbackExecutor {
        fn execute(&mut self, a: &Action) -> Result<String, String> {
            match a {
                Action::ClipboardRead => Ok(self.current.clone()),
                _ => Ok("done".into()),
            }
        }
    }

    #[test]
    fn verify_readback_passes_when_matching() {
        let mut exec = ReadbackExecutor { current: "hi".into() };
        assert!(verify(&VerifyPlan::ReadBack { expected: "hi".into() }, &mut exec).is_ok());
    }

    #[test]
    fn verify_readback_fails_on_mismatch() {
        let mut exec = ReadbackExecutor { current: "other".into() };
        let err = verify(&VerifyPlan::ReadBack { expected: "hi".into() }, &mut exec)
            .unwrap_err();
        assert!(err.contains("验证失败"));
    }
}
