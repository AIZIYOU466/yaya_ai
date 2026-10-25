package com.yaya.ai.ui.theme

import androidx.compose.ui.graphics.Color

/** 色板 v3（白蓝亮色）：浅灰白底 + 可靠蓝强调，清新有活力。 */
object YayaColors {
    // 背景与表面（三级，浅色）
    val Background = Color(0xFFF6F7FB)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFEEF2F8)
    val SurfaceHigh = Color(0xFFE2E8F2)

    // 强调（可靠蓝）
    val Primary = Color(0xFF2563EB)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryDim = Color(0xFF1D4ED8)
    val PrimaryContainer = Color(0xFFDBEAFE)
    val OnPrimaryContainer = Color(0xFF1E40AF)

    // 语义
    val Warning = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)

    // 描边与分割
    val Outline = Color(0xFFD7DEE9)
    val OutlineVariant = Color(0xFFE4E9F2)

    // 文字三级
    val TextPrimary = Color(0xFF0F172A)
    val TextSecondary = Color(0xFF64748B)
    val TextDisabled = Color(0xFF94A3B8)

    // 节点状态（灰 / 黄 / 绿 / 红）
    val StatusPending = Color(0xFF94A3B8)
    val StatusRunning = Color(0xFFF59E0B)
    val StatusSuccess = Color(0xFF10B981)
    val StatusError = Color(0xFFEF4444)

    // 氛围辉光（聊天页顶部，浅蓝）
    val AmbientGlow = Color(0x2A2563EB) // primary 16% alpha
}
