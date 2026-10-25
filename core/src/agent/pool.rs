//! 端点池与质量感知路由（ROADMAP 任务 42）。
//!
//! - [`EndpointProfile`]：端点档案（id / kind / 静态能力 / 动态健康）；
//!   id = [`normalize_endpoint_id`]（4C-2，宁分勿合）。
//! - [`EndpointPool`]：多端点配置 + 质量感知选路（过滤可用 + 按 health / 复杂度打分）
//!   + 故障切换（`switch_away_from`）+ 快照往返（「降级快照含池健康」）。
//!
//! 降级模式是「端点池的零号档」（degrade.rs）：单端点也有 failover 目标（切到降级而非切端点），
//! 加端点池只是多一个目标，架构不动。同 kind 多后端实例注册与任务 25（多模型提供商）配套接线。

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::capability::Capabilities;
use super::degrade::normalize_endpoint_id;
use super::router::{Backend, Complexity, RouteHints};

/// 端点健康度（质量感知路由的打分依据）：EMA 成功率，初值 1.0（新端点满分起，靠失败降权）。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct EndpointHealth {
    /// 指数移动平均成功率（0.0-1.0）。
    pub score: f64,
    pub successes: u32,
    pub failures: u32,
}

impl EndpointHealth {
    /// EMA 平滑系数：单次结果影响 30%，历史占 70%。
    const ALPHA: f64 = 0.3;

    pub fn new() -> Self {
        EndpointHealth {
            score: 1.0,
            successes: 0,
            failures: 0,
        }
    }

    /// 记一次成功：score 向 1.0 收敛。
    pub fn note_success(&mut self) {
        self.successes = self.successes.saturating_add(1);
        self.score += (1.0 - self.score) * Self::ALPHA;
    }

    /// 记一次失败：score 向 0.0 收敛。
    pub fn note_failure(&mut self) {
        self.failures = self.failures.saturating_add(1);
        self.score += (0.0 - self.score) * Self::ALPHA;
    }
}

impl Default for EndpointHealth {
    fn default() -> Self {
        Self::new()
    }
}

/// 单个端点档案（42）。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EndpointProfile {
    /// 端点 id = `normalize_endpoint_id(base_url)`（4C-2）。
    pub id: String,
    /// 后端类型（`Backend::Error` 不入池）。
    pub kind: Backend,
    /// 静态能力（任务 13）：token 钳制 / tools / vision 开关的端点级声明。
    #[serde(default)]
    pub capabilities: Capabilities,
    /// 动态健康（打分依据）。
    #[serde(default)]
    pub health: EndpointHealth,
}

impl EndpointProfile {
    /// 按 base_url 归一化 id 构造（宁分勿合：同 host 不同 path 分桶）。
    pub fn new(base_url: &str, kind: Backend) -> Self {
        EndpointProfile {
            id: normalize_endpoint_id(base_url),
            kind,
            capabilities: Capabilities::default(),
            health: EndpointHealth::new(),
        }
    }
}

/// 端点池（42）：多端点 + 质量感知路由 + 故障切换。
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct EndpointPool {
    pub endpoints: Vec<EndpointProfile>,
    /// 当前选中下标（None = 未选）。
    #[serde(default)]
    pub current: Option<usize>,
}

impl EndpointPool {
    pub fn new(endpoints: Vec<EndpointProfile>) -> Self {
        EndpointPool {
            endpoints,
            current: None,
        }
    }

    /// 质量感知选路：过滤可用（kind 对应 hints + health 高于阈值）→ 打分（health + 复杂度匹配）
    /// → 最高分。返回池下标并更新 `current`。
    pub fn select(&mut self, hints: &RouteHints, budget: Complexity) -> Option<usize> {
        let idx = self.select_excluding(hints, budget, None)?;
        self.current = Some(idx);
        Some(idx)
    }

    /// 用户显式选择（任务 25：对话页模型切换 → 提供商端点）。
    /// 优先选中 `preferred`（按 id 归一化匹配），只校验 kind 可用性、不看历史健康分
    /// （尊重用户强制选择）；不存在/不可用时回退质量感知 [`select`]。
    pub fn select_preferred(
        &mut self,
        preferred: Option<&str>,
        hints: &RouteHints,
        budget: Complexity,
    ) -> Option<usize> {
        if let Some(pref) = preferred {
            let id = normalize_endpoint_id(pref);
            if let Some(i) = self
                .endpoints
                .iter()
                .position(|e| e.id == id && Self::usable(e, hints))
            {
                self.current = Some(i);
                return Some(i);
            }
        }
        self.select(hints, budget)
    }

