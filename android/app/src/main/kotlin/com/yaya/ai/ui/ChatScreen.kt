package com.yaya.ai.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaya.ai.ChatImages
import com.yaya.ai.ui.components.CapsTags
import com.yaya.ai.ui.components.StatusDot
import com.yaya.ai.ui.components.Tag
import com.yaya.ai.ui.components.TaskCard
import com.yaya.ai.ui.components.TaskCardState
import com.yaya.ai.ui.components.YayaCard
import com.yaya.ai.ui.components.pressScale
import com.yaya.ai.ui.theme.MonoLabel
import com.yaya.ai.ui.theme.MonoSmall
import com.yaya.ai.ui.theme.Motion
import com.yaya.ai.ui.theme.YayaColors
import com.yaya.ai.AgentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val chatIdGen = java.util.concurrent.atomic.AtomicLong()

/** 任务 30：一次任务内多次生成的用量聚合（回复气泡下展示 + 会话累计）。 */
private data class UsageInfo(
    val prompt: Int = 0,
    val completion: Int = 0,
    val total: Int = 0,
    val cached: Int = 0,
    val durationMs: Long = 0,
    val costUsd: Double = 0.0,
    val rounds: Int = 0,
    val compactTokens: Int = 0,
) {
    fun merge(other: UsageInfo): UsageInfo = UsageInfo(
        prompt = prompt + other.prompt,
        completion = completion + other.completion,
        total = total + other.total,
        cached = cached + other.cached,
        durationMs = durationMs + other.durationMs,
        costUsd = costUsd + other.costUsd,
        rounds = rounds + other.rounds,
        compactTokens = compactTokens + other.compactTokens,
    )

    fun isEmpty(): Boolean = total == 0 && compactTokens == 0
}

private data class ChatItem(
    val role: String,
    val text: String,
    val ok: Boolean,
    val id: Long = chatIdGen.incrementAndGet(),
    val images: List<String> = emptyList(),
    val usage: UsageInfo? = null,
)
private data class QueuedMessage(
    val prompt: String,
    val display: String,
    val picks: List<PickedImage>,
    val command: Boolean,
    val id: Long = chatIdGen.incrementAndGet(),
)
private data class ApprovalReq(val id: String, val tool: String, val args: String, val reversibility: String)
private data class SessionMeta(val id: String, val title: String, val updatedAt: Long, val count: Int)

/** 任务 28：斜杠命令（46 决策：仅 /status；/compress 待压缩增强稳定后再启用）。 */
private val SLASH_COMMANDS = listOf(
    "/status" to "查看当前会话 token / 模型 / 模式",
)

private fun usageFromJson(m: JSONObject): UsageInfo {
    val prompt = m.optInt("promptTokens")
    val completion = m.optInt("completionTokens")
    val total = m.optString("text").toIntOrNull() ?: (prompt + completion)
    val kind = m.optString("kind")
    return UsageInfo(
        prompt = prompt,
        completion = completion,
        total = total,
        cached = m.optInt("cachedTokens"),
        durationMs = m.optLong("durationMs"),
        costUsd = m.optDouble("costUsd", 0.0),
        rounds = if (kind == "compact") 0 else 1,
        compactTokens = if (kind == "compact") total else 0,
    )
}

private fun usageLine(u: UsageInfo): String {
    val parts = mutableListOf<String>()
    if (u.prompt > 0 || u.completion > 0) {
        parts += "↑${formatTokens(u.prompt)} ↓${formatTokens(u.completion)}"
    } else if (u.total > 0) {
        parts += "共 ${formatTokens(u.total)} tokens"
    }
    if (u.cached > 0 && u.prompt > 0) {
        parts += "缓存 ${(u.cached * 100 / u.prompt).coerceAtMost(100)}%"
    }
    if (u.compactTokens > 0) parts += "压缩 ${formatTokens(u.compactTokens)}"
    if (u.durationMs > 0) parts += formatDuration(u.durationMs)
    parts += if (u.costUsd > 0) "≈\$${formatCost(u.costUsd)}" else "单价未配置"
    return parts.joinToString(" · ")
}

