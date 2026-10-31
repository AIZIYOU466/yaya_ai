package com.yaya.ai

import java.util.Locale

/**
 * 端侧模型文件（`.gguf`）的纯 JVM 规则（ROADMAP 任务 31）。
 *
 * 只做命名、扩展名校验与体积展示，**不触碰 Android API** —— 与 [LocalModels]（Context /
 * ContentResolver 侧）分离，便于在容器内单测（R16 每步验证）。
 */
object LocalModelFiles {
    const val EXTENSION = ".gguf"

    /** 文件名主干上限：NAME_MAX 255 字节，留出唯一后缀与扩展名空间。 */
    const val MAX_STEM_LENGTH = 96

    /** 文件系统非法字符（含路径分隔符，防目录穿越）。 */
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun hasModelExtension(name: String): Boolean =
        name.trim().lowercase(Locale.ROOT).endsWith(EXTENSION)

    /**
     * 把 SAF 显示名清洗成安全文件名：
     * 取末段基名 → 去掉非法/控制字符 → 必须仍以 `.gguf` 结尾 → 主干截断到 [MAX_STEM_LENGTH]。
     * 不可用时返回 `null`（调用方显式报错，R15 不静默）。
     */
    fun sanitizeName(raw: String): String? {
        val base = raw.trim().substringAfterLast('/').substringAfterLast('\\')
        if (base.isEmpty()) return null
        val filtered = base
            .filterNot { it in ILLEGAL || it.isISOControl() }
            .trim()
            .trim('.')
        if (filtered.isEmpty()) return null
        if (!hasModelExtension(filtered)) return null
        val stem = filtered.dropLast(EXTENSION.length).trim().trim('.')
        if (stem.isEmpty()) return null
        return stem.take(MAX_STEM_LENGTH) + EXTENSION
    }

    /** 目录内重名时追加 `-1` / `-2`…（大小写不敏感比较，避免与 FAT/大小写无感实现冲突）。 */
    fun uniqueName(desired: String, existing: Collection<String>): String {
        val taken = existing.map { it.lowercase(Locale.ROOT) }.toSet()
        if (desired.lowercase(Locale.ROOT) !in taken) return desired
        val stem = desired.dropLast(EXTENSION.length)
        for (i in 1..999) {
            val candidate = "$stem-$i$EXTENSION"
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        }
        return "$stem-${System.currentTimeMillis()}$EXTENSION"
    }

    /** 人类可读体积（固定 US 小数格式，避免本地化差异）。 */
    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "未知"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < mb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            bytes < gb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            else -> String.format(Locale.US, "%.2f GB", bytes / gb)
        }
    }
}
