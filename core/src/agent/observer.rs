//! 屏幕观察抽象（AGENTS.md R6）：循环机经此 trait 获取界面树，不感知平台。//
//! 平台实现：Android 由无障碍服务经 JNI 提供；桌面由 UI Automation / AX / AT-SPI 提供。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Rect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Node {
    /// 稳定标识（Android 为“子索引路径”，如 `0/1/3`），供后续动作定位。
    pub id: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub class: String,
    /// contentDescription / 无障碍描述
    #[serde(default)]
    pub desc: String,
    #[serde(default)]
    pub bounds: Rect,
    #[serde(default)]
    pub clickable: bool,
    #[serde(default)]
    pub editable: bool,
    #[serde(default)]
    pub scrollable: bool,
    #[serde(default)]
    pub children: Vec<Node>,
}

impl Node {
    /// 渲染成模型可读的紧凑树文本（带缩进与可交互标记）。
    pub fn to_prompt_text(&self) -> String {
        let mut out = String::new();
        self.write_tree(&mut out, 0);
        out
    }

    fn write_tree(&self, out: &mut String, depth: usize) {
        let pad = "  ".repeat(depth);
        let mut tags = String::new();
        if self.clickable {
            tags.push_str(" clickable");
        }
        if self.editable {
            tags.push_str(" editable");
        }
        if self.scrollable {
            tags.push_str(" scrollable");
        }
        let label = if !self.text.is_empty() {
            self.text.as_str()
        } else {
            self.desc.as_str()
        };
        out.push_str(&format!(
            "{} - [{}] {}{}{}\n",
            pad,
            self.id,
            self.class,
            if label.is_empty() {
                String::new()
            } else {
                format!(" \"{}\"", label)
            },
            tags
        ));
        for child in &self.children {
            child.write_tree(out, depth + 1);
        }
    }
}

pub trait ScreenObserver: Send {
    fn observe(&mut self) -> Result<Node, String>;
    fn observe_image(&mut self) -> Result<String, String>;
}