    /// 端点 kind 在当前信号下是否可用（health 阈值另由质量选路判定）。
    fn usable(e: &EndpointProfile, hints: &RouteHints) -> bool {
        match e.kind {
            Backend::Cloud => hints.cloud_ok && hints.network_ok,
            Backend::Desktop => hints.desktop_ok,
            Backend::Jni => hints.local_ok,
            Backend::Error => false,
        }
    }

    /// 故障切换：当前端点失败（记 health 降权）后选下一个可用端点（排除 failed_id）。
    /// 无可用替代返回 None（调用方回退原失败路径，不静默）。
    pub fn switch_away_from(
        &mut self,
        failed_id: &str,
        hints: &RouteHints,
        budget: Complexity,
    ) -> Option<usize> {
        self.note_result(failed_id, false);
        let idx = self.select_excluding(hints, budget, Some(failed_id))?;
        self.current = Some(idx);
        Some(idx)
    }

    fn select_excluding(
        &self,
        hints: &RouteHints,
        budget: Complexity,
        exclude: Option<&str>,
    ) -> Option<usize> {
        let mut best: Option<(usize, f64)> = None;
        for (i, e) in self.endpoints.iter().enumerate() {
            if Some(e.id.as_str()) == exclude {
                continue;
            }
            // 故障端点（score 塌陷）不参与选路。
            if !Self::usable(e, hints) || e.health.score <= 0.05 {
                continue;
            }
            let mut score = e.health.score;
            // 复杂度匹配：Hard 偏好云端/桌面（大模型），Simple 偏好端侧（低成本）。
            score += match (budget, e.kind) {
                (Complexity::Hard, Backend::Jni) => -0.2,
                (Complexity::Hard, _) => 0.1,
                (Complexity::Simple, Backend::Jni) => 0.1,
                (Complexity::Simple, _) => 0.0,
                (Complexity::Medium, _) => 0.0,
            };
            if best.map(|(_, s)| score > s).unwrap_or(true) {
                best = Some((i, score));
            }
        }
        best.map(|(i, _)| i)
    }

    /// 记录端点结果（成功/失败），供质量感知打分。
    pub fn note_result(&mut self, id: &str, ok: bool) {
        if let Some(e) = self.endpoints.iter_mut().find(|e| e.id == id) {
            if ok {
                e.health.note_success();
            } else {
                e.health.note_failure();
            }
        }
    }

    /// 池健康快照（「降级快照含池健康」：平台落盘跨任务延续路由质量）。
    pub fn snapshot(&self) -> Value {
        serde_json::to_value(self).unwrap_or(Value::Null)
    }

