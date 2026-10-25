package com.yaya.ai.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.yaya.ai.AgentApi
import com.yaya.ai.ui.components.CapsTags
import com.yaya.ai.ui.components.Tag
import com.yaya.ai.ui.components.YayaCard
import com.yaya.ai.ui.theme.MonoLabel
import com.yaya.ai.ui.theme.MonoSmall
import com.yaya.ai.ui.theme.YayaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设置页：多模型提供商（任务 25）+ 端侧模型 + 运行模式 + 语言。
 *
 * 提供商配置存 SharedPreferences（`flutter.ai_providers`），选中模型同步旧单端点键；
 * 对话页据此下发 `config.providers`（core 侧注册多 CloudBackend 实例，任务 25/42）。
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val api = LocalAgentApi.current
    val prefs = remember { ProviderStore.prefs(context) }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    var providers by remember { mutableStateOf(ProviderStore.load(context)) }
    var active by remember { mutableStateOf(ProviderStore.active(context, providers)) }
    var editing by remember { mutableStateOf<ModelProvider?>(null) }
    var deleteTarget by remember { mutableStateOf<ModelProvider?>(null) }
    var modelPath by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("build") }
    var lang by remember { mutableStateOf("zh") }
    var saved by remember { mutableStateOf(false) }
    var localModelReady by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }

    fun refreshActive() {
        active = ProviderStore.active(context, providers)
    }

    fun persist(list: List<ModelProvider>) {
        providers = list
        ProviderStore.save(context, list)
        ProviderStore.clearActiveIfMissing(context, list)
        refreshActive()
    }

    LaunchedEffect(Unit) {
        modelPath = prefs.getString(ProviderStore.KEY_MODEL_PATH, "") ?: ""
        mode = prefs.getString(ProviderStore.KEY_MODE, "build") ?: "build"
        lang = prefs.getString(ProviderStore.KEY_LANG, "zh") ?: "zh"
        localModelReady = api?.localAvailable() == true
        stats = runCatching { JSONObject(api?.getStats() ?: "{}") }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("模型提供商", style = MaterialTheme.typography.titleMedium)
        Text(
            "支持 OpenAI / Anthropic / Gemini 协议；对话页右上角可随时切换模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (providers.isEmpty()) {
            YayaCard(Modifier.fillMaxWidth()) {
                Text(
                    "尚未配置提供商。添加后可拉取模型列表或手动填写模型名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                active = active,
                onPickModel = { model ->
                    ProviderStore.setActive(context, provider, model)
                    refreshActive()
                    scope.launch { snackbar.showSnackbar("已切换到 ${model.id}") }
                },
                onEdit = { editing = provider },
                onDelete = { deleteTarget = provider },
            )
        }
        OutlinedButton(
            onClick = {
                editing = ModelProvider(
                    id = ModelProvider.newId(),
                    name = "",
                    type = "openai",
                    baseUrl = defaultBaseUrl("openai"),
                    apiKey = "",
                    models = emptyList(),
                )
            },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(" 添加提供商")
        }

        if (active != null) {
            Text("当前模型", style = MaterialTheme.typography.titleMedium)
            YayaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${active?.model?.id}",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${active?.provider?.name?.ifBlank { providerTypeLabel(active?.provider?.type ?: "") }} · ${providerTypeLabel(active?.provider?.type ?: "")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    active?.model?.let { CapsTags(it.caps) }
                }
            }
        }

        HorizontalDivider()

        // 任务 30：全局用量累计（会话/回复/工具/缓存/耗时/费用估算）。
        Text("用量统计", style = MaterialTheme.typography.titleMedium)
        stats?.let { s ->
            YayaCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "会话 ${s.optInt("sessions")} · 消息 ${s.optInt("messages")} · 工具 ${s.optInt("toolCalls")}",
                        style = MonoSmall,
                    )
                    Text(
                        "tokens ${s.optInt("totalTokens")}（输入 ${s.optInt("promptTokens")} / 输出 ${s.optInt("completionTokens")} / 缓存 ${s.optInt("cachedTokens")}）",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val cost = s.optDouble("totalCost", 0.0)
                    Text(
                        "累计耗时 ${formatDuration(s.optLong("durationMs"))} · " +
                            if (cost > 0) "费用约 \$${String.format("%.4f", cost)}" else "费用未配置单价",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } ?: Text(
            "暂无用数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()

        Text("端侧模型", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = modelPath,
            onValueChange = { modelPath = it },
            label = { Text("模型路径（.gguf，可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (localModelReady) "端侧模型可用" else "端侧模型 STUB（未启用）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                prefs.edit().putString(ProviderStore.KEY_MODEL_PATH, modelPath.trim()).apply()
                saved = true
            },
        ) { Text("保存端侧路径") }
        if (saved) Text("已保存", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()

        Text("运行模式", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == "build",
                onClick = { mode = "build"; prefs.edit().putString(ProviderStore.KEY_MODE, "build").apply() },
                label = { Text("BUILD") },
            )
            FilterChip(
                selected = mode == "plan",
                onClick = { mode = "plan"; prefs.edit().putString(ProviderStore.KEY_MODE, "plan").apply() },
                label = { Text("PLAN") },
            )
            FilterChip(
                selected = mode == "auto",
                onClick = { mode = "auto"; prefs.edit().putString(ProviderStore.KEY_MODE, "auto").apply() },
                label = { Text("AUTO") },
            )
        }

        HorizontalDivider()

        Text("语言", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = lang == "zh",
                onClick = { lang = "zh"; prefs.edit().putString(ProviderStore.KEY_LANG, "zh").apply() },
                label = { Text("中文") },
            )
            FilterChip(
                selected = lang == "en",
                onClick = { lang = "en"; prefs.edit().putString(ProviderStore.KEY_LANG, "en").apply() },
                label = { Text("English") },
            )
        }
    }

    editing?.let { provider ->
        ProviderEditorDialog(
            api = api,
            initial = provider,
            onDismiss = { editing = null },
            onSave = { updated ->
                val exists = providers.any { it.id == updated.id }
                val next = if (exists) {
                    providers.map { if (it.id == updated.id) updated else it }
                } else {
                    providers + updated
                }
                persist(next)
                // 新增/编辑后若无有效选中，落到该提供商的模型
                if (updated.models.isNotEmpty() &&
                    (active == null || active?.provider?.id == updated.id)
                ) {
                    val keepId = active?.model?.id
                    val keep = updated.models.firstOrNull { it.id == keepId } ?: updated.models.first()
                    ProviderStore.setActive(context, updated, keep)
                    refreshActive()
                }
                editing = null
                scope.launch {
                    snackbar.showSnackbar("已保存 ${updated.name.ifBlank { providerTypeLabel(updated.type) }}")
                }
            },
        )
    }

    deleteTarget?.let { provider ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除提供商？") },
            text = {
                Text(
                    "将删除「${provider.name.ifBlank { providerTypeLabel(provider.type) }}」及其模型配置。" +
                        "工作区与对话数据不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    persist(providers.filterNot { it.id == provider.id })
                    deleteTarget = null
                    scope.launch { snackbar.showSnackbar("已删除提供商") }
                }) { Text("删除", color = YayaColors.StatusError) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ModelProvider,
    active: ActiveModel?,
    onPickModel: (ProviderModel) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isActive = provider.id == active?.provider?.id
    YayaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            provider.name.ifBlank { providerTypeLabel(provider.type) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.size(6.dp))
                        Tag(providerTypeLabel(provider.type))
                        if (isActive) {
                            Spacer(Modifier.size(4.dp))
                            Tag("使用中", YayaColors.Primary)
                        }
                    }
                    Text(
                        provider.baseUrl.ifBlank { "(未填写 Base URL)" },
                        style = MonoLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑提供商", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除提供商", modifier = Modifier.size(18.dp))
                }
            }
            if (provider.models.isEmpty()) {
                Text(
                    "尚未添加模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = YayaColors.Warning,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                provider.models.forEach { model ->
                    val picked = isActive && model.id == active?.model?.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onPickModel(model) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = if (picked) "当前模型" else null,
                            tint = if (picked) YayaColors.Primary else YayaColors.Outline,
                            modifier = Modifier.size(16.dp),
                        )
                        Column(
                            Modifier.weight(1f).padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                model.id,
                                style = MonoSmall,
                                color = if (picked) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            CapsTags(model.caps)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditorDialog(
    api: AgentApi?,
    initial: ModelProvider,
    onDismiss: () -> Unit,
    onSave: (ModelProvider) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var type by remember { mutableStateOf(normalizeType(initial.type)) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models) }
    var manual by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var capsEditId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun addModel(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        if (models.any { it.id == clean }) {
            message = "模型已存在：$clean"
            return
        }
        models = models + ProviderModel(clean)
        message = null
    }

    fun fetchModels() {
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            message = "请先填写 Base URL 与 API Key"
            return
        }
        scope.launch {
            loading = true
            message = null
            val raw = withContext(Dispatchers.IO) {
                api?.listModels(baseUrl.trim(), apiKey.trim(), type)
                    ?: """{"ok":false,"message":"执行层未就绪"}"""
            }
            val (ids, err) = ProviderStore.parseModelList(raw)
            if (err != null) {
                message = err
            } else {
                var added = 0
                ids.forEach { id ->
                    if (models.none { it.id == id }) {
                        models = models + ProviderModel(id)
                        added++
                    }
                }
                message = when {
                    ids.isEmpty() -> "服务端未返回模型，可手动添加"
                    added == 0 -> "列表已是最新（共 ${ids.size} 个）"
                    else -> "已添加 $added 个模型"
                }
            }
            loading = false
        }
    }

    val capsTarget = models.firstOrNull { it.id == capsEditId }
    when {
        capsTarget != null -> ModelCapsDialog(
            model = capsTarget,
            onDismiss = { capsEditId = null },
            onSave = { updated ->
                models = models.map { if (it.id == updated.id) updated else it }
                capsEditId = null
            },
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("提供商配置") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称（展示用）") },
                        placeholder = { Text("如 OpenAI 官方") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("协议", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PROVIDER_TYPES.forEach { (value, label) ->
                            FilterChip(
                                selected = type == value,
                                onClick = {
                                    val wasDefault = baseUrl.isBlank() ||
                                        PROVIDER_TYPES.any { defaultBaseUrl(it.first) == baseUrl }
                                    type = value
                                    if (wasDefault) baseUrl = defaultBaseUrl(value)
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        placeholder = { Text(defaultBaseUrl(type)) },
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { fetchModels() }, enabled = !loading) {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Text(if (loading) " 拉取中..." else " 拉取模型列表")
                        }
                    }
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it.contains("已添加") || it.contains("最新"))
                                YayaColors.StatusSuccess else YayaColors.Warning,
                        )
                    }
                    Text("模型", style = MaterialTheme.typography.labelMedium)
                    if (models.isEmpty()) {
                        Text(
                            "尚未添加模型；可拉取或手动填写模型名。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    models.forEach { model ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(model.id, style = MonoSmall)
                                CapsTags(model.caps)
                            }
                            IconButton(onClick = { capsEditId = model.id }) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "编辑能力标签",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(onClick = {
                                models = models.filterNot { it.id == model.id }
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "移除模型",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            label = { Text("手动添加模型") },
                            placeholder = { Text("如 gpt-4o-mini") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            addModel(manual)
                            manual = ""
                        }) { Text("添加") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = baseUrl.trim()
                    if (url.isEmpty()) {
                        message = "Base URL 不能为空"
                        return@TextButton
                    }
                    onSave(
                        ModelProvider(
                            id = initial.id,
                            name = name.trim(),
                            type = type,
                            baseUrl = url,
                            apiKey = apiKey.trim(),
                            models = models.distinctBy { it.id },
                        )
                    )
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModelCapsDialog(
    model: ProviderModel,
    onDismiss: () -> Unit,
    onSave: (ProviderModel) -> Unit,
) {
    var tools by remember { mutableStateOf(model.caps.tools) }
    var vision by remember { mutableStateOf(model.caps.vision) }
    var context by remember {
        mutableStateOf(if (model.caps.contextTokens > 0) model.caps.contextTokens.toString() else "")
    }
    // 任务 30：费用估算单价（美元 / 百万 tokens，0/空表示未配置）。
    var inputCost by remember {
        mutableStateOf(if (model.caps.inputCostPerM > 0) model.caps.inputCostPerM.toString() else "")
    }
    var outputCost by remember {
        mutableStateOf(if (model.caps.outputCostPerM > 0) model.caps.outputCostPerM.toString() else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("能力标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.id, style = MonoSmall)
                Text(
                    "上下文窗口用于钳制 token 预算；tools/image 标签用于展示与请求声明。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tools,
                        onClick = { tools = !tools },
                        label = { Text("tools") },
                    )
                    FilterChip(
                        selected = vision,
                        onClick = { vision = !vision },
                        label = { Text("image") },
                    )
                }
                OutlinedTextField(
                    value = context,
                    onValueChange = { context = it.filter(Char::isDigit).take(9) },
                    label = { Text("上下文 tokens") },
                    placeholder = { Text("128000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "费用单价（可选，美元/百万 tokens）：用于对话页成本估算。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inputCost,
                        onValueChange = { inputCost = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                        label = { Text("输入 \$/M") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = outputCost,
                        onValueChange = { outputCost = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                        label = { Text("输出 \$/M") },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    ProviderModel(
                        model.id,
                        ModelCaps(
                            tools = tools,
                            vision = vision,
                            contextTokens = context.toIntOrNull() ?: 0,
                            inputCostPerM = inputCost.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                            outputCostPerM = outputCost.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
                        ),
                    )
                )
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
