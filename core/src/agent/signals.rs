//! 平台环境信号槽（commit 2）：Android 层只负责"感知环境 + 传信号"，
//! core 层决定行为（路由避开、流中止、金丝雀降频）。
//!
//! 信号槽是共享 `Arc`：JNI 层持有全局实例并写入（`nativeSetNetworkLost` 等），
//! `AgentCore` / `CloudBackend` 持有同一实例读取。仅传信号、不传决策——
//! 决策（是否降级、是否中止、是否跳过探测）全部留在 core。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

/// 环境信号槽（默认全 false = 正常）。
#[derive(Default)]
pub struct SignalSlots {
    /// 网络已断开（onLost 置位 / onAvailable 复位）。置位时 cloud 后端不可信任，
    /// 新建请求应快速失败并让路由避开，避免"连到失效链路干等首 token"。
    pub network_lost: AtomicBool,
    /// App 切后台（onStop 置位 / onStart 复位）。置位时不再发起新的模型生成，
    /// 当前任务经 Notice 说明后结束（恢复靠 R13 检查点重跑，SSE 无法真正暂停）。
    pub app_background: AtomicBool,
    /// 系统省电模式。置位时跳过金丝雀等额外请求（降频健康探测）。
    pub power_save: AtomicBool,
}

impl SignalSlots {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn network_lost(&self) -> bool {
        self.network_lost.load(Ordering::Relaxed)
    }

    pub fn app_background(&self) -> bool {
        self.app_background.load(Ordering::Relaxed)
    }

    pub fn power_save(&self) -> bool {
        self.power_save.load(Ordering::Relaxed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_all_clear() {
        let s = SignalSlots::new();
        assert!(!s.network_lost());
        assert!(!s.app_background());
        assert!(!s.power_save());
    }

    #[test]
    fn slots_are_shared_arcs() {
        let s = SignalSlots::new();
        let s2 = s.clone();
        s.network_lost.store(true, Ordering::Relaxed);
        assert!(s2.network_lost());
    }
}