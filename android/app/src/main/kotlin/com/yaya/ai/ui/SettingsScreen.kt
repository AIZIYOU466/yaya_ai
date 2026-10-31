package com.yaya.ai.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.yaya.ai.AgentApi
import com.yaya.ai.LocalModelFiles
import com.yaya.ai.LocalModels
import com.yaya.ai.SshHost
import com.yaya.ai.SshSettings
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
    var models by remember { mutableStateOf(emptyList<LocalModels.LocalModel>()) }
    var importing by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var deleteModel by remember { mutableStateOf<LocalModels.LocalModel?>(null) }
    // 任务 35：远程 SSH 执行后端。
    var ssh by remember { mutableStateOf(SshSettings()) }
    var sshFingerprint by remember { mutableStateOf<String?>(null) }
    var sshMessage by remember { mutableStateOf<String?>(null) }
    var sshTesting by remember { mutableStateOf(false) }
    var editingSsh by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("build") }
    var lang by remember { mutableStateOf("zh") }
    var localModelReady by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    // 任务 32：自定义子代理 + 同轮并行上限。
    var subagents by remember { mutableStateOf(SubagentStore.load(context)) }
    var maxParallel by remember { mutableStateOf(SubagentStore.maxParallel(context)) }
    var editingSubagent by remember { mutableStateOf<SubagentConfig?>(null) }
    var deleteSubagent by remember { mutableStateOf<SubagentConfig?>(null) }

    fun refreshActive() {
        active = ProviderStore.active(context, providers)
    }

    fun persist(list: List<ModelProvider>) {
        providers = list
        ProviderStore.save(context, list)
        ProviderStore.clearActiveIfMissing(context, list)
        refreshActive()
    }

    // 任务 31：SAF 选文件 → 复制进 filesDir/models/ → 自动选中（路径随 config.modelPath 下发）。
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                importProgress = null
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        LocalModels.import(context, uri) { copied, total ->
                            // 回调在 IO 线程；Compose 快照状态写入线程安全，且已按 8MB 节流。
                            importProgress = if (total > 0) {
                                (copied.toFloat() / total).coerceIn(0f, 1f)
                            } else {
                                null
                            }
                        }
                    }
                }
                importing = false
                importProgress = null
                result.onSuccess { model ->
                    models = withContext(Dispatchers.IO) { LocalModels.list(context) }
                    modelPath = model.path
                    snackbar.showSnackbar("已导入 ${model.name}（${LocalModelFiles.formatSize(model.size)}）")
                }.onFailure { e ->
                    snackbar.showSnackbar(e.message ?: "导入失败")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        modelPath = withContext(Dispatchers.IO) { LocalModels.reconcileSelection(context) }
        models = withContext(Dispatchers.IO) { LocalModels.list(context) }
        ssh = SshHost.load(context)
        sshFingerprint = SshHost.pinnedFingerprint(context)
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

        // 任务 31：端侧模型文件管理（导入 → filesDir/models/，选择写回 provider store）。
        Text("端侧模型", style = MaterialTheme.typography.titleMedium)
        Text(
            "导入 .gguf 文件到应用私有目录并选择；对话页把路径随 config.modelPath 下发。" +
                "文件不会进备份，卸载即删除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (models.isEmpty()) {
            YayaCard(Modifier.fillMaxWidth()) {
                Text(
                    "尚未导入模型文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        models.forEach { model ->
            val chosen = model.path == modelPath
            YayaCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            LocalModels.select(context, model.path)
                            modelPath = model.path
                            scope.launch { snackbar.showSnackbar("已选择 ${model.name}") }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = if (chosen) "当前端侧模型" else null,
                        tint = if (chosen) YayaColors.Primary else YayaColors.Outline,
                        modifier = Modifier.size(16.dp),
                    )
                    Column(
                        Modifier.weight(1f).padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            model.name,
                            style = MonoSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            LocalModelFiles.formatSize(model.size) +
                                if (chosen) " · 使用中" else "",
                            style = MonoLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { deleteModel = model }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除模型文件",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        if (importing) {
            LinearProgressIndicator(
                progress = { importProgress ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                importProgress?.let { "导入中… ${(it * 100).toInt()}%" } ?: "导入中…",
                style = MonoLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = !importing,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" 导入 .gguf 模型")
            }
            if (modelPath.isNotBlank()) {
                TextButton(onClick = {
                    LocalModels.clearSelection(context)
                    modelPath = ""
                }) { Text("清除选择") }
            }
        }
        Text(
            when {
                !localModelReady ->
                    "端侧推理不可用：当前为 STUB 构建（需 -PenableLlamaCpp=true 且 llama.cpp 源码就位）"
                modelPath.isBlank() -> "端侧推理可用，但尚未选择模型文件"
                else -> "端侧推理可用（简单提示词优先走端侧，见 R2 路由）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (modelPath.isNotBlank()) {
            Text(
                modelPath,
                style = MonoLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        HorizontalDivider()

        // 任务 35：远程 SSH 执行后端（terminal_exec + 文件工具；终端页/Git 页仍为本地 proot）。
        Text("远程 SSH 后端", style = MaterialTheme.typography.titleMedium)
        Text(
            "启用后，Agent 的终端命令与文件工具在远端执行（SFTP）；终端页与 Git 页仍为本地 proot。" +
                "主机密钥首次连接后钉扎，变化时拒绝连接。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        YayaCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when {
                                !ssh.enabled -> "未启用（命令走本地 proot）"
                                !ssh.isComplete -> "已启用，但配置不完整"
                                else -> "${ssh.user}@${ssh.host}:${ssh.port}"
                            },
                            style = MonoSmall,
                            color = if (ssh.enabled && !ssh.isComplete) YayaColors.Warning
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (ssh.isComplete) {
                            Text(
                                "远端工作区 ${ssh.remoteWorkspace.ifBlank { "/workspace" }} · " +
                                    if (ssh.auth == SshSettings.AUTH_KEY) "私钥认证" else "密码认证",
                                style = MonoLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = ssh.enabled,
                        onCheckedChange = { enabled ->
                            val next = ssh.copy(enabled = enabled)
                            ssh = next
                            SshHost.save(context, next)
                            sshMessage = null
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingSsh = true }) { Text("配置") }
                    OutlinedButton(
                        enabled = ssh.isComplete && !sshTesting,
                        onClick = {
                            scope.launch {
                                sshTesting = true
                                sshMessage = null
                                val raw = withContext(Dispatchers.IO) { SshHost.test(context, ssh) }
                                sshTesting = false
                                val o = runCatching { JSONObject(raw) }.getOrNull()
                                sshMessage = o?.optString("message")?.ifBlank { null } ?: "测试失败"
                                val fp = o?.optString("fingerprint").orEmpty()
                                sshFingerprint = fp.ifEmpty { SshHost.pinnedFingerprint(context) }
                            }
                        },
                    ) {
                        if (sshTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text(if (sshTesting) " 测试中…" else " 测试连接")
                    }
                    if (sshFingerprint != null) {
                        TextButton(onClick = {
                            SshHost.resetHostKey(context)
                            sshFingerprint = null
                            sshMessage = "已重置主机密钥；下次连接会重新记录指纹"
                        }) { Text("重置主机密钥") }
                    }
                }
                sshMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.startsWith("连接成功")) YayaColors.StatusSuccess
                        else YayaColors.Warning,
                    )
                }
                sshFingerprint?.let {
                    Text(
                        it,
                        style = MonoLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

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

        // 任务 32：子代理（内置 explore 只读，可启停；自定义可指定模型/工具白名单/专属指令）。
        Text("子代理", style = MaterialTheme.typography.titleMedium)
        Text(
            "同一轮可并行派发多个子代理（上限 $maxParallel）；explore 为内置只读代理。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        subagents.forEach { def ->
            SubagentCard(
                def = def,
                onToggle = { enabled ->
                    subagents = subagents.map { if (it.name == def.name) it.copy(enabled = enabled) else it }
                    SubagentStore.save(context, subagents)
                },
                onEdit = { editingSubagent = def },
                onDelete = { deleteSubagent = def },
            )
        }
        OutlinedButton(
            onClick = {
                editingSubagent = SubagentConfig(name = "")
            },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(" 添加子代理")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "并行上限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            (1..SubagentStore.MAX_PARALLEL).forEach { n ->
                FilterChip(
                    selected = maxParallel == n,
                    onClick = {
                        maxParallel = n
                        SubagentStore.setMaxParallel(context, n)
                    },
                    label = { Text("$n") },
                )
            }
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

    editingSubagent?.let { initial ->
        SubagentEditorDialog(
            initial = initial,
            existing = subagents,
            onDismiss = { editingSubagent = null },
            onSave = { updated ->
                val exists = subagents.any { it.name == updated.name }
                subagents = if (exists && initial.name == updated.name) {
                    subagents.map { if (it.name == updated.name) updated else it }
                } else {
                    subagents + updated
                }
                SubagentStore.save(context, subagents)
                editingSubagent = null
                scope.launch { snackbar.showSnackbar("已保存子代理 ${updated.name}") }
            },
        )
    }

    if (editingSsh) {
        SshEditorDialog(
            initial = ssh,
            onDismiss = { editingSsh = false },
            onSave = { updated ->
                SshHost.save(context, updated)
                ssh = updated
                sshFingerprint = SshHost.pinnedFingerprint(context)
                sshMessage = null
                editingSsh = false
                scope.launch { snackbar.showSnackbar("已保存 SSH 配置") }
            },
        )
    }

    deleteModel?.let { model ->
        AlertDialog(
            onDismissRequest = { deleteModel = null },
            title = { Text("删除模型文件？") },
            text = {
                Text(
                    "将从应用私有目录删除「${model.name}」（${LocalModelFiles.formatSize(model.size)}），" +
                        "需重新导入才能再使用。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = runCatching { LocalModels.delete(context, model) }.exceptionOrNull()
                    models = LocalModels.list(context)
                    modelPath = LocalModels.selected(context)
                    deleteModel = null
                    scope.launch { snackbar.showSnackbar(err?.message ?: "已删除 ${model.name}") }
                }) { Text("删除", color = YayaColors.StatusError) }
            },
            dismissButton = {
                TextButton(onClick = { deleteModel = null }) { Text("取消") }
            },
        )
    }

    deleteSubagent?.let { def ->
        AlertDialog(
            onDismissRequest = { deleteSubagent = null },
            title = { Text("删除子代理？") },
            text = { Text("将删除「${def.name}」的定义；已开始的对话不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    subagents = subagents.filterNot { it.name == def.name }
                    SubagentStore.save(context, subagents)
                    deleteSubagent = null
                    scope.launch { snackbar.showSnackbar("已删除子代理") }
                }) { Text("删除", color = YayaColors.StatusError) }
            },
            dismissButton = {
                TextButton(onClick = { deleteSubagent = null }) { Text("取消") }
            },
        )
    }
}

/** 远程 SSH 配置弹窗（任务 35）：主机/端口/用户/认证（密码或私钥导入）/远端工作区。 */
@Composable
private fun SshEditorDialog(
    initial: SshSettings,
    onDismiss: () -> Unit,
    onSave: (SshSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    var auth by remember { mutableStateOf(initial.auth) }
    var password by remember { mutableStateOf(initial.password) }
    var keyPath by remember { mutableStateOf(initial.keyPath) }
    var passphrase by remember { mutableStateOf(initial.passphrase) }
    var workspace by remember {
        mutableStateOf(initial.remoteWorkspace.ifBlank { "/workspace" })
    }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val keyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                val result = withContext(Dispatchers.IO) { runCatching { SshHost.importKey(context, uri) } }
                importing = false
                result.onSuccess { path ->
                    keyPath = path
                    auth = SshSettings.AUTH_KEY
                    message = "已导入私钥：${path.substringAfterLast('/')}"
                }.onFailure { e -> message = e.message ?: "导入私钥失败" }
            }
        }
    }

    val portValue = port.toIntOrNull()
    val valid = host.isNotBlank() && user.isNotBlank() &&
        portValue != null && portValue in 1..65535 &&
        workspace.trim().startsWith("/") &&
        if (auth == SshSettings.AUTH_KEY) keyPath.isNotBlank() else password.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("远程 SSH 后端") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机（IP 或域名）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                }
                Text("认证方式", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = auth == SshSettings.AUTH_PASSWORD,
                        onClick = { auth = SshSettings.AUTH_PASSWORD },
                        label = { Text("密码") },
                    )
                    FilterChip(
                        selected = auth == SshSettings.AUTH_KEY,
                        onClick = { auth = SshSettings.AUTH_KEY },
                        label = { Text("私钥") },
                    )
                }
                if (auth == SshSettings.AUTH_PASSWORD) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = keyPath.ifBlank { "" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("私钥文件") },
                            placeholder = { Text("从文件选择 OpenSSH / PEM 私钥") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = !importing,
                            onClick = { keyLauncher.launch(arrayOf("*/*")) },
                        ) { Text(if (importing) "导入中…" else "选择文件") }
                    }
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("私钥口令（可选）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = workspace,
                    onValueChange = { workspace = it },
                    label = { Text("远端工作区路径（绝对路径）") },
                    placeholder = { Text("/workspace") },
                    singleLine = true,
                    isError = !workspace.trim().startsWith("/"),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "密码/口令明文存于应用私有配置（与 API Key 同口径），备份经加密容器；" +
                        "文件工具的相对路径以远端工作区为根。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = YayaColors.Warning,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            host = host.trim(),
                            port = portValue ?: initial.port,
                            user = user.trim(),
                            auth = auth,
                            password = password,
                            keyPath = keyPath,
                            passphrase = passphrase,
                            remoteWorkspace = workspace.trim().ifBlank { "/workspace" },
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 子代理卡片（任务 32）：名称/说明/能力 + 启停；自定义代理可编辑/删除。 */
@Composable
private fun SubagentCard(
    def: SubagentConfig,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    YayaCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    def.name + if (def.builtin) " · 内置" else "",
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (def.description.isNotBlank()) {
                    Text(
                        def.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val caps = listOfNotNull(
                    if (def.model.isNotBlank()) "模型 ${def.model}" else null,
                    if (def.tools.isEmpty()) "全部工具" else "工具 ${def.tools.size} 个",
                    if (def.maxSteps > 0) "≤${def.maxSteps} 步" else null,
                )
                Text(
                    caps.joinToString(" · "),
                    style = MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!def.builtin) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "编辑子代理", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除子代理", modifier = Modifier.size(16.dp))
                }
            }
            Switch(checked = def.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 子代理编辑弹窗：名称/说明/模型/专属指令/工具白名单/步数。 */
@Composable
private fun SubagentEditorDialog(
    initial: SubagentConfig,
    existing: List<SubagentConfig>,
    onDismiss: () -> Unit,
    onSave: (SubagentConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }
    var model by remember { mutableStateOf(initial.model) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var tools by remember { mutableStateOf(initial.tools.joinToString(",")) }
    var maxSteps by remember { mutableStateOf(if (initial.maxSteps > 0) initial.maxSteps.toString() else "") }
    var enabled by remember { mutableStateOf(initial.enabled) }

    val trimmed = name.trim()
    val conflict = existing.any { it.name == trimmed && it.name != initial.name }
    val reserved = trimmed == SubagentStore.GENERAL || trimmed == SubagentStore.EXPLORE
    val valid = trimmed.isNotEmpty() && !conflict && !reserved

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "添加子代理" else "编辑子代理") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（模型用 subagent.agent 选择）") },
                    singleLine = true,
                    isError = conflict || reserved,
                    supportingText = {
                        when {
                            reserved -> Text("general / explore 为保留名")
                            conflict -> Text("名称已存在")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("一句话说明（供模型选择）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("专属模型（可选，留空继承）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("专属指令（追加在系统提示词后）") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tools,
                    onValueChange = { tools = it },
                    label = { Text("工具白名单（逗号分隔，留空继承全部）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxSteps,
                    onValueChange = { maxSteps = it.filter(Char::isDigit) },
                    label = { Text("步数上限（可选）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        SubagentConfig(
                            name = trimmed,
                            description = description.trim(),
                            prompt = prompt.trim(),
                            model = model.trim(),
                            tools = tools.split(',', '\n', '，')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() },
                            enabled = enabled,
                            maxSteps = maxSteps.toIntOrNull()?.coerceAtLeast(1) ?: 0,
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
