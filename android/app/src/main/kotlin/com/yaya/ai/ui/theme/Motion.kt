package com.yaya.ai.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring

/** 动效 token（motion-web 时长尺度映射 Compose；只动 transform/opacity，保手机流畅）。 */
object Motion {
    // 时长（ms）：instant 语义并入 Fast
    const val Fast = 150
    const val Standard = 280
    const val Medium = 450
    const val Slow = 650

    // 缓动
    val StandardEasing = FastOutSlowInEasing
    val RevealEasing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f) // ease-out-expo，消息 reveal

    // spring（按钮按压反馈）
    val PressSpring = spring<Float>(dampingRatio = 0.7f, stiffness = 400f)

    // 列表 stagger（ms / 条）
    const val StaggerStep = 60
}
