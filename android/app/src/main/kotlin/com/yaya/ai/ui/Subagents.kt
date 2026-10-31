package com.yaya.ai.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 子代理定义（任务 32）。
 *
 * `tools` 为工具白名单（空 = 继承父级全部工具；只收窄不放宽）；`maxSteps = 0` 表示默认 5。
 * 内置 `explore` 只读代理由 core 固定白名单，此处只允许启停（不可改工具/指令）。
 */
data class SubagentConfig(
    val name: String,
    val description: String = "",
    val prompt: String = "",
    val model: String = "",
    val tools: List<String> = emptyList(),
    val enabled: Boolean = true,
    val maxSteps: Int = 0,
    val builtin: Boolean = false,
) {
    /** 下发给 core（`config.subagents`，字段名与 `SubagentDef` 的 camelCase 一致）。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        if (description.isNotBlank()) put("description", description)
        if (prompt.isNotBlank()) put("prompt", prompt)
        if (model.isNotBlank()) put("model", model)
        if (tools.isNotEmpty()) put("tools", JSONArray().apply { tools.forEach { put(it) } })
        put("enabled", enabled)
        if (maxSteps > 0) put("maxSteps", maxSteps)
    }

    companion object {
        fun fromJson(o: JSONObject): SubagentConfig {
            val tools = o.optJSONArray("tools")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }
                }
            } ?: emptyList()
            return SubagentConfig(
                name = o.optString("name").trim(),
                description = o.optString("description"),
                prompt = o.optString("prompt"),
                model = o.optString("model"),
                tools = tools,
                enabled = o.optBoolean("enabled", true),
                maxSteps = o.optInt("maxSteps", 0).coerceAtLeast(0),
                builtin = o.optBoolean("builtin", false),
            )
        }
    }
}

/**
 * 子代理配置持久化（SharedPreferences `FlutterSharedPreferences`，与提供商配置同键空间）。
 *
 * 内置 `explore` 始终出现在列表中（用户可停用）；自定义代理同名覆盖内置。
 */
object SubagentStore {
    private const val KEY_DEFS = "flutter.ai_subagents"
    private const val KEY_PARALLEL = "flutter.ai_max_parallel"

    const val EXPLORE = "explore"
    const val GENERAL = "general"
    const val DEFAULT_PARALLEL = 3
    /** UI 上限；core 硬上限为 8（`subagent::MAX_PARALLEL_LIMIT`）。 */
    const val MAX_PARALLEL = 5

    fun explore(enabled: Boolean = true): SubagentConfig = SubagentConfig(
        name = EXPLORE,
        description = "只读探索代理：仅可列举/读取工作区文件与记忆",
        enabled = enabled,
        builtin = true,
    )

    /** 读取全部定义：内置 explore 置顶，自定义按存储顺序。 */
    fun load(context: Context): List<SubagentConfig> {
        val raw = ProviderStore.prefs(context).getString(KEY_DEFS, null)
        val parsed = mutableListOf<SubagentConfig>()
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val def = runCatching { SubagentConfig.fromJson(o) }.getOrNull() ?: continue
                    if (def.name.isBlank() || def.name == GENERAL) continue
                    parsed += def
                }
            }
        }
        val storedExplore = parsed.firstOrNull { it.name == EXPLORE }
        val explore = explore(storedExplore?.enabled ?: true)
        val custom = parsed.filter { it.name != EXPLORE }.distinctBy { it.name }
        return listOf(explore) + custom
    }

    fun save(context: Context, defs: List<SubagentConfig>) {
        val arr = JSONArray()
        defs.forEach { arr.put(it.toJson()) }
        ProviderStore.prefs(context).edit().putString(KEY_DEFS, arr.toString()).apply()
    }

    /** 同轮并行子代理上限（1..MAX_PARALLEL）。 */
    fun maxParallel(context: Context): Int =
        ProviderStore.prefs(context).getInt(KEY_PARALLEL, DEFAULT_PARALLEL).coerceIn(1, MAX_PARALLEL)

    fun setMaxParallel(context: Context, value: Int) {
        ProviderStore.prefs(context).edit()
            .putInt(KEY_PARALLEL, value.coerceIn(1, MAX_PARALLEL))
            .apply()
    }
}
