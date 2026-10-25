package com.yaya.ai.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 模型能力标签（任务 25）：随 config.capabilities 下发，contextTokens=0 表示未标注。 */
data class ModelCaps(
    val tools: Boolean = true,
    val vision: Boolean = true,
    val contextTokens: Int = 0,
    /** 任务 30：费用估算单价（美元 / 百万 tokens）；0 表示未配置。 */
    val inputCostPerM: Double = 0.0,
    val outputCostPerM: Double = 0.0,
) {
    fun contextLabel(): String? = when {
        contextTokens <= 0 -> null
        contextTokens >= 1_000_000 -> "${contextTokens / 1_000_000}M"
        contextTokens >= 1000 -> "${contextTokens / 1000}K"
        else -> contextTokens.toString()
    }

    fun hasPricing(): Boolean = inputCostPerM > 0 || outputCostPerM > 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("tools", tools)
        put("vision", vision)
        put("contextTokens", contextTokens)
        put("inputCostPerM", inputCostPerM)
        put("outputCostPerM", outputCostPerM)
    }

    companion object {
        fun fromJson(o: JSONObject): ModelCaps = ModelCaps(
            tools = o.optBoolean("tools", true),
            vision = o.optBoolean("vision", true),
            contextTokens = o.optInt("contextTokens", 0).coerceAtLeast(0),
            inputCostPerM = o.optDouble("inputCostPerM", 0.0).coerceAtLeast(0.0),
            outputCostPerM = o.optDouble("outputCostPerM", 0.0).coerceAtLeast(0.0),
        )
    }
}

/** 单个模型：id + 能力标签。 */
data class ProviderModel(
    val id: String,
    val caps: ModelCaps = ModelCaps(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("tools", caps.tools)
        put("vision", caps.vision)
        put("contextTokens", caps.contextTokens)
        put("inputCostPerM", caps.inputCostPerM)
        put("outputCostPerM", caps.outputCostPerM)
    }

    companion object {
        fun fromJson(o: JSONObject): ProviderModel = ProviderModel(
            id = o.optString("id"),
            caps = ModelCaps.fromJson(o),
        )
    }
}

/** 模型提供商（任务 25）：协议类型 + 凭据 + 模型列表。 */
data class ModelProvider(
    val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<ProviderModel>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("models", JSONArray().apply { models.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): ModelProvider {
            val arr = o.optJSONArray("models") ?: JSONArray()
            val models = (0 until arr.length()).mapNotNull { i ->
                when (val item = arr.opt(i)) {
                    is JSONObject -> ProviderModel.fromJson(item).takeIf { it.id.isNotBlank() }
                    is String -> item.takeIf { it.isNotBlank() }?.let { ProviderModel(it) }
                    else -> null
                }
            }
            return ModelProvider(
                id = o.optString("id").ifBlank { newId() },
                name = o.optString("name"),
                type = normalizeType(o.optString("type")),
                baseUrl = o.optString("baseUrl"),
                apiKey = o.optString("apiKey"),
                models = models.distinctBy { it.id },
            )
        }

        fun newId(): String = "p-${UUID.randomUUID().toString().take(8)}"
    }
}

/** 当前选中（提供商 + 模型）。 */
data class ActiveModel(val provider: ModelProvider, val model: ProviderModel)

val PROVIDER_TYPES = listOf("openai" to "OpenAI", "anthropic" to "Anthropic", "gemini" to "Gemini")

fun normalizeType(type: String): String =
    if (type in PROVIDER_TYPES.map { it.first }) type else "openai"

fun providerTypeLabel(type: String): String =
    PROVIDER_TYPES.firstOrNull { it.first == type }?.second ?: "OpenAI"

fun defaultBaseUrl(type: String): String = when (normalizeType(type)) {
    "anthropic" -> "https://api.anthropic.com"
    "gemini" -> "https://generativelanguage.googleapis.com"
    else -> "https://api.openai.com/v1"
}

/**
 * 提供商配置持久化（任务 25）。
 *
 * 存在旧 Dart SharedPreferences 文件（`FlutterSharedPreferences`）的 `flutter.*` 键空间：
 * 首次加载若无 `ai_providers`，用旧单端点配置合成一个 OpenAI 提供商（迁移不丢配置）；
 * 选中模型时同步写回 `ai_base_url` / `ai_api_key` / `ai_model_name`，兼容旧读取路径。
 */
