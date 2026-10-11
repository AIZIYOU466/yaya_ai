//! 环境验证 oracle（ROADMAP 任务 20/40）：工具执行后自动验证效果，不假设成功。
//!
//! 验证计划（任务 40 泛化）按工具注册：可读回的写操作读回比较（剪贴板 / 文件内容
//! 哈希）；命令类动作断言退出码（terminal_exec）；其余视为执行成功。
//! 验证失败会把原因回填给模型并标记失败（R15 不静默）。

use serde_json::Value;
use sha2::{Digest, Sha256};

use super::executor::{Action, ActionExecutor};
use super::workspace::{self, FileAccess};

/// 验证计划：由工具名与参数决定。
pub enum VerifyPlan {
    /// 无需验证（通知、只读操作、命令已返回输出）。
    NoCheck,
    /// 读回比较：写入后读回须等于期望值（`clipboard_write`）。
    ReadBack { expected: String },
    /// 文件写入后读回内容，比对 sha256（`file_write`，R15 内容完整）。
    ReadBackHash { path: String, expected_hash: String },
    /// 终端命令断言退出码（`terminal_exec`）：`Some(n)` 精确匹配；`None` 仅要求返回退出码。
    ExitCode { expected: Option<i32> },
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    format!("{:x}", hasher.finalize())
}

/// 依据工具名与参数决定验证计划（工具层声明各自验证器）。
pub fn plan_for(name: &str, args: &Value) -> VerifyPlan {
    match name {
        super::tools::TOOL_CLIPBOARD_WRITE => VerifyPlan::ReadBack {
            expected: args
                .get("text")
                .and_then(|t| t.as_str())
                .unwrap_or("")
                .to_string(),
        },
        workspace::TOOL_FILE_WRITE => VerifyPlan::ReadBackHash {
            path: args.get("path").and_then(|p| p.as_str()).unwrap_or("").to_string(),
            expected_hash: sha256_hex(
                args.get("content").and_then(|c| c.as_str()).unwrap_or("").as_bytes(),
            ),
        },
        super::tools::TOOL_TERMINAL_EXEC => VerifyPlan::ExitCode { expected: Some(0) },
        _ => VerifyPlan::NoCheck,
    }
}

