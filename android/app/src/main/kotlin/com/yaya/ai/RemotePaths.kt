package com.yaya.ai

/**
 * 远端工作区路径安全（ROADMAP 任务 35）：纯字符串运算，与 Android API 无关，可单测。
 *
 * 规则与本地工作区（R19）一致：工具只传**相对路径**；`..` 只能在工作区根内回退，
 * 归一化后必须仍以远端工作区根为前缀，否则视为越界。
 */
object RemotePaths {

    /** 归一化绝对路径：折叠 `.` / `..` / 重复斜杠；越出根（`..` 过多）返回 null。 */
    fun normalize(path: String): String? {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/")) return null
        if (trimmed.contains('\u0000')) return null
        val out = ArrayList<String>()
        for (seg in trimmed.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isEmpty()) return null else out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    /**
     * 相对路径 → 远端绝对路径。
     * 越界（工作区外）、非法（NUL）或根本身不是绝对路径时返回 null。
     */
    fun resolve(root: String, rel: String): String? {
        val base = normalize(root)?.trimEnd('/')?.ifEmpty { "/" } ?: return null
        val raw = rel.trim()
        if (raw.contains('\u0000')) return null
        val target = normalize("$base/$raw") ?: return null
        return if (base == "/" || target == base || target.startsWith("$base/")) target else null
    }
}
