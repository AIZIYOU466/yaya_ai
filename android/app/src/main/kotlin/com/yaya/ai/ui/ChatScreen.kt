package com.yaya.ai.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaya.ai.AgentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class ChatItem(val role: String, val text: String, val ok: Boolean)
private data class ApprovalReq(val id: String, val tool: String, val args: String, val reversibility: String)
private data class SessionMeta(val id: String, val title: String, val updatedAt: Long, val count: Int)

/** 聊天页（P3 迁移自 chat_screen.dart）：Agent 事件流 + 消息渲染 + 会话管理。 */
@Composable
fun ChatScreen() {
    val api = LocalAgentApi.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE) }
    val snackbar = LocalSnackbar.current

    val items = remember { mutableStateListOf<ChatItem>() }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var sessionId by remember { mutableStateOf("") }
    var historyCount by remember { mutableStateOf(0) }
    var totalTokens by remember { mutableStateOf(0) }
    var pendingTokens by remember { mutableStateOf("") }
    var approval by remember { mutableStateOf<ApprovalReq?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var showCheckpoints by remember { mutableStateOf(false) }
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

    fun flushTokens() {
        if (pendingTokens.isEmpty()) return
        if (items.isNotEmpty() && items.size > historyCount && items.last().role == "assistant") {
            val last = items.removeAt(items.lastIndex)
            items.add(ChatItem("assistant", last.text + pendingTokens, true))
        } else {
            items.add(ChatItem("assistant", pendingTokens, true))
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
            "state" -> status = ev.optString("state")
            "usage" -> totalTokens += ev.optLong("total_tokens").toInt()
            "done" -> {
                flushTokens()
                running = false
                status = "done"
            }
            "error" -> {
                flushTokens()
                running = false
                appendSystem("错误: ${ev.optString("message")}")
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

    fun readConfig(): JSONObject {
        fun read(key: String, def: String = "") = prefs.getString(key, def) ?: def
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
        return JSONObject().apply {
            put("baseUrl", read("flutter.ai_base_url"))
            put("apiKey", read("flutter.ai_api_key"))
            put("model", read("flutter.ai_model_name"))
            put("modelPath", read("flutter.ai_model_path"))
            put("localAvailable", api?.localAvailable() == true)
            put("networkOk", api?.networkAvailable() == true)
            put("maxSteps", 12)
            put("mode", read("flutter.agent_mode", "build"))
            put("mcpServers", mcp)
        }
    }

    fun send() {
        val message = input.trim()
        if (message.isEmpty() || running) return
        running = true
        status = "starting"
        items.add(ChatItem("user", message, true))
        input = ""
        refreshScroll()

        if (sessionId.isEmpty()) sessionId = System.currentTimeMillis().toString()
        val config = readConfig()
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                api?.startAgent(sessionId, message, config.toString()) == true
            }
            if (!started) {
                appendSystem("启动失败：Rust Core 未就绪")
                running = false
            }
        }
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
            items.clear()
            for (i in 0 until messages.length()) {
                val m = messages.getJSONObject(i)
                val role = m.optString("role")
                if (role == "usage") continue
                val text = m.optString("text")
                if (role == "policy") {
                    items.add(ChatItem("policy", text, true))
                    continue
                }
                if (text.isEmpty()) continue
                if (role == "assistant" && items.isNotEmpty() && items.last().role == "assistant") {
                    val last = items.removeAt(items.lastIndex)
                    items.add(ChatItem("assistant", last.text + text, true))
                } else {
                    items.add(ChatItem(role, text, m.optBoolean("ok", true)))
                }
            }
            historyCount = items.size
        }
    }

    LaunchedEffect(Unit) { restoreRecent() }

    // ── UI ──

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (running) return@IconButton
                showSessions = true
            }) {
                Icon(Icons.Filled.SwitchAccount, contentDescription = "会话")
            }
            Text(
                if (running) "运行中（$status）" else if (status == "done") "完成" else "就绪",
                style = MaterialTheme.typography.bodySmall,
                color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (totalTokens > 0) {
                Text(
                    "$totalTokens tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { if (!running) showCheckpoints = true }) {
                Icon(Icons.Filled.History, contentDescription = "检查点")
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "输入指令，如「运行测试」「查看项目结构」「写一段代码」",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items.size) { i -> ChatRow(items[i]) }
            }
        }

        // 输入行
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("输入指令...") },
                maxLines = 4,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { if (running) { scope.launch { api?.stopAgent() } ; running = false } else send() },
                enabled = input.isNotBlank() || running,
            ) {
                Icon(
                    if (running) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (running) "停止" else "发送",
                )
            }
        }
    }

    // 授权弹窗
    approval?.let { a ->
        AlertDialog(
            onDismissRequest = { approval = null },
            title = { Text("需要授权") },
            text = {
                Column {
                    Text("Agent 请求执行「${a.tool}」")
                    Spacer(Modifier.width(0.dp))
                    Text(
                        "撤销成本：${a.reversibility}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "参数：${a.args}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    api?.respondApproval(a.id, true)
                    approval = null
                }) { Text("允许") }
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
                    items.clear()
                    val messages = data.optJSONArray("messages") ?: return@launch
                    for (i in 0 until messages.length()) {
                        val m = messages.getJSONObject(i)
                        val role = m.optString("role")
                        if (role == "usage") continue
                        val text = m.optString("text")
                        if (role == "policy") {
                            items.add(ChatItem("policy", text, true))
                            continue
                        }
                        if (text.isEmpty()) continue
                        items.add(ChatItem(role, text, m.optBoolean("ok", true)))
                    }
                    historyCount = items.size
                    running = false
                    status = ""
                }
            },
            onNew = {
                showSessions = false
                items.clear()
                sessionId = System.currentTimeMillis().toString()
                historyCount = 0
                totalTokens = 0
                status = ""
            },
        )
    }

    // 检查点弹层（简化：列出并回滚）
    if (showCheckpoints) {
        CheckpointsSheet(
            api = api,
            sessionId = sessionId,
            onDismiss = { showCheckpoints = false },
            onRollback = { sid ->
                showCheckpoints = false
                scope.launch {
                    val raw = withContext(Dispatchers.IO) { api?.loadSession(sid) ?: "" }
                    val data = try {
                        JSONObject(raw)
                    } catch (_: Exception) {
                        null
                    }
                    if (data != null) {
                        items.clear()
                        val messages = data.optJSONArray("messages") ?: return@launch
                        for (i in 0 until messages.length()) {
                            val m = messages.getJSONObject(i)
                            val role = m.optString("role")
                            if (role == "usage") continue
                            val text = m.optString("text")
                            if (text.isEmpty()) continue
                            if (role == "policy") {
                                items.add(ChatItem("policy", text, true))
                                continue
                            }
                            items.add(ChatItem(role, text, m.optBoolean("ok", true)))
                        }
                        historyCount = items.size
                        snackbar.showSnackbar("已回滚到会话快照")
                    }
                }
            },
        )
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    val cs = MaterialTheme.colorScheme
    val isUser = item.role == "user"
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Text(
            item.text,
            style = if (isUser) MaterialTheme.typography.bodyMedium else if (item.role == "tool" || item.role == "system" || item.role == "policy") MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = when {
                isUser -> cs.onPrimaryContainer
                item.role == "tool" && !item.ok -> cs.error
                item.role == "system" -> cs.onSurfaceVariant
                else -> cs.onSurface
            },
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 1f)
                .padding(vertical = 2.dp),
        )
    }
}

// ── 会话管理弹层 ──

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
                            "${s.count} 条 · ${s.updatedAt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { renameTarget = s }) {
                        Icon(Icons.Filled.Edit, contentDescription = "重命名")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            api?.deleteSession(s.id)
                            sessions = sessions.filterNot { it.id == s.id }
                            snackbar.showSnackbar("已删除会话")
                        }
                    }) {
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
}

// ── 检查点弹层（简化版：列出检查点数并一键回滚到最近一次）──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckpointsSheet(
    api: AgentApi?,
    sessionId: String,
    onDismiss: () -> Unit,
    onRollback: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
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
                    "共 $count 个检查点；回滚将恢复最近一次快照",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                TextButton(
                    onClick = { onRollback(sessionId) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("回滚到最近检查点") }
            }
        }
    }
}