/// 执行验证：`Ok(())` 通过；`Err(原因)` 验证失败。
///
/// `exit_code` 是工具原始执行的退出码（terminal 类，重放命令有副作用故用已执行的结果）；
/// `files` 供 ReadBackHash 读回文件（`file_write` 不经 `ActionExecutor`）。
pub fn verify(
    plan: &VerifyPlan,
    exit_code: Option<i32>,
    executor: &mut dyn ActionExecutor,
    files: &mut Option<Box<dyn FileAccess>>,
) -> Result<(), String> {
    match plan {
        VerifyPlan::NoCheck => Ok(()),
        VerifyPlan::ReadBack { expected } => {
            let actual = executor
                .execute(&Action::ClipboardRead)
                .map_err(|e| format!("验证读回失败: {e}"))?
                .text;
            if actual == *expected {
                Ok(())
            } else {
                Err(format!(
                    "验证失败：写入后读回为「{actual}」，与预期「{expected}」不一致"
                ))
            }
        }
        VerifyPlan::ReadBackHash { path, expected_hash } => {
            let Some(file_box) = files.as_mut() else {
                return Err("文件验证需要 FileAccess（平台未注册）".into());
            };
            let actual = file_box
                .read(path)
                .map_err(|e| format!("验证读回失败: {e}"))?;
            if sha256_hex(actual.as_bytes()) == *expected_hash {
                Ok(())
            } else {
                Err(format!("验证失败：{path} 内容与写入时不一致（哈希不匹配）"))
            }
        }
        VerifyPlan::ExitCode { expected } => match expected {
            Some(n) => {
                if exit_code == Some(*n) {
                    Ok(())
                } else {
                    Err(format!(
                        "验证失败：命令退出码 {exit_code:?} 与预期 {n} 不符"
                    ))
                }
            }
            None => {
                if exit_code.is_some() {
                    Ok(())
                } else {
                    Err("验证失败：命令未返回退出码".into())
                }
            }
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::executor::ExecResult;
    use std::collections::HashMap;

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

    #[test]
    fn file_write_plans_hash_check() {
        let plan = plan_for(workspace::TOOL_FILE_WRITE, &serde_json::json!({
            "path": "a/b.txt",
            "content": "hi there",
        }));
        match &plan {
            VerifyPlan::ReadBackHash { path, expected_hash } => {
                assert_eq!(path, "a/b.txt");
                assert_eq!(expected_hash, &sha256_hex(b"hi there"));
            }
            _ => panic!("file_write 应有内容哈希验证"),
        }
    }

    #[test]
    fn terminal_plans_exit_code_zero() {
        assert!(matches!(
            plan_for(super::super::tools::TOOL_TERMINAL_EXEC, &serde_json::json!({})),
            VerifyPlan::ExitCode { expected: Some(0) }
        ));
    }

    struct ReadbackExecutor {
        current: String,
    }
    impl ActionExecutor for ReadbackExecutor {
        fn execute(&mut self, a: &Action) -> Result<ExecResult, String> {
            match a {
                Action::ClipboardRead => Ok(ExecResult::text(self.current.clone())),
                _ => Ok(ExecResult::text("done")),
            }
        }
    }

    struct MemFiles {
        files: HashMap<String, String>,
    }
    impl FileAccess for MemFiles {
        fn list(&mut self, _p: &str) -> Result<String, String> {
            Ok(self.files.keys().cloned().collect())
        }
        fn read(&mut self, path: &str) -> Result<String, String> {
            self.files
                .get(path)
                .cloned()
                .ok_or_else(|| format!("文件不存在: {path}"))
        }
        fn exists(&mut self, path: &str) -> Result<bool, String> {
            Ok(self.files.contains_key(path))
        }
        fn write(&mut self, path: &str, content: &str, _o: bool) -> Result<bool, String> {
            self.files.insert(path.into(), content.into());
            Ok(true)
        }
        fn delete(&mut self, _path: &str) -> Result<(), String> {
            Ok(())
        }
    }

    #[test]
    fn verify_readback_passes_when_matching() {
        let mut exec = ReadbackExecutor { current: "hi".into() };
        assert!(verify(&VerifyPlan::ReadBack { expected: "hi".into() }, None, &mut exec, &mut None).is_ok());
    }

    #[test]
    fn verify_readback_fails_on_mismatch() {
        let mut exec = ReadbackExecutor { current: "other".into() };
        let err = verify(&VerifyPlan::ReadBack { expected: "hi".into() }, None, &mut exec, &mut None)
            .unwrap_err();
        assert!(err.contains("验证失败"));
    }

    #[test]
    fn verify_hash_passes_when_content_matches() {
        let mut exec = ReadbackExecutor { current: String::new() };
        let files = MemFiles {
            files: HashMap::from([("a.txt".into(), "payload".into())]),
        };
        let plan = VerifyPlan::ReadBackHash {
            path: "a.txt".into(),
            expected_hash: sha256_hex(b"payload"),
        };
        assert!(verify(&plan, None, &mut exec, &mut Some(Box::new(files))).is_ok());
    }

    #[test]
    fn verify_hash_fails_on_content_change() {
        let mut exec = ReadbackExecutor { current: String::new() };
        let files = MemFiles {
            files: HashMap::from([("a.txt".into(), "tampered".into())]),
        };
        let plan = VerifyPlan::ReadBackHash {
            path: "a.txt".into(),
            expected_hash: sha256_hex(b"payload"),
        };
        let err = verify(&plan, None, &mut exec, &mut Some(Box::new(files))).unwrap_err();
        assert!(err.contains("哈希"));
    }

    #[test]
    fn verify_hash_requires_file_access() {
        let mut exec = ReadbackExecutor { current: String::new() };
        let plan = VerifyPlan::ReadBackHash {
            path: "a.txt".into(),
            expected_hash: "x".into(),
        };
        let err = verify(&plan, None, &mut exec, &mut None).unwrap_err();
        assert!(err.contains("FileAccess"));
    }

    #[test]
    fn verify_exit_code_assertions() {
        let mut exec = ReadbackExecutor { current: String::new() };
        // 精确匹配成功。
        assert!(verify(&VerifyPlan::ExitCode { expected: Some(0) }, Some(0), &mut exec, &mut None).is_ok());
        // 退出码不符失败。
        let err = verify(&VerifyPlan::ExitCode { expected: Some(0) }, Some(1), &mut exec, &mut None)
            .unwrap_err();
        assert!(err.contains("退出码"));
        // None 期望：仅要求返回退出码。
        assert!(verify(&VerifyPlan::ExitCode { expected: None }, Some(5), &mut exec, &mut None).is_ok());
        // 无退出码失败。
        let err = verify(&VerifyPlan::ExitCode { expected: None }, None, &mut exec, &mut None).unwrap_err();
        assert!(err.contains("未返回退出码"));
    }
}