//! 动作执行抽象（AGENTS.md R6）：循环机经此 trait 下发动作，不感知平台。
//!
//! 平台实现：Android 由 Kotlin 经 JNI 提供（proot 终端、剪贴板、通知）。
//! 定位为「聊天与开发者助手」，不含任何设备操控（无障碍/屏幕操作已移除）。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Action {
    /// 终端命令（proot 容器）。
    Terminal {
        command: String,
        timeout_ms: u32,
    },
    /// 读取剪贴板文本。
    ClipboardRead,
    /// 写入剪贴板文本。
    ClipboardWrite {
        text: String,
    },
    /// 发送本地通知。
    Notify {
        title: String,
        body: String,
    },
}

pub trait ActionExecutor: Send {
    /// 执行动作，返回面向模型的结果文本（成功或失败说明）。
    fn execute(&mut self, action: &Action) -> Result<String, String>;
}