//! 动作执行抽象（AGENTS.md R6）：循环机经此 trait 下发动作，不感知平台。
//!
//! 平台实现：Android 由无障碍手势/文本注入与 proot 终端经 JNI 提供；桌面由原生注入提供。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ScrollDir {
    Forward,
    Backward,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SystemKind {
    Back,
    Home,
    Recents,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Action {
    /// 点击节点；缺 id 时用坐标。
    Tap {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        id: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        x: Option<i32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        y: Option<i32>,
    },
    Input {
        id: String,
        text: String,
    },
    Scroll {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        id: Option<String>,
        direction: ScrollDir,
    },
    Swipe {
        from_x: i32,
        from_y: i32,
        to_x: i32,
        to_y: i32,
        duration_ms: u32,
    },
    System {
        kind: SystemKind,
    },
    Launch {
        package: String,
    },
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
