//! 任务状态机（AGENTS.md R6 规范源；循环机 [`crate::agent::loop`] 驱动，Android/桌面共用）。

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskState {
    Idle,
    Planning,
    Executing,
    Streaming,
    Done,
    Failed,
}

pub fn can_transition(from: TaskState, to: TaskState) -> bool {
    use TaskState::*;
    if from == to {
        return true;
    }
    matches!(
        (from, to),
        (Idle, Planning)
            | (Planning, Executing)
            | (Planning, Failed)
            | (Executing, Streaming)
            | (Executing, Failed)
            // 多步：本轮流式产出工具调用后，回到 Executing 执行并进入下一轮
            | (Streaming, Executing)
            | (Streaming, Done)
            | (Streaming, Failed)
            | (Done, Idle)
            | (Failed, Idle)
    )
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskMachine {
    state: TaskState,
}

impl Default for TaskMachine {
    fn default() -> Self {
        TaskMachine {
            state: TaskState::Idle,
        }
    }
}

impl TaskMachine {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn state(&self) -> TaskState {
        self.state
    }

    pub fn transition(&mut self, to: TaskState) -> Result<TaskState, String> {
        if can_transition(self.state, to) {
            self.state = to;
            Ok(to)
        } else {
            Err(format!("非法状态转移: {:?} -> {:?}", self.state, to))
        }
    }

    pub fn reset(&mut self) -> Result<TaskState, String> {
        match self.state {
            TaskState::Done | TaskState::Failed => self.transition(TaskState::Idle),
            other => Err(format!("仅 Done/Failed 可复位，当前 {:?}", other)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use TaskState::*;

    #[test]
    fn happy_path() {
        let mut m = TaskMachine::new();
        for s in [Planning, Executing, Streaming, Done] {
            assert_eq!(m.transition(s).unwrap(), s);
        }
        assert_eq!(m.reset().unwrap(), Idle);
    }

    #[test]
    fn failure_paths() {
        let mut m = TaskMachine::new();
        m.transition(Planning).unwrap();
        assert_eq!(m.transition(Failed).unwrap(), Failed);
        assert_eq!(m.reset().unwrap(), Idle);
    }

    #[test]
    fn illegal_transitions_rejected() {
        let mut m = TaskMachine::new();
        assert!(m.transition(Done).is_err());
        assert!(m.transition(Streaming).is_err());
        m.transition(Planning).unwrap();
        assert!(m.transition(Done).is_err());
        assert!(m.transition(Idle).is_err());
    }

    #[test]
    fn tool_loop_reenters_executing() {
        let mut m = TaskMachine::new();
        for s in [Planning, Executing, Streaming] {
            m.transition(s).unwrap();
        }
        assert_eq!(m.transition(Executing).unwrap(), Executing);
        m.transition(Streaming).unwrap();
        assert_eq!(m.transition(Done).unwrap(), Done);
    }

    #[test]
    fn cannot_reset_mid_flight() {
        let mut m = TaskMachine::new();
        m.transition(Planning).unwrap();
        assert!(m.reset().is_err());
    }

    #[test]
    fn idempotent_self_transition_allowed() {
        let mut m = TaskMachine::new();
        m.transition(Planning).unwrap();
        assert!(m.transition(Planning).is_ok());
    }
}