object ProviderStore {
    private const val PREFS = "FlutterSharedPreferences"
    private const val KEY_PROVIDERS = "flutter.ai_providers"
    private const val KEY_ACTIVE_PROVIDER = "flutter.ai_active_provider"
    private const val KEY_ACTIVE_MODEL = "flutter.ai_active_model"

    // 旧单端点键（SettingsScreen / ChatScreen / 迁移读取）
    const val KEY_BASE_URL = "flutter.ai_base_url"
    const val KEY_API_KEY = "flutter.ai_api_key"
    const val KEY_MODEL_NAME = "flutter.ai_model_name"
    const val KEY_MODEL_PATH = "flutter.ai_model_path"
    const val KEY_IS_STREAM = "flutter.ai_is_stream"
    const val KEY_MODE = "flutter.agent_mode"
    const val KEY_LANG = "flutter.app_language"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<ModelProvider> {
        val p = prefs(context)
        val raw = p.getString(KEY_PROVIDERS, null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    (arr.opt(i) as? JSONObject)?.let { o ->
                        runCatching { ModelProvider.fromJson(o) }.getOrNull()
                    }
                }.distinctBy { it.id }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return legacyProvider(p)
    }

    private fun legacyProvider(p: android.content.SharedPreferences): List<ModelProvider> {
        val base = p.getString(KEY_BASE_URL, "")?.trim().orEmpty()
        val key = p.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val model = p.getString(KEY_MODEL_NAME, "")?.trim().orEmpty()
        if (base.isEmpty() && model.isEmpty()) return emptyList()
        return listOf(
            ModelProvider(
                id = "legacy",
                name = hostLabel(base),
                type = "openai",
                baseUrl = base,
                apiKey = key,
                models = if (model.isEmpty()) emptyList() else listOf(ProviderModel(model)),
            )
        )
    }

    private fun hostLabel(baseUrl: String): String {
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
        return host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "默认提供商"
    }

    fun save(context: Context, providers: List<ModelProvider>) {
        ProviderStore.prefs(context).edit()
            .putString(KEY_PROVIDERS, JSONArray().apply { providers.forEach { put(it.toJson()) } }.toString())
            .apply()
    }

    /** 当前选中；无有效记录时回退第一个可用模型（不写盘）。 */
    fun active(context: Context, providers: List<ModelProvider> = load(context)): ActiveModel? {
        val p = prefs(context)
        val pid = p.getString(KEY_ACTIVE_PROVIDER, null)
        val mid = p.getString(KEY_ACTIVE_MODEL, null)
        val byId = providers.firstOrNull { it.id == pid }
        val model = byId?.models?.firstOrNull { it.id == mid }
        if (byId != null && model != null) return ActiveModel(byId, model)
        val fallback = providers.firstOrNull { it.models.isNotEmpty() } ?: return null
        return ActiveModel(fallback, fallback.models.first())
    }

    /** 切换选中并同步旧单端点键（供 config 兼容路径读取）。 */
    fun setActive(context: Context, provider: ModelProvider, model: ProviderModel) {
        prefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, provider.id)
            .putString(KEY_ACTIVE_MODEL, model.id)
            .putString(KEY_BASE_URL, provider.baseUrl.trim())
            .putString(KEY_API_KEY, provider.apiKey)
            .putString(KEY_MODEL_NAME, model.id)
            .apply()
    }

    /** 删除/重命名后清理失效选中。 */
    fun clearActiveIfMissing(context: Context, providers: List<ModelProvider>) {
        val p = prefs(context)
        val pid = p.getString(KEY_ACTIVE_PROVIDER, null) ?: return
        val provider = providers.firstOrNull { it.id == pid } ?: run {
            p.edit().remove(KEY_ACTIVE_PROVIDER).remove(KEY_ACTIVE_MODEL).apply()
            return
        }
        val mid = p.getString(KEY_ACTIVE_MODEL, null)
        if (provider.models.none { it.id == mid }) {
            p.edit().remove(KEY_ACTIVE_MODEL).apply()
        }
    }

    /** 拉取结果统一信封：`{"ok":true,"models":[...]}` / `{"ok":false,"message":"..."}`。 */
    fun parseModelList(raw: String): Pair<List<String>, String?> = try {
        val o = JSONObject(raw)
        val ok = o.optBoolean("ok", false)
        if (ok) {
            val arr = o.optJSONArray("models") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } } to null
        } else {
            emptyList<String>() to o.optString("message").ifBlank { "拉取失败" }
        }
    } catch (_: Exception) {
        emptyList<String>() to "拉取响应解析失败"
    }
}