/** 聊天页（P3 迁移自 chat_screen.dart）：Agent 事件流 + 消息渲染 + 会话/模型管理。 */
@Composable
fun ChatScreen(onOpenDrawer: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val api = LocalAgentApi.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE) }
    val snackbar = LocalSnackbar.current

    val items = remember { mutableStateListOf<ChatItem>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    // 任务 27：挂起态与消息队列。挂起不是失败，队列在继续/新一轮结束后自动发送。
    var suspended by remember { mutableStateOf(false) }
    val queue = remember { mutableStateListOf<QueuedMessage>() }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // R12 运行模式与当前模型：与授权行为直接相关，必须在聊天页可见（46 决策）
    val mode = prefs.getString(ProviderStore.KEY_MODE, "build") ?: "build"
    var activeModel by remember { mutableStateOf(ProviderStore.active(context)) }
    var taskState by remember {
        mutableStateOf(TaskCardState(mode = mode, model = activeModel?.model?.id ?: ""))
    }
    var showModels by remember { mutableStateOf(false) }
    var sessionId by remember { mutableStateOf("") }
    var historyCount by remember { mutableStateOf(0) }
    var totalTokens by remember { mutableStateOf(0) }
    var pendingTokens by remember { mutableStateOf("") }
    // 任务 30：流式阶段先挂起用量，下一条 assistant 消息落位时附上。
    var pendingUsage by remember { mutableStateOf(UsageInfo()) }
    var sessionUsage by remember { mutableStateOf(UsageInfo()) }
    var approval by remember { mutableStateOf<ApprovalReq?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var showCheckpoints by remember { mutableStateOf(false) }
    // 任务 26：待发送图片、相册选择、全屏查看。
    val attachments = remember { mutableStateListOf<PickedImage>() }
    var processingImages by remember { mutableStateOf(false) }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            processingImages = true
            val room = (MAX_CHAT_IMAGES - attachments.size).coerceAtLeast(0)
            if (uris.size > room) snackbar.showSnackbar("最多附 $MAX_CHAT_IMAGES 张图片")
            var failed = 0
            uris.take(room).forEach { uri ->
                val picked = prepareChatImage(context, uri)
                if (picked == null) failed += 1 else attachments.add(picked)
            }
            processingImages = false
            if (failed > 0) snackbar.showSnackbar("$failed 张图片读取失败")
        }
    }
    val listState = rememberLazyListState()

    fun refreshScroll() {
        scope.launch {
            listState.animateScrollToItem((items.size - 1).coerceAtLeast(0))
        }
    }

    fun appendSystem(text: String) {
        if (text.isEmpty()) return
        items.add(ChatItem("system", text, true))
        refreshScroll()
    }

    /** 恢复消息（含任务 26 图片路径、任务 30 用量）；空消息跳过。 */
    fun restoreItems(messages: JSONArray) {
        items.clear()
        sessionUsage = UsageInfo()
        totalTokens = 0
        pendingUsage = UsageInfo()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val text = m.optString("text")
            if (role == "usage") {
                val u = usageFromJson(m)
                sessionUsage = sessionUsage.merge(u)
                totalTokens += u.total
                val last = items.lastOrNull()
                if (last?.role == "assistant") {
                    items[items.lastIndex] = last.copy(usage = (last.usage ?: UsageInfo()).merge(u))
                } else {
                    pendingUsage = pendingUsage.merge(u)
                }
                continue
            }
            val images = m.optJSONArray("images")?.let { arr ->
                (0 until arr.length()).mapNotNull { idx ->
                    arr.optString(idx).takeIf { it.isNotBlank() }
                }
            } ?: emptyList()
            if (text.isEmpty() && images.isEmpty()) continue
            if (role == "assistant" && items.isNotEmpty() && items.last().role == "assistant") {
                val last = items.removeAt(items.lastIndex)
                items.add(last.copy(text = last.text + text))
            } else {
                items.add(ChatItem(role, text, m.optBoolean("ok", true), images = images))
            }
        }
        historyCount = items.size
    }

    /** 用量附到本轮最后一条 assistant 回复（流式已落位则立即展示）。 */
    fun applyUsage(u: UsageInfo) {
        pendingUsage = pendingUsage.merge(u)
        val last = items.lastOrNull()
        if (last?.role == "assistant" && items.size > historyCount && pendingTokens.isEmpty()) {
            items[items.lastIndex] = last.copy(usage = (last.usage ?: UsageInfo()).merge(u))
            pendingUsage = UsageInfo()
        }
    }

    fun flushTokens() {
        if (pendingTokens.isEmpty()) return
        val u = pendingUsage.takeIf { !it.isEmpty() }
        pendingUsage = UsageInfo()
        if (items.isNotEmpty() && items.size > historyCount && items.last().role == "assistant") {
            val last = items.removeAt(items.lastIndex)
            items.add(
                last.copy(
                    text = last.text + pendingTokens,
                    usage = u?.let { (last.usage ?: UsageInfo()).merge(it) } ?: last.usage,
                )
            )
        } else {
            items.add(ChatItem("assistant", pendingTokens, true, usage = u))
        }
        pendingTokens = ""
        refreshScroll()
    }

    fun onEvent(raw: String) {
        val ev = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        when (ev.optString("type")) {
            "token" -> pendingTokens += ev.optString("text")
            "tool_call" -> {
                items.add(ChatItem("tool", "调用 ${ev.optString("name")} ${ev.opt("args")}", true))
                taskState = taskState.copy(lastStep = "调用 ${ev.optString("name")}")
                refreshScroll()
            }
            "tool_result" -> {
                val t = ev.optString("content").trim()
                if (t.isNotEmpty()) {
                    items.add(ChatItem("tool", t, ev.optBoolean("ok")))
                    refreshScroll()
                }
            }
            "notice" -> appendSystem(ev.optString("message"))
            "state" -> {
                status = ev.optString("state")
                taskState = taskState.copy(status = status)
            }
            "usage" -> {
                // 任务 30：展示每次回复的输入/输出/缓存/耗时/费用；token 同时计入会话累计。
                val kind = ev.optString("kind")
                val prompt = ev.optInt("prompt_tokens")
                val completion = ev.optInt("completion_tokens")
                val total = ev.optInt("total_tokens", prompt + completion)
                val inputPrice = activeModel?.model?.caps?.inputCostPerM ?: 0.0
                val outputPrice = activeModel?.model?.caps?.outputCostPerM ?: 0.0
                val u = UsageInfo(
                    prompt = prompt,
                    completion = completion,
                    total = total,
                    cached = ev.optInt("cached_tokens"),
                    durationMs = ev.optLong("duration_ms"),
                    costUsd = (prompt * inputPrice + completion * outputPrice) / 1_000_000.0,
                    rounds = if (kind == "compact") 0 else 1,
                    compactTokens = if (kind == "compact") total else 0,
                )
                totalTokens += total
                sessionUsage = sessionUsage.merge(u)
                taskState = taskState.copy(
                    tokens = totalTokens,
                    costUsd = sessionUsage.costUsd.takeIf { it > 0 },
                )
                applyUsage(u)
            }
            "done" -> {
                flushTokens()
                running = false
                suspended = false
                status = "done"
                taskState = taskState.copy(status = "done")
            }
            "suspended" -> {
                // 任务 27：core 已保存循环光标；保留队列，等待用户继续。
                flushTokens()
                running = false
                suspended = true
                status = "suspended"
                taskState = taskState.copy(status = "suspended")
                appendSystem(ev.optString("reason").ifBlank { "任务已暂停，可点击继续" })
            }
            "error" -> {
                flushTokens()
                running = false
                suspended = false
                appendSystem("错误: ${ev.optString("message")}")
                taskState = taskState.copy(status = "error")
            }
            "approval_request" -> {
                flushTokens()
                approval = ApprovalReq(
                    id = ev.optString("id"),
                    tool = ev.optString("tool"),
                    args = ev.opt("args")?.toString() ?: "",
                    reversibility = ev.optString("reversibility"),
                )
            }
        }
    }

    // 事件流常驻收集（订阅在 send 之前就绪，不会漏事件）
    LaunchedEffect(Unit) {
        api?.events?.collectLatest { json -> onEvent(json) }
    }

    // token 缓冲节流：30ms 合并刷新，避免每 token 全表重组
    LaunchedEffect(pendingTokens) {
        if (pendingTokens.isNotEmpty()) {
            delay(30)
            flushTokens()
        }
    }

    fun readConfig(
        userImages: List<String> = emptyList(),
        userImageFiles: List<String> = emptyList(),
    ): JSONObject {
        fun read(key: String, def: String = "") = prefs.getString(key, def) ?: def
        fun arrOf(values: List<String>) = JSONArray().apply { values.forEach { put(it) } }
        val mcp = try {
            val arr = JSONArray(prefs.getString("flutter.mcp_servers", null) ?: "[]")
            val list = JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                if (s.optBoolean("enabled", false)) list.put(s)
            }
            list
        } catch (_: Exception) {
            JSONArray()
        }
        // 任务 25：多提供商下发（core 按端点实例注册 + 池路由）。
        // 当前选中提供商排首位：端点池同分时首个优先，其余提供商作为故障切换目标。
        val providers = ProviderStore.load(context)
        val active = ProviderStore.active(context, providers)
        val ordered = if (active == null) providers
        else listOf(active.provider) + providers.filterNot { it.id == active.provider.id }
        val providersJson = JSONArray()
        ordered.forEach { p ->
            val modelId = if (p.id == active?.provider?.id) active.model.id
            else p.models.firstOrNull()?.id
            if (modelId == null || p.baseUrl.isBlank()) return@forEach
            providersJson.put(
                JSONObject().apply {
                    put("baseUrl", p.baseUrl.trim())
                    put("apiKey", p.apiKey)
                    put("model", modelId)
                    put("type", p.type)
                }
            )
        }
        return JSONObject().apply {
            put("baseUrl", active?.provider?.baseUrl?.trim() ?: read(ProviderStore.KEY_BASE_URL))
            put("apiKey", active?.provider?.apiKey ?: read(ProviderStore.KEY_API_KEY))
            put("model", active?.model?.id ?: read(ProviderStore.KEY_MODEL_NAME))
            put("modelPath", read(ProviderStore.KEY_MODEL_PATH))
            // 任务 30：费用单价随 config 下发，AgentHost 落库时据此计算每条回复费用。
            put("inputCostPerM", active?.model?.caps?.inputCostPerM ?: 0.0)
            put("outputCostPerM", active?.model?.caps?.outputCostPerM ?: 0.0)
            put("localAvailable", api?.localAvailable() == true)
            put("networkOk", api?.networkAvailable() == true)
            put("maxSteps", 12)
            put("mode", read(ProviderStore.KEY_MODE, "build"))
            put("mcpServers", mcp)
            if (providersJson.length() > 0) put("providers", providersJson)
            // 任务 26：首条 user 消息图片（data URL 给 core，受管路径给本地消息持久化）。
            if (userImages.isNotEmpty()) put("userImages", arrOf(userImages))
            if (userImageFiles.isNotEmpty()) put("userImageFiles", arrOf(userImageFiles))
            active?.provider?.baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
                put("preferredEndpoint", it)
            }
            active?.model?.caps?.let { caps ->
                put(
                    "capabilities",
                    JSONObject().apply {
                        put("functionCalling", caps.tools)
                        put("vision", caps.vision)
                        if (caps.contextTokens > 0) put("maxContextTokens", caps.contextTokens)
                    }
                )
            }
        }
    }

    fun runLocalCommand(raw: String) {
        val cmd = raw.trim().substringBefore(' ')
        when (cmd) {
            "/status" -> {
                val stateLabel = when {
                    running -> "运行中"
                    suspended -> "已暂停"
                    else -> "空闲"
                }
                val modelLabel = activeModel?.model?.id?.takeIf { it.isNotBlank() } ?: "未配置模型"
                val costLabel = if (sessionUsage.costUsd > 0) {
                    "≈\$${formatCost(sessionUsage.costUsd)}"
                } else {
                    "单价未配置"
                }
                val cacheLabel = if (sessionUsage.cached > 0 && sessionUsage.prompt > 0) {
                    " · 缓存命中 ${(sessionUsage.cached * 100 / sessionUsage.prompt).coerceAtMost(100)}%"
                } else ""
                appendSystem(
                    buildString {
                        appendLine("命令 /status")
                        appendLine("会话 ${sessionId.ifEmpty { "(未开始)" }} · $stateLabel")
                        appendLine("模式 ${mode.uppercase()} · 模型 $modelLabel")
                        appendLine(
                            "tokens ${formatTokens(totalTokens)}（输入 ${formatTokens(sessionUsage.prompt)}" +
                                " / 输出 ${formatTokens(sessionUsage.completion)}）$cacheLabel"
                        )
                        append("累计耗时 ${formatDuration(sessionUsage.durationMs)} · 费用 $costLabel · 队列 ${queue.size}")
                    }
                )
            }
            else -> appendSystem("未知命令：$cmd\n可用命令：/status — 查看当前会话状态")
        }
    }

    /** 真正启动一次 Agent 任务（输入、队列与命令路由已在 [send] 处理）。 */
    fun dispatch(agentPrompt: String, picks: List<PickedImage>, displayText: String) {
        running = true
        suspended = false
        status = "starting"
        taskState = TaskCardState(
            goal = displayText,
            status = "starting",
            tokens = totalTokens,
            mode = mode,
            model = activeModel?.model?.id ?: "",
            startedAt = System.currentTimeMillis(),
            costUsd = sessionUsage.costUsd.takeIf { it > 0 },
        )
        if (sessionId.isEmpty()) sessionId = System.currentTimeMillis().toString()
        val dataUrls = picks.map { it.dataUrl }
        scope.launch {
            // 先落盘受管附件（供会话恢复），再启动任务；失败显式报错并恢复输入。
            val saved = if (picks.isEmpty()) {
                emptyList()
            } else withContext(Dispatchers.IO) {
                picks.map { ChatImages.save(context, it.bytes) }
            }
            if (saved.any { it == null }) {
                ChatImages.delete(context, saved.filterNotNull())
                appendSystem("图片保存失败：无法写入附件目录")
                running = false
                status = ""
                taskState = taskState.copy(status = "error")
                if (attachments.isEmpty()) attachments.addAll(picks)
                if (input.isBlank()) input = displayText
                return@launch
            }
            val paths = saved.filterNotNull()
            items.add(ChatItem("user", displayText, true, images = paths))
            refreshScroll()
            val config = readConfig(dataUrls, paths)
            val started = withContext(Dispatchers.IO) {
                api?.startAgent(sessionId, agentPrompt, config.toString()) == true
            }
            if (!started) {
                if (items.lastOrNull()?.images == paths) items.removeAt(items.lastIndex)
                ChatImages.delete(context, paths)
                appendSystem("启动失败：Rust Core 未就绪")
                running = false
                status = ""
                taskState = taskState.copy(status = "error")
                if (attachments.isEmpty()) attachments.addAll(picks)
                if (input.isBlank()) input = displayText
            }
        }
    }

    fun send() {
        if (processingImages) return
        val text = input.trim()
        val picks = attachments.toList()
        if (text.isEmpty() && picks.isEmpty()) return
        if (picks.isNotEmpty() && activeModel?.model?.caps?.vision == false) {
            scope.launch { snackbar.showSnackbar("当前模型未标注图片能力，请切换支持图片的模型") }
            return
        }
        // 斜杠命令：空闲时立即执行，忙/暂停时排队（任务 27/28）。
        if (text.startsWith("/") && picks.isEmpty()) {
            input = ""
            if (running || suspended) {
                queue.add(QueuedMessage(prompt = text, display = text, picks = emptyList(), command = true))
            } else {
                runLocalCommand(text)
            }
            return
        }
        // Anthropic 拒绝空 text 块：纯图片消息用中性占位文本。
        val prompt = text.ifBlank { "请查看图片" }
        input = ""
        attachments.clear()
        if (running || suspended) {
            queue.add(QueuedMessage(prompt = prompt, display = text.ifBlank { prompt }, picks = picks, command = false))
            scope.launch {
                snackbar.showSnackbar(
                    if (suspended) "任务已暂停：消息已排队，点击继续后执行" else "已加入队列（${queue.size}）"
                )
            }
            return
        }
        dispatch(prompt, picks, text.ifBlank { prompt })
    }

    /** 从检查点继续挂起的任务（任务 27/43）。 */
    fun resumeTask() {
        if (running || !suspended) return
        if (sessionId.isEmpty()) {
            appendSystem("没有可恢复的会话")
            return
        }
        running = true
        suspended = false
        status = "starting"
        historyCount = items.size // 暂停前的残缺输出不并入恢复后的回复
        taskState = taskState.copy(status = "starting", startedAt = System.currentTimeMillis())
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                api?.resumeAgent(sessionId, readConfig().toString()) == true
            }
            if (!started) {
                running = false
                suspended = true
                status = "suspended"
                taskState = taskState.copy(status = "suspended")
                appendSystem("继续失败：没有可恢复的任务断点")
            }
        }
    }

    fun newSession() {
        items.clear()
        attachments.clear()
        viewingImage = null
        queue.clear()
        sessionId = System.currentTimeMillis().toString()
        historyCount = 0
        totalTokens = 0
        sessionUsage = UsageInfo()
        suspended = false
        status = ""
        taskState = TaskCardState(mode = mode, model = activeModel?.model?.id ?: "")
    }

    fun restoreRecent() {
        scope.launch {
            val raw = withContext(Dispatchers.IO) { api?.loadRecentSession() ?: "" }
            if (raw.isBlank() || items.isNotEmpty()) return@launch
            val data = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return@launch
            }
            sessionId = data.optString("sessionId")
            val messages = data.optJSONArray("messages") ?: return@launch
            restoreItems(messages)
            // 进程被杀后仍有循环光标：恢复“已暂停”状态并提供继续入口。
            if (api?.hasPendingTask() == true) {
                suspended = true
                status = "suspended"
                taskState = TaskCardState(
                    goal = items.lastOrNull { it.role == "user" }?.text ?: "",
                    status = "suspended",
                    tokens = totalTokens,
                    mode = mode,
                    model = activeModel?.model?.id ?: "",
                    costUsd = sessionUsage.costUsd.takeIf { it > 0 },
                )
            }
        }
    }

    LaunchedEffect(Unit) { restoreRecent() }

    // 任务 27：当前任务结束且未挂起时，自动发送队首消息；挂起期间队列保持等待。
    LaunchedEffect(running, suspended, processingImages, queue.size) {
        if (!running && !suspended && !processingImages && queue.isNotEmpty()) {
            val next = queue.removeAt(0)
            if (next.command) {
                runLocalCommand(next.display)
            } else {
                dispatch(next.prompt, next.picks, next.display)
            }
        }
    }

    // ── UI ──

    // 渲染分组：连续 tool 消息合并为步骤卡（每次重组重算，消息数有限）
    val grouped = groupItems(items)

    val glow = remember {
        Brush.verticalGradient(
            colors = listOf(YayaColors.AmbientGlow, Color.Transparent),
            startY = 0f,
            endY = 360f,
        )
    }
    Box(Modifier.fillMaxSize().background(glow)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                // 顶部避让状态栏（背景仍铺满到状态栏区域），底部全沉浸平铺到手势下方
                modifier = Modifier.fillMaxWidth()
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "菜单")
            }
            IconButton(onClick = {
                if (running || suspended) {
                    scope.launch { snackbar.showSnackbar("请先继续或结束当前任务") }
                    return@IconButton
                }
                showSessions = true
            }) {
                Icon(Icons.Filled.SwitchAccount, contentDescription = "会话")
            }
            IconButton(onClick = {
                if (running) return@IconButton
                showModels = true
            }) {
                Icon(Icons.Filled.Memory, contentDescription = "切换模型")
            }
            TaskCard(
                state = taskState,
                // 任务 27：停止 = 挂起（保存检查点，不丢弃任务）；挂起后按钮变“继续”。
                onPause = {
                    if (running) {
                        api?.suspendAgent()
                        taskState = taskState.copy(lastStep = "正在暂停：当前步骤结束后保存检查点…")
                        scope.launch { snackbar.showSnackbar("正在暂停，检查点保存后可继续") }
                    }
                },
                onResume = { resumeTask() },
                onUndo = { if (!running && !suspended) showCheckpoints = true },
                modifier = Modifier.weight(1f),
            )
        }

        if (items.isEmpty()) {
            // weight(1f) 而非 fillMaxSize：fillMaxSize 会占满剩余空间把输入区挤出屏幕
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "输入指令，或点击下方示例",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("运行测试", "查看项目结构", "写一段代码").forEach { cmd ->
                            YayaCard(Modifier.clickable { input = cmd }) {
                                Text(
                                    cmd,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    count = grouped.size,
                    // 稳定 key：按消息 id，流式合并/追加不重置已有项的入场状态
                    key = { i ->
                        when (val g = grouped[i]) {
                            is RenderItem.Msg -> g.item.id
                            is RenderItem.Step -> g.steps.first().id
                        }
                    },
                ) { i ->
                    when (val g = grouped[i]) {
                        is RenderItem.Msg -> Reveal(g.item.id, i) {
                            ChatRow(
                                item = g.item,
                                streaming = running && i == grouped.lastIndex,
                            ) { viewingImage = it }
                        }
                        is RenderItem.Step -> Reveal(g.steps.first().id, i) { ToolStepCard(g.steps) }
                    }
                }
            }
        }

        // 输入区：与页面背景同色（手势行区域不会出现异色带），输入框白色卡片贴底
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
        ) {
        Column(
            // 内容避开手势栏保证输入框完整露出；Surface 背景仍铺满到手势栏下（与页面同色）
            modifier = Modifier.fillMaxWidth()
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                .padding(8.dp).imePadding(),
        ) {
            // 任务 28：输入 "/" 时展示可用命令，点击填入。
            val slashPrefix = input.trimStart()
            if (slashPrefix.startsWith("/") && !slashPrefix.contains(' ')) {
                val matches = SLASH_COMMANDS.filter { (cmd, _) -> cmd.startsWith(slashPrefix) }
                if (matches.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                    ) {
                        matches.forEach { (cmd, desc) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { input = cmd }
                                    .padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(cmd, style = MonoLabel, color = YayaColors.Primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            // 任务 27：排队面板（AI 忙 / 挂起时输入进入队列，可单独跳过）。
            if (queue.isNotEmpty()) {
                YayaCard(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            "待发送 ${queue.size} 条" + if (suspended) "（继续任务后自动发送）" else "",
                            style = MonoLabel,
                            color = if (suspended) YayaColors.Warning else YayaColors.Primary,
                        )
                        queue.forEach { q ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (q.command) "" else "▸ ") + q.display.replace('\n', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { queue.remove(q) }) {
                                    Text("跳过", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
            if (attachments.isNotEmpty() || processingImages) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attachments.forEachIndexed { idx, img ->
                        Box(Modifier.size(64.dp)) {
                            Image(
                                bitmap = img.preview,
                                contentDescription = "待发送图片 ${idx + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .clickable { attachments.remove(img) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除图片",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                    if (processingImages) {
                        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (activeModel?.model?.caps?.vision == false) {
                            scope.launch { snackbar.showSnackbar("当前模型未标注图片能力，可在设置中开启") }
                        } else {
                            pickImages.launch("image/*")
                        }
                    },
                    enabled = !processingImages,
                ) {
                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "添加图片")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            when {
                                suspended -> "任务已暂停：输入将排队，点击继续执行"
                                running -> "AI 工作中：输入将排队，结束后自动发送"
                                else -> "输入指令，可附图片；/ 查看命令"
                            }
                        )
                    },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                if (running || suspended) {
                    IconButton(
                        onClick = {
                            if (suspended) {
                                resumeTask()
                            } else {
                                api?.suspendAgent()
                                taskState = taskState.copy(lastStep = "正在暂停：当前步骤结束后保存检查点…")
                                scope.launch { snackbar.showSnackbar("正在暂停，检查点保存后可继续") }
                            }
                        },
                        modifier = Modifier.pressScale(),
                    ) {
                        Icon(
                            if (suspended) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                            contentDescription = if (suspended) "继续任务" else "暂停任务",
                        )
                    }
                }
                FilledIconButton(
                    onClick = { send() },
                    enabled = (input.isNotBlank() || attachments.isNotEmpty()) && !processingImages,
                    modifier = Modifier.pressScale(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (running || suspended) "加入队列" else "发送",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        }
    }
    }

    // 全屏看图（任务 26）：双指缩放 / 双击复位。
    viewingImage?.let { src ->
        ChatImageViewer(src) { viewingImage = null }
    }

    // 授权弹窗：撤销成本分级可视（R12）
    approval?.let { a ->
        var argsExpanded by remember { mutableStateOf(false) }
        val revColor = when (a.reversibility) {
            "reversible" -> YayaColors.StatusSuccess
            "partially_reversible" -> YayaColors.Warning
            else -> YayaColors.StatusError
        }
        val revLabel = when (a.reversibility) {
            "reversible" -> "✓ 可撤销"
            "partially_reversible" -> "△ 半可逆"
            else -> "✗ 不可逆"
        }
        AlertDialog(
            onDismissRequest = { approval = null },
            title = { Text("需要授权") },
            text = {
                Column {
                    Text("Agent 请求执行「${a.tool}」")
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(revColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            revLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = revColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    if (a.args.length > 0) {
                        Text(
                            "参数",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { argsExpanded = !argsExpanded }
                                .padding(vertical = 2.dp),
                        )
                        if (argsExpanded) {
                            Text(
                                a.args,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(6.dp),
                            )
                        } else {
                            Text(
                                a.args.take(80) + if (a.args.length > 80) "…" else "",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    api?.respondApproval(a.id, true)
                    approval = null
                }) {
                    Text("允许", color = if (a.reversibility == "irreversible") YayaColors.StatusError else YayaColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    api?.respondApproval(a.id, false)
                    approval = null
                }) { Text("拒绝") }
            },
        )
    }

    // 会话管理弹层
    if (showSessions) {
        SessionsSheet(
            api = api,
            snackbar = snackbar,
            onDismiss = { showSessions = false },
            onOpen = { sid ->
                showSessions = false
                scope.launch {
                    val raw = withContext(Dispatchers.IO) { api?.loadSession(sid) ?: "" }
                    if (raw.isBlank()) return@launch
                    val data = try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        return@launch
                    }
                    sessionId = data.optString("sessionId", sid)
                    attachments.clear()
                    viewingImage = null
                    queue.clear()
                    suspended = false
                    val messages = data.optJSONArray("messages") ?: return@launch
                    restoreItems(messages)
                    running = false
                    status = ""
                }
            },
            onNew = {
                if (suspended) {
                    // 有暂停任务：创建新会话会丢弃其循环光标，必须显式确认。
                    showSessions = false
                    showDiscardConfirm = true
                } else {
                    showSessions = false
                    newSession()
                }
            },
        )
    }

    // 模型切换弹层（任务 25）：切提供商/模型 + 跳转设置管理
    if (showModels) {
        ModelsSheet(
            onDismiss = { showModels = false },
            onPick = { provider, model ->
                ProviderStore.setActive(context, provider, model)
                activeModel = ActiveModel(provider, model)
                taskState = taskState.copy(model = model.id)
                showModels = false
                scope.launch { snackbar.showSnackbar("已切换到 ${model.id}") }
            },
            onManage = {
                showModels = false
                onOpenSettings()
            },
        )
    }

    // 新建会话前丢弃暂停任务（明确确认，不可恢复循环光标）。
    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("放弃暂停的任务？") },
            text = {
                Text("当前有已暂停的任务。新建会话会丢弃它的检查点，之后无法继续该任务。")
            },
            confirmButton = {
                TextButton(onClick = {
                    api?.clearPendingTask()
                    showDiscardConfirm = false
                    newSession()
                }) { Text("放弃并新建", color = YayaColors.StatusError) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("取消") }
            },
        )
    }

    // 检查点弹层：列出并回滚到指定检查点
    if (showCheckpoints) {
        CheckpointsSheet(
            api = api,
            sessionId = sessionId,
            onDismiss = { showCheckpoints = false },
            onRollback = { cpid ->
                showCheckpoints = false
                scope.launch {
                    val raw = withContext(Dispatchers.IO) {
                        api?.restoreCheckpoint(sessionId, cpid)
                    }
                    if (raw.isNullOrEmpty()) {
                        snackbar.showSnackbar("回滚失败：检查点不存在")
                        return@launch
                    }
                    val messages = try {
                        JSONArray(raw)
                    } catch (_: Exception) {
                        JSONArray()
                    }
                    attachments.clear()
                    viewingImage = null
                    queue.clear()
                    api?.clearPendingTask()
                    suspended = false
                    restoreItems(messages)
                    running = false
                    taskState = TaskCardState(mode = mode, model = activeModel?.model?.id ?: "")
                    snackbar.showSnackbar("已回滚到检查点")
                }
            },
        )
    }
}

@Composable
private fun ChatRow(item: ChatItem, streaming: Boolean, onViewImage: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val isUser = item.role == "user"
    val isMeta = item.role == "tool" || item.role == "policy" || item.role == "system"
    if (isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(12.dp, 2.dp, 12.dp, 12.dp))
                    .background(cs.primary)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.images.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item.images.forEach { path ->
                                ChatImageBlock(
                                    source = path,
                                    onView = onViewImage,
                                    modifier = Modifier.size(112.dp),
                                    thumb = true,
                                )
                            }
                        }
                    }
                    if (item.text.isNotEmpty()) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onPrimary,
                        )
                    }
                }
            }
        }
        return
    }
    if (isMeta) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .width(3.dp)
                    .height(if (item.role == "tool" && !item.ok) 24.dp else 16.dp)
                    .background(
                        when {
                            item.role == "tool" && !item.ok -> cs.error
                            item.role == "policy" -> YayaColors.Primary
                            else -> cs.onSurfaceVariant.copy(alpha = 0.4f)
                        },
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.fillMaxWidth()) {
                if (item.role != "system") {
                    Text(
                        text = if (item.role == "tool") "tool" else "policy",
                        style = MonoLabel,
                        color = if (item.role == "tool" && !item.ok) cs.error else cs.onSurfaceVariant,
                    )
                }
                Text(
                    text = item.text,
                    style = MonoSmall,
                    color = when {
                        item.role == "tool" && !item.ok -> cs.error
                        item.role == "system" -> cs.onSurfaceVariant
                        else -> cs.onSurface
                    },
                )
            }
        }
        return
    }
    YayaCard(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // 流式期间不解析图片：避免半截 URL 触发失败占位与布局跳动。
        val segments = remember(item.text, streaming) {
            if (streaming || item.role != "assistant") {
                listOf(MessageSegment.Text(item.text))
            } else {
                parseMessageSegments(item.text)
            }
        }
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            segments.forEach { seg ->
                when (seg) {
                    is MessageSegment.Text -> Text(
                        seg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                    )
                    is MessageSegment.Image -> ChatImageBlock(
                        source = seg.url,
                        onView = onViewImage,
                    )
                }
            }
            // 任务 30：每次回复的用量明细（输入/输出/缓存/耗时/费用）。
            item.usage?.takeIf { !it.isEmpty() }?.let { u ->
                Text(
                    usageLine(u),
                    style = MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 渲染分组：连续的 tool 消息合并为步骤卡（tool_call 为 header，相邻 tool_result 为结果体）。 */
private sealed interface RenderItem {
    data class Msg(val item: ChatItem) : RenderItem
    data class Step(val steps: List<ChatItem>) : RenderItem
}

private fun groupItems(items: List<ChatItem>): List<RenderItem> {
    val out = mutableListOf<RenderItem>()
    var i = 0
    while (i < items.size) {
        if (items[i].role == "tool") {
            var j = i
            while (j < items.size && items[j].role == "tool") j++
            out.add(RenderItem.Step(items.subList(i, j)))
            i = j
        } else {
            out.add(RenderItem.Msg(items[i]))
            i++
        }
    }
    return out
}

private sealed interface MessageSegment {
    data class Text(val text: String) : MessageSegment
    data class Image(val url: String) : MessageSegment
}

private val markdownImage = Regex("""!\[[^\]]*]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)""")

/** 任务 26：把助手文本切成文本/图片段；代码块内的 `![...](...)` 不当作图片。 */
private fun parseMessageSegments(text: String): List<MessageSegment> {
    if (text.isBlank()) return emptyList()
    val out = mutableListOf<MessageSegment>()
    var index = 0
    while (index < text.length) {
        val fenceStart = text.indexOf("```", index)
        val plainEnd = if (fenceStart < 0) text.length else fenceStart
        appendImageSegments(text.substring(index, plainEnd), out)
        if (fenceStart < 0) break
        val fenceEnd = text.indexOf("```", fenceStart + 3)
        val blockEnd = if (fenceEnd < 0) text.length else (fenceEnd + 3).coerceAtMost(text.length)
        out.add(MessageSegment.Text(text.substring(fenceStart, blockEnd)))
        index = blockEnd
    }
    return out
}

private fun appendImageSegments(chunk: String, out: MutableList<MessageSegment>) {
    var cursor = 0
    markdownImage.findAll(chunk).forEach { m ->
        if (m.range.first > cursor) {
            val t = chunk.substring(cursor, m.range.first)
            if (t.isNotEmpty()) out.add(MessageSegment.Text(t))
        }
        out.add(MessageSegment.Image(m.groupValues[1]))
        cursor = m.range.last + 1
    }
    if (cursor < chunk.length) {
        val t = chunk.substring(cursor)
        if (t.isNotEmpty()) out.add(MessageSegment.Text(t))
    }
}

/** 消息入场：alpha + 轻微上移，按位置 stagger（至多 8 档 × Motion.StaggerStep，只动 transform/opacity）。 */
@Composable
private fun Reveal(id: Long, index: Int, content: @Composable () -> Unit) {
    var shown by remember(id) { mutableStateOf(false) }
    val delay = minOf(index, 8) * Motion.StaggerStep
    LaunchedEffect(id) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(
            durationMillis = Motion.Standard,
            delayMillis = delay,
            easing = Motion.RevealEasing,
        ),
        label = "revealAlpha",
    )
    val shift by animateFloatAsState(
        targetValue = if (shown) 0f else 10f,
        animationSpec = tween(
            durationMillis = Motion.Standard,
            delayMillis = delay,
            easing = Motion.RevealEasing,
        ),
        label = "revealShift",
    )
    Box(Modifier.graphicsLayer {
        this.alpha = alpha
        this.translationY = shift * density
    }) {
        content()
    }
}

/** 工具执行步骤卡：header 显示工具调用，展开显示相邻结果；任一步失败整卡红描边。 */
@Composable
private fun ToolStepCard(steps: List<ChatItem>) {
    val header = steps.first()
    val results = steps.drop(1)
    val failed = steps.any { !it.ok }
    val status = when {
        failed -> "error"
        results.isNotEmpty() -> "success"
        else -> "running"
    }
    var expanded by remember { mutableStateOf(true) }
    val cs = MaterialTheme.colorScheme
    YayaCard(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(status, Modifier.size(8.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "tool",
                        style = MonoLabel,
                        color = if (failed) cs.error else cs.onSurfaceVariant,
                    )
                    Text(
                        header.text,
                        style = MonoSmall,
                        color = if (failed) cs.error else cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (results.isNotEmpty()) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起结果" else "展开结果",
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (expanded && results.isNotEmpty()) {
                Column(Modifier.padding(start = 26.dp, end = 10.dp, bottom = 2.dp)) {
                    results.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                Modifier.padding(top = 3.dp).width(3.dp).height(if (!r.ok) 24.dp else 16.dp)
                                    .background(if (!r.ok) cs.error else YayaColors.StatusSuccess),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                r.text,
                                style = MonoSmall,
                                color = if (!r.ok) cs.error else cs.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 模型切换弹层（任务 25）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsSheet(
    onDismiss: () -> Unit,
    onPick: (ModelProvider, ProviderModel) -> Unit,
    onManage: () -> Unit,
) {
    val context = LocalContext.current
    val providers = remember { ProviderStore.load(context) }
    val active = remember { ProviderStore.active(context, providers) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("切换模型", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onManage) { Text("管理提供商") }
            }
            if (providers.isEmpty()) {
                Text(
                    "尚未配置模型提供商",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                TextButton(onClick = onManage, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("去设置添加")
                }
            }
            providers.forEach { provider ->
                Text(
                    "${provider.name.ifBlank { providerTypeLabel(provider.type) }} · ${providerTypeLabel(provider.type)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                if (provider.models.isEmpty()) {
                    Text(
                        "未添加模型",
                        style = MaterialTheme.typography.labelSmall,
                        color = YayaColors.Warning,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                provider.models.forEach { model ->
                    val picked = provider.id == active?.provider?.id && model.id == active.model.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(provider, model) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = if (picked) "当前模型" else null,
                            tint = if (picked) YayaColors.Primary else YayaColors.Outline,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(
                            Modifier.weight(1f).padding(start = 10.dp),
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
                        if (picked) Tag("当前", YayaColors.Primary)
                    }
                }
            }
        }
    }
}

// ── 会话管理弹层 ──

/** 会话相对时间：1 分钟内"刚刚"，7 天内相对，更早按 MM-dd。 */
private fun formatRelative(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsSheet(
    api: AgentApi?,
    snackbar: androidx.compose.material3.SnackbarHostState,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionMeta>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<SessionMeta?>(null) }
    var confirmDelete by remember { mutableStateOf<SessionMeta?>(null) }

    LaunchedEffect(Unit) {
        sessions = try {
            val arr = JSONArray(api?.listSessions() ?: "[]")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SessionMeta(
                    o.optString("id"),
                    o.optString("title"),
                    o.optLong("updatedAt"),
                    o.optInt("messageCount"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onNew) { Icon(Icons.Filled.Add, contentDescription = "新建会话") }
            }
            sessions.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(s.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title.ifEmpty { "(无标题)" }, maxLines = 1)
                        Text(
                            "${s.count} 条 · ${formatRelative(s.updatedAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renameTarget = s }) {
                        Icon(Icons.Filled.Edit, contentDescription = "重命名")
                    }
                    IconButton(onClick = { confirmDelete = s }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
            }
            if (sessions.isEmpty()) {
                Text(
                    "暂无会话，发一条消息开始",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    renameTarget?.let { s ->
        var title by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    api?.renameSession(s.id, title.trim().ifEmpty { "(无标题)" })
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    // 删除会话确认（R12 可逆性可视：不可逆操作需明确确认）
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除会话？") },
            text = {
                Text("将永久删除「${s.title.ifEmpty { "(无标题)" }}」及其全部消息与检查点，此操作不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        api?.deleteSession(s.id)
                        sessions = sessions.filterNot { it.id == s.id }
                        snackbar.showSnackbar("已删除会话")
                    }
                    confirmDelete = null
                }) {
                    Text("删除", color = YayaColors.StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

// ── 检查点弹层（简化版：列出检查点数并一键回滚到最近一次）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckpointsSheet(
    api: AgentApi?,
    sessionId: String,
    onDismiss: () -> Unit,
    onRollback: (Long) -> Unit,
) {
    var checkpoints by remember { mutableStateOf<JSONArray?>(null) }

    LaunchedEffect(Unit) {
        checkpoints = try {
            JSONArray(api?.checkpoints(sessionId) ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("检查点", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            val count = checkpoints?.length() ?: 0
            if (count == 0) {
                Text(
                    "暂无检查点",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Text(
                    "共 $count 个检查点；点击回滚到该快照",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                checkpoints?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val cp = arr.optJSONObject(i)
                        val cpid = cp.optLong("id")
                        val ts = cp.optLong("createdAt")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onRollback(cpid) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.History,
                                contentDescription = "回滚到此检查点",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "快照 ${i + 1} · ${formatRelative(ts)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}