    /// 从快照恢复；非法/缺失回退 None（安全默认）。
    pub fn from_snapshot(v: &Value) -> Option<Self> {
        serde_json::from_value(v.clone()).ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hints(cloud: bool, net: bool) -> RouteHints {
        RouteHints {
            cloud_ok: cloud,
            network_ok: net,
            ..Default::default()
        }
    }

    #[test]
    fn health_ema_moves_toward_outcomes() {
        let mut h = EndpointHealth::new();
        assert_eq!(h.score, 1.0);
        h.note_failure();
        assert!(h.score < 1.0 && h.score > 0.0);
        let after_fail = h.score;
        h.note_success();
        assert!(h.score > after_fail);
        assert_eq!(h.successes, 1);
        assert_eq!(h.failures, 1);
    }

    #[test]
    fn select_prefers_healthier_endpoint() {
        let mut a = EndpointProfile::new("https://a.example.com/v1", Backend::Cloud);
        a.health.note_failure();
        a.health.note_failure();
        let mut b = EndpointProfile::new("https://b.example.com/v1", Backend::Cloud);
        b.health.note_success();
        let mut pool = EndpointPool::new(vec![a, b]);
        let idx = pool.select(&hints(true, true), Complexity::Medium).unwrap();
        assert_eq!(idx, 1, "健康度更高的端点应胜出");
        assert_eq!(pool.current, Some(1));
    }

    #[test]
    fn select_matches_complexity() {
        // 端侧 + 云端同池：Simple 偏好端侧（低成本），Hard 偏好云端（大模型）。
        let local = EndpointProfile::new("local", Backend::Jni);
        let cloud = EndpointProfile::new("https://c.example.com/v1", Backend::Cloud);
        let mut pool = EndpointPool::new(vec![local, cloud]);
        let h = RouteHints {
            cloud_ok: true,
            network_ok: true,
            local_ok: true,
            ..Default::default()
        };
        assert_eq!(pool.select(&h, Complexity::Simple), Some(0));
        assert_eq!(pool.select(&h, Complexity::Hard), Some(1));
    }

    #[test]
    fn select_preferred_overrides_health_and_normalizes_id() {
        let mut a = EndpointProfile::new("https://a.example.com/v1", Backend::Cloud);
        a.health.note_success();
        let mut b = EndpointProfile::new("https://b.example.com/v1", Backend::Cloud);
        b.health.note_failure();
        b.health.note_failure();
        let mut pool = EndpointPool::new(vec![a, b]);
        // 健康度更低的 b 被显式选中（用户强制）；id 允许未归一化输入。
        let idx = pool
            .select_preferred(Some("HTTPS://B.EXAMPLE.COM/v1/"), &hints(true, true), Complexity::Medium)
            .unwrap();
        assert_eq!(idx, 1);
        assert_eq!(pool.current, Some(1));
        // 未知 preferred → 回退质量感知选路（a 更健康）。
        assert_eq!(
            pool.select_preferred(Some("https://unknown.example.com"), &hints(true, true), Complexity::Medium),
            Some(0)
        );
        // preferred 不可用（断网）且云端点均不可用 → None。
        assert_eq!(
            pool.select_preferred(Some("https://b.example.com/v1"), &hints(true, false), Complexity::Medium),
            None
        );
    }

    #[test]
    fn select_filters_unusable_and_failed() {
        // 断网时云端不可选。
        let cloud = EndpointProfile::new("https://c.example.com/v1", Backend::Cloud);
        let mut pool = EndpointPool::new(vec![cloud]);
        assert_eq!(pool.select(&hints(true, false), Complexity::Medium), None);
        // health 塌陷的端点不选。
        let mut bad = EndpointProfile::new("https://b.example.com/v1", Backend::Cloud);
        for _ in 0..20 {
            bad.health.note_failure();
        }
        let mut pool = EndpointPool::new(vec![bad]);
        assert_eq!(pool.select(&hints(true, true), Complexity::Medium), None);
    }

    #[test]
    fn switch_away_from_fails_over_and_demotes() {
        let a = EndpointProfile::new("https://a.example.com/v1", Backend::Cloud);
        let b = EndpointProfile::new("https://b.example.com/v1", Backend::Cloud);
        let mut pool = EndpointPool::new(vec![a, b]);
        let before = pool.endpoints[0].health.score;
        let idx = pool
            .switch_away_from("https://a.example.com/v1", &hints(true, true), Complexity::Medium)
            .expect("应有替代端点");
        assert_eq!(idx, 1, "应切换到另一个端点");
        assert!(pool.endpoints[0].health.score < before, "失败端点应降权");
        // 无替代时返回 None（不静默）。
        let only = EndpointProfile::new("https://a.example.com/v1", Backend::Cloud);
        let mut pool = EndpointPool::new(vec![only]);
        assert_eq!(
            pool.switch_away_from("https://a.example.com/v1", &hints(true, true), Complexity::Medium),
            None
        );
    }

    #[test]
    fn pool_snapshot_roundtrip_with_health() {
        let mut a = EndpointProfile::new("https://a.example.com/v1", Backend::Cloud);
        a.health.note_failure();
        let b = EndpointProfile::new("https://b.example.com/v1", Backend::Cloud);
        let mut pool = EndpointPool::new(vec![a, b]);
        pool.select(&hints(true, true), Complexity::Medium);
        let snap = pool.snapshot();
        let back = EndpointPool::from_snapshot(&snap).expect("池快照应可往返");
        assert_eq!(back, pool);
        assert_eq!(back.current, pool.current);
        assert_eq!(back.endpoints[0].health.failures, 1);
    }

    #[test]
    fn endpoint_id_uses_normalization() {
        let e = EndpointProfile::new("HTTPS://API.Example.COM/v1/chat?token=x", Backend::Cloud);
        assert_eq!(e.id, "https://api.example.com/v1/chat");
    }
}
