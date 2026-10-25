package com.yaya.ai.ui

import java.util.Locale

/** 任务 30：用量与耗时的统一展示格式。 */
fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 10_000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
    else -> n.toString()
}

fun formatDuration(ms: Long): String = when {
    ms <= 0 -> "—"
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

fun formatCost(usd: Double): String = when {
    usd <= 0 -> "—"
    usd < 0.01 -> String.format(Locale.US, "%.4f", usd)
    else -> String.format(Locale.US, "%.3f", usd)
}

fun formatPrice(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
