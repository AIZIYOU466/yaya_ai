/**
 * Model Router：三层模型路由策略（同构于 core/src/router.rs，见 AGENTS.md R2/R6）。
 *
 * 策略优先级（route() 逐条对应）：
 *   1. 用户强制指定（force != auto） → 直接用指定后端
 *   2. 简单提示词 && 端侧非 STUB && 非低延迟 → JNI（端侧 2B-4B）
 *   3. 桌面可达 && 中/高复杂度 → 桌面 gRPC（7B-70B）
 *   4. 桌面可达 && 端侧 STUB → 桌面 gRPC
 *   5. 云端已配置 && 有网络 → 云端
 *   6. 端侧非 STUB → JNI 兜底
 *   7. 全部不可用 → 返回明确错误（禁止静默假数据、静默空回复）
 *
 * 复杂度判定：含 ``` 代码块或长度 > 1024 → Hard；长度 ≤ 256 → Simple；其余 Medium。
 */
package com.yaya.ai

enum class Backend { Jni, Desktop, Cloud, Error }

enum class Complexity { Simple, Medium, Hard }

/**
 * 路由输入信号。探测类信号（desktopOk / localOk / networkOk）由调用方负责
 * 执行与缓存，本类只做纯函数决策。
 */
data class RouteHints(
    val force: Backend? = null,       // null = auto
    val cloudOk: Boolean = false,
    val desktopOk: Boolean = false,
    val localOk: Boolean = false,     // 端侧非 STUB 且 JNI 库已加载
    val networkOk: Boolean = false,
    val latencySensitive: Boolean = false,
)

object ModelRouter {

    /** 复杂度判定（与 core/src/router.rs::complexity 一致） */
    fun complexity(prompt: String): Complexity {
        if (prompt.contains("```") || prompt.length > 1024) return Complexity.Hard
        if (prompt.length <= 256) return Complexity.Simple
        return Complexity.Medium
    }

    /** AGENTS.md R2 策略表，逐条对应（与 core/src/router.rs::route 一致） */
    fun route(prompt: String, hints: RouteHints): Backend {
        // 1. 用户强制指定
        if (hints.force != null && hints.force != Backend.Error) return hints.force

        val budget = complexity(prompt)

        // 2. 简单提示词 && 端侧非 STUB && 非低延迟 → JNI
        if (hints.localOk && !hints.latencySensitive && budget == Complexity.Simple) {
            return Backend.Jni
        }
        // 3. 桌面可达 && 中/高复杂度 → 桌面
        if (hints.desktopOk && budget != Complexity.Simple) return Backend.Desktop
        // 4. 桌面可达 && 端侧 STUB → 桌面
        if (hints.desktopOk && !hints.localOk) return Backend.Desktop
        // 5. 云端已配置 && 有网络 → 云端
        if (hints.cloudOk && hints.networkOk) return Backend.Cloud
        // 6. 端侧兜底
        if (hints.localOk) return Backend.Jni
        // 7. 明确错误，禁止静默假数据
        throw IllegalStateException(
            "无可用后端：本地=${if (hints.localOk) "可用" else "STUB/不可用"}，" +
                "桌面=${if (hints.desktopOk) "可达" else "不可达"}，" +
                "云端已配置=${hints.cloudOk}，网络=${hints.networkOk}"
        )
    }
}
