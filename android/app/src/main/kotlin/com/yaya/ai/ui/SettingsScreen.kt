package com.yaya.ai.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 旧 Dart shared_preferences 插件在 Android 存于 `FlutterSharedPreferences` 文件，
 * 键带 `flutter.` 前缀。设置页读写同一文件/前缀以兼容老用户配置（迁移不丢数据）。
 */
private fun Context.legacyPrefs() =
    getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

private const val KEY_BASE_URL = "flutter.ai_base_url"
private const val KEY_API_KEY = "flutter.ai_api_key"
private const val KEY_MODEL_NAME = "flutter.ai_model_name"
private const val KEY_MODEL_PATH = "flutter.ai_model_path"
private const val KEY_IS_STREAM = "flutter.ai_is_stream"
private const val KEY_MODE = "flutter.agent_mode"
private const val KEY_LANG = "flutter.app_language"

/** 设置页（P0：AI 配置 + 运行模式 + 语言）。后续页迁移时扩展。 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val api = LocalAgentApi.current
    val prefs = remember { context.legacyPrefs() }

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var modelPath by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("build") }
    var lang by remember { mutableStateOf("zh") }
    var saved by remember { mutableStateOf(false) }
    var localModelReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        baseUrl = prefs.getString(KEY_BASE_URL, "") ?: ""
        apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        modelName = prefs.getString(KEY_MODEL_NAME, "") ?: ""
        modelPath = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        mode = prefs.getString(KEY_MODE, "build") ?: "build"
        lang = prefs.getString(KEY_LANG, "zh") ?: "zh"
        localModelReady = api?.localAvailable() == true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AI 配置", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.openai.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelName,
            onValueChange = { modelName = it },
            label = { Text("模型名") },
            placeholder = { Text("gpt-4o-mini") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            label = { Text("端侧模型路径（.gguf，可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (localModelReady) "端侧模型可用" else "端侧模型 STUB（未启用）",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                prefs.edit()
                    .putString(KEY_BASE_URL, baseUrl.trim())
                    .putString(KEY_API_KEY, apiKey.trim())
                    .putString(KEY_MODEL_NAME, modelName.trim())
                    .putString(KEY_MODEL_PATH, modelPath.trim())
                    .apply()
                saved = true
            },
        ) { Text("保存") }
        if (saved) Text("已保存", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()

        Text("运行模式", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == "build",
                onClick = { mode = "build"; prefs.edit().putString(KEY_MODE, "build").apply() },
                label = { Text("BUILD") },
            )
            FilterChip(
                selected = mode == "plan",
                onClick = { mode = "plan"; prefs.edit().putString(KEY_MODE, "plan").apply() },
                label = { Text("PLAN") },
            )
            FilterChip(
                selected = mode == "auto",
                onClick = { mode = "auto"; prefs.edit().putString(KEY_MODE, "auto").apply() },
                label = { Text("AUTO") },
            )
        }

        HorizontalDivider()

        Text("语言", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = lang == "zh",
                onClick = { lang = "zh"; prefs.edit().putString(KEY_LANG, "zh").apply() },
                label = { Text("中文") },
            )
            FilterChip(
                selected = lang == "en",
                onClick = { lang = "en"; prefs.edit().putString(KEY_LANG, "en").apply() },
                label = { Text("English") },
            )
        }
    }
}