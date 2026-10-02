//! 三层模型路由策略（AGENTS.md R2 的可执行规范）。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Backend {
    Jni,
    Desktop,
    Cloud,
    Error,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Complexity {
    Simple,
    Medium,
    Hard,
}

/// 路由输入信号。探测类信号（desktop_ok / local_ok / network_ok）由调用方负责
/// 执行与缓存，本模块只做纯函数决策。
#[derive(Debug, Clone, Copy)]
pub struct RouteHints {
    /// 用户强制指定的后端；None = auto
    pub force: Option<Backend>,
    pub cloud_ok: bool,
    pub desktop_ok: bool,
    /// 端侧真实可推理（非 STUB 且 JNI 库已加载）
    pub local_ok: bool,
    pub network_ok: bool,
    pub latency_sensitive: bool,
}

impl Default for RouteHints {
    fn default() -> Self {
        RouteHints {
            force: None,
            cloud_ok: false,
            desktop_ok: false,
            local_ok: false,
            network_ok: false,
            latency_sensitive: false,
        }
    }
}

pub fn complexity(prompt: &str) -> Complexity {
    if prompt.contains("```") || prompt.chars().count() > 1024 {
        Complexity::Hard
    } else if prompt.chars().count() <= 256 {
        Complexity::Simple
    } else {
        Complexity::Medium
    }
}

/// AGENTS.md R2 策略表，逐条对应。
pub fn route(prompt: &str, h: &RouteHints) -> Result<Backend, String> {
    if let Some(b) = h.force {
        if b != Backend::Error {
            return Ok(b);
        }
    }
    let budget = complexity(prompt);

    // 2. 简单提示词 && 端侧非 STUB && 非低延迟 → 端侧
    if h.local_ok && !h.latency_sensitive && budget == Complexity::Simple {
        return Ok(Backend::Jni);
    }
    // 3. 桌面可达 && 中/高复杂度 → 桌面
    if h.desktop_ok && budget != Complexity::Simple {
        return Ok(Backend::Desktop);
    }
    // 4. 桌面可达 && 端侧 STUB → 桌面
    if h.desktop_ok && !h.local_ok {
        return Ok(Backend::Desktop);
    }
    // 5. 云端已配置 && 有网络 → 云端
    if h.cloud_ok && h.network_ok {
        return Ok(Backend::Cloud);
    }
    // 6. 端侧兜底
    if h.local_ok {
        return Ok(Backend::Jni);
    }
    // 7. 明确错误，禁止静默假数据
    Err(format!(
        "无可用后端：本地={}，桌面={}，云端已配置={}，网络={}",
        if h.local_ok { "可用" } else { "STUB/不可用" },
        if h.desktop_ok { "可达" } else { "不可达" },
        h.cloud_ok,
        h.network_ok
    ))
}

/// 策略表的机器可读描述，暴露在桌面 /debug 端点（R2 运行时可核对）。
pub const ROUTER_SPEC: &str = concat!(
    "force!=auto -> force; ",
    "simple&&local&&!latency -> jni; ",
    "desktop&&!(simple) -> desktop; ",
    "desktop&&!local -> desktop; ",
    "cloud&&net -> cloud; ",
    "local -> jni; ",
    "else -> error(显式原因)"
);

#[cfg(test)]
mod tests {
    use super::*;

    fn h(f: Option<Backend>, cloud: bool, desk: bool, local: bool, net: bool) -> RouteHints {
        RouteHints {
            force: f,
            cloud_ok: cloud,
            desktop_ok: desk,
            local_ok: local,
            network_ok: net,
            latency_sensitive: false,
        }
    }

    const SIMPLE: &str = "你好";
    const MEDIUM: &str = "请解释一下下面这段自然语言处理流程的原理和工程取舍，大概三百字左右的内容就好";
    const HARD: &str = "```rust\nfn main() {}\n```";

    #[test]
    fn complexity_boundaries() {
        assert_eq!(complexity(SIMPLE), Complexity::Simple);
        assert_eq!(complexity(MEDIUM), Complexity::Medium);
        assert_eq!(complexity(HARD), Complexity::Hard);
        assert_eq!(complexity(&"x".repeat(1025)), Complexity::Hard);
    }

    #[test]
    fn rule1_force_wins() {
        let hints = h(Some(Backend::Cloud), true, false, true, false);
        assert_eq!(route(SIMPLE, &hints), Ok(Backend::Cloud));
    }

    #[test]
    fn rule2_simple_goes_local() {
        assert_eq!(
            route(SIMPLE, &h(None, true, true, true, true)),
            Ok(Backend::Jni)
        );
    }

    #[test]
    fn rule3_medium_hard_goes_desktop() {
        assert_eq!(
            route(MEDIUM, &h(None, false, true, true, true)),
            Ok(Backend::Desktop)
        );
        assert_eq!(
            route(HARD, &h(None, false, true, false, true)),
            Ok(Backend::Desktop)
        );
    }

    #[test]
    fn rule4_local_stub_prefers_desktop_even_for_simple() {
        assert_eq!(
            route(SIMPLE, &h(None, false, true, false, true)),
            Ok(Backend::Desktop)
        );
    }

    #[test]
    fn rule5_cloud_fallback_when_desktop_down() {
        assert_eq!(
            route(MEDIUM, &h(None, true, false, false, true)),
            Ok(Backend::Cloud)
        );
    }

    #[test]
    fn rule5_skipped_without_network() {
        // 云端已配置但断网，且本地/桌面均不可用 → 必须显式错误（含网络原因）
        match route(MEDIUM, &h(None, true, false, false, false)) {
            Ok(b) => panic!("断网且无本地/桌面时必须报错，实际 {:?}", b),
            Err(e) => assert!(e.contains("网络=false"), "错误信息需含网络原因: {}", e),
        }
    }

    #[test]
    fn rule6_local_fallback() {
        // 桌面不可达、云端未配置：端侧兜底
        assert_eq!(
            route(MEDIUM, &h(None, false, false, true, false)),
            Ok(Backend::Jni)
        );
    }

    #[test]
    fn rule7_all_down_is_explicit_error() {
        let hints = h(None, false, false, false, false);
        match route(SIMPLE, &hints) {
            Ok(b) => panic!("全断时必须报错，实际 {:?}", b),
            Err(e) => assert!(e.contains("无可用后端"), "错误信息需含原因: {}", e),
        }
    }
}
