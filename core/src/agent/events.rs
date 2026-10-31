//! 循环机对外事件（AGENTS.md R6）。
//!
//! 同一份 JSON 协议供两条链路复用：
//! - Android：经 JNI sink 回调 → Kotlin → Dart
//! - 桌面：编码进 gRPC `TaskEvent.output`

use serde::{Deserialize, Serialize};

use super::state::TaskState;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Event {
    State {
        state: TaskState,
    },
    Token {
        text: String,
    },
    ToolCall {
        name: String,
        args: serde_json::Value,
    },
    /// 工具调用的策略判定（决策原因码）：供追溯每次调用为何被放行/确认/拒绝。
    /// `verdict` ∈ `allow` / `ask` / `deny`；`reason` 说明判定依据。
    ToolPolicy {
        name: String,
        verdict: String,
        reason: String,
    },
    ToolResult {
        name: String,
        ok: bool,
        content: String,
    },
    /// 一次生成的 token 用量（后端提供时发出），供 UI 累计与成本估算。
    /// 任务 30：`cached_tokens` 缓存命中、`duration_ms` 生成耗时、
    /// `kind` 区分来源（`None` = 正常回复；`Some("compact")` = 上下文摘要压缩）。
    Usage {
        prompt_tokens: u32,
        completion_tokens: u32,
        total_tokens: u32,
        #[serde(default)]
        cached_tokens: u32,
        #[serde(default)]
        duration_ms: u64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        kind: Option<String>,
    },
    /// 非致命提示（如 MCP 不可用）：不中断循环，仅供用户参考。
    /// `tag`：结构化标签（如 `behavior:schema:mismatch`），供评测按前缀聚合重放流；
    /// `serde(default)` 保证旧录制无该字段仍可反序列化，`None` 不落 JSON（旧消费端零感知）。
    Notice {
        message: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        tag: Option<String>,
    },
    /// 降级状态快照（任务结束前发出）：`state` 为 `DegradeState::snapshot()` 的 JSON 字符串，
    /// 供平台侧持久化（commit 2）跨任务延续降级；Dart 侧 switch 无 default 自动忽略。
    DegradeSnapshot {
        state: String,
    },
    /// 循环光标检查点（43）：`state` 为 `LoopState` 的 JSON 字符串，在工具调用执行后与
    /// 后台中断前发出，平台落盘后下次任务经 `RunConfig.loop_state` 注入续跑（已执行工具不重复）。
    LoopState {
        state: String,
    },
    /// 端点池选路（42）：质量感知路由选中端点（决策可观测）。
    EndpointSelected {
        id: String,
        index: usize,
    },
    /// 端点切换（42）：故障 failover 切换决策可观测（不新增决策路径，复用 Notice 语义不适用——
    /// 切换是结构性决策，独立事件供评测/回放聚合）。
    EndpointSwitched {
        from: String,
        to: String,
    },
    /// 子代理生命周期（任务 32）：同一轮可有多个并行子代理，`id` 标记归属
    /// （由 tool_call id 派生，如 `sub-call_1`），`phase` ∈ `started` / `finished` / `failed`。
    /// 子代理内部事件先缓冲、批次结束后随本事件一同回传，UI 可据此组成子会话。
    Subagent {
        id: String,
        agent: String,
        phase: String,
        /// 单行摘要：`started` 时为任务、结束时为结果/错误（截断 160 字）。
        detail: String,
    },
    /// 任务已挂起（任务 27）：循环光标已存档，平台可稍后经 `loop_state` 继续；
    /// 与 `Error` 区分——挂起不是失败，排队中的消息等待继续后再发送。
    Suspended {
        reason: String,
    },
    Done {
        text: String,
    },
    Error {
        message: String,
    },
}

impl Event {
    pub fn to_json(&self) -> String {
        serde_json::to_string(self)
            .unwrap_or_else(|_| "{\"type\":\"error\",\"message\":\"event 序列化失败\"}".to_string())
    }
}

/// `Event::Notice.tag` 的规范取值（core 内部枚举防字符串漂移，序列化层转规范字符串）。
/// 观测层（4C-2）与补偿层（4C-3）共用，均以 `behavior:` 前缀供评测聚合（ROADMAP 45）。
pub enum NoticeTag {
    /// tool_call 参数未通过其 ToolSpec 的 JSON Schema 校验。
    SchemaMismatch,
    /// 绝对活跃度异常：文本含强动词声称已执行，但窗口内零工具调用。
    IdleAbs,
    /// 相对活跃度异常：文本含意向词、该端点历史（≥20 轮）有工具调用基线，当前窗口零调用。
    IdleRel,
    /// L1 元信号注入（Boosted 档，位置轮换）。
    L1Injected,
    /// L2 显式 tool_choice=auto（Boosted 档生效）。
    L2Auto,
    /// L3 tool_choice=required 强制一轮（Forced 档）。
    L3Forced,
    /// 一次成功工具调用后行为环降一档（Forced→Boosted→Normal）。
    L3Recovered,
    /// canary 仲裁结果（L3 强制轮失败 ≥3 触发）。
    L3Canary,
}

impl NoticeTag {
    pub fn as_str(&self) -> &'static str {
        match self {
            NoticeTag::SchemaMismatch => "behavior:schema:mismatch",
            NoticeTag::IdleAbs => "behavior:idle:abs",
            NoticeTag::IdleRel => "behavior:idle:rel",
            NoticeTag::L1Injected => "behavior:L1:injected",
            NoticeTag::L2Auto => "behavior:L2:auto",
            NoticeTag::L3Forced => "behavior:L3:forced",
            NoticeTag::L3Recovered => "behavior:L3:recovered",
            NoticeTag::L3Canary => "behavior:L3:canary",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn notice_without_tag_serializes_like_before() {
        let e = Event::Notice {
            message: "m".into(),
            tag: None,
        };
        assert_eq!(e.to_json(), r#"{"type":"notice","message":"m"}"#);
    }

    #[test]
    fn old_recording_without_tag_deserializes() {
        let e: Event = serde_json::from_str(r#"{"type":"notice","message":"m"}"#).unwrap();
        assert_eq!(
            e,
            Event::Notice {
                message: "m".into(),
                tag: None,
            }
        );
    }

    #[test]
    fn suspended_event_roundtrip() {
        let e = Event::Suspended {
            reason: "用户暂停".into(),
        };
        let s = e.to_json();
        assert!(s.contains("\"type\":\"suspended\""));
        let back: Event = serde_json::from_str(&s).unwrap();
        assert_eq!(back, e);
    }

    #[test]
    fn notice_tag_roundtrip_and_behavior_prefix() {
        let e = Event::Notice {
            message: "m".into(),
            tag: Some(NoticeTag::IdleAbs.as_str().to_string()),
        };
        let s = e.to_json();
        assert!(s.contains("behavior:idle:abs"));
        let back: Event = serde_json::from_str(&s).unwrap();
        assert_eq!(back, e);
    }
}
