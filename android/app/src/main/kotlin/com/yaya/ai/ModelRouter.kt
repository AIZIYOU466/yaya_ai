/**
 * Model Router：三层模型路由策略（同构于 core/src/router.rs 的 AGENTS.md R2）。
 *
 * 策略优先级（由 route() 方法实现，见下文注释对应 R2 各条规则）：
 *   1. 用户强制指定（force != auto） → 直接使用
 *   2. 简单提示词 && 端侧非 STUB && 非低延迟 → JNI（端侧 2B-4B）
 *   3. 桌面可达 && 中/高复杂度 → 桌面 gRPC（7B-70B）
 *   4. 桌面可达 && 端侧 STUB → 桌面 gRPC
 *   5. 云端已配置 && 有网络 → 云端
 *   6. 端侧兜底 → JNI
 *   7. 全部不可用 → 返回明确错误（**禁止静默假数据、空回复**）
 *
 * Complexity 判定：
 *   - Hard：含 ``` ``` 代码块 或长度 > 1024
 *   - Simple：长度 <= 256
 *   - Medium：其余
 */

package com.yaya.ai.core.model.router

import com.yaya.ai.core.model.router.Backend
import com.yaya.ai.core.model.router.Complexity

/** 路由输入探测信号，由调用方通过 ModelRouter.kt 外部探测后填充。 */
private data class RouteHints(
    val force: Backend?,        // None = auto
    val cloudOk: Boolean,
    val desktopOk: Boolean,
    val localOk: Boolean,       // 端侧非 STUB 且 JNI 库已加载
    val networkOk: Boolean,
    val latencySensitive: Boolean,
)

/** 复杂度判定 */
private fun complexity(prompt: String): Complexity {
    if (prompt.contains("```") || prompt.length > 1024) return Complexity.Hard
    if (prompt.length <= 256) return Complexity.Simple
    return Complexity.Medium
}

/** AGENTS.md R2 策略表，逐条对应 */
private fun route(
    prompt: String,
    hints: RouteHints,
): Backend {
    // 1. 用户强制指定
    if (hints?.force != null && hints.force != Backend.Error) return hints.force

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

/** 通过 RouteHints.Default() 构建默认探测信号。 */
val RouteHints.default : RouteHints
    get() = RouteHints(
        force = null,
        cloudOk = false,
        desktopOk = false,
        localOk = false,
        networkOk = false,
        latencySensitive = false,
    )

/** 供桌面 gRPC 客户端或 Android UI 层调用的公共入口。 */
fun routeModel(prompt: String, hints: RouteHints = RouteHints.default): Backend = route(prompt, hints)