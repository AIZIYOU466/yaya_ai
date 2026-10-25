package com.yaya.ai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yaya.ai.TerminalLog
import com.yaya.ai.ui.components.pressScale
import com.yaya.ai.ui.theme.MonoSmall
import com.yaya.ai.ui.theme.YayaColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 一个用户终端标签：独立的回滚缓冲与命令历史（同一容器串行执行）。 */
private class TerminalTab(val id: Long, var title: String) {
    val lines = mutableStateListOf<String>()
    val history = mutableStateListOf<String>()
}

private const val KEY_FONT_SIZE = "flutter.terminal_font_size"
private const val KEY_INK = "flutter.terminal_ink"
private const val MAX_TABS = 5

/** 终端配色（文字色）预设：浅色背景下的三档墨色。 */
private val TERMINAL_INKS = listOf(
    "墨黑" to 0xFF0F172A,
    "暗绿" to 0xFF065F46,
    "深蓝" to 0xFF1E3A8A,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val api = LocalAgentApi.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val prefs = remember {
        context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
    }

    var profiles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var currentId by remember { mutableStateOf("alpine") }
    var selectedId by remember { mutableStateOf("alpine") }
    var installing by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // ── 多标签（任务 33）──
    var tabSeq by remember { mutableStateOf(1L) }
    val tabs = remember { mutableStateListOf(TerminalTab(tabSeq, "终端 1")) }
    var activeId by remember { mutableStateOf(1L) }
    var aiSelected by remember { mutableStateOf(false) }
    var busyTabId by remember { mutableStateOf<Long?>(null) }
    var cmdJob by remember { mutableStateOf<Job?>(null) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var historyIndex by remember { mutableStateOf(-1) }

    // 字号 / 配色（持久化）
    var fontSize by remember {
        mutableStateOf(prefs.getFloat(KEY_FONT_SIZE, 12f).coerceIn(9f, 22f))
    }
    var inkIndex by remember {
        mutableStateOf(prefs.getInt(KEY_INK, 0).coerceIn(0, TERMINAL_INKS.lastIndex))
    }
    val ink = Color(TERMINAL_INKS[inkIndex].second)

    val uiListState = rememberLazyListState()
    val aiListState = rememberLazyListState()

    // AI 标签数据（TerminalLog 线程安全，UI 只订阅版本号取快照）
    val aiVersion by TerminalLog.version.collectAsState()
    val aiEntries = remember(aiVersion) { TerminalLog.snapshot() }
    val aiRunning = aiEntries.any { it.running }

    val activeTab = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    suspend fun loadProfiles() {
        currentId = api?.currentRootfs() ?: "alpine"
        selectedId = currentId
        profiles = try {
            val arr = org.json.JSONArray(api?.rootfsProfiles() ?: "[]")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    LaunchedEffect(Unit) { loadProfiles() }

    // 新输出自动滚到底（UI 标签与 AI 标签各自独立）
    LaunchedEffect(aiSelected, activeId, activeTab?.lines?.size, aiEntries.size) {
        if (aiSelected) {
            if (aiEntries.isNotEmpty()) aiListState.animateScrollToItem(aiEntries.lastIndex)
        } else {
            val size = activeTab?.lines?.size ?: 0
            if (size > 0) uiListState.animateScrollToItem(size - 1)
        }
    }

    val profileLabel: (JSONObject) -> String = { p ->
        val id = p.optString("id")
        val installed = p.optBoolean("installed", false)
        val suffix = if (id == currentId) " · 当前" else if (installed) " · 已装" else " · 未装"
        "${p.optString("name")}$suffix"
    }
    fun isSelectedInstalled(): Boolean =
        profiles.any { it.optString("id") == selectedId && it.optBoolean("installed", false) }

    // ── 输入辅助 ──
    fun insertText(text: String) {
        val t = input.text
        val start = input.selection.start.coerceIn(0, t.length)
        val end = input.selection.end.coerceIn(0, t.length)
        input = TextFieldValue(t.replaceRange(start, end, text), TextRange(start + text.length))
    }

    fun moveCaret(delta: Int) {
        val t = input.text
        val pos = (input.selection.start + delta).coerceIn(0, t.length)
        input = input.copy(selection = TextRange(pos))
    }

    fun recallHistory(delta: Int) {
        val history = activeTab?.history ?: return
        if (history.isEmpty()) return
        val next = (historyIndex + delta).let { if (it < 0) history.lastIndex else it }
            .coerceIn(0, history.lastIndex)
        historyIndex = if (historyIndex == history.lastIndex && delta > 0) -1 else next
        val text = if (historyIndex < 0) "" else history[historyIndex]
        input = TextFieldValue(text, TextRange(text.length))
    }

    fun interrupt() {
        cmdJob?.cancel()
        cmdJob = null
        busyTabId?.let { id -> tabs.firstOrNull { it.id == id }?.lines?.add("^C") }
        busyTabId = null
    }

    fun runCommand() {
        val cmd = input.text.trim()
        val tab = activeTab ?: return
        if (cmd.isEmpty() || !isRunning || busyTabId != null) return
        tab.lines.add("$ $cmd")
        if (tab.history.lastOrNull() != cmd) tab.history.add(cmd)
        historyIndex = -1
        input = TextFieldValue("")
        val tabId = tab.id
        busyTabId = tabId
        cmdJob = scope.launch {
            try {
                api?.executeCommand(cmd)?.collect { line ->
                    tabs.firstOrNull { it.id == tabId }?.lines?.add(line)
                }
            } finally {
                if (busyTabId == tabId) busyTabId = null
            }
        }
    }

    fun addTab() {
        if (tabs.size >= MAX_TABS) {
            scope.launch { snackbar.showSnackbar("最多 $MAX_TABS 个标签") }
            return
        }
        tabSeq += 1
        val tab = TerminalTab(tabSeq, "终端 ${tabs.size + 1}")
        tabs.add(tab)
        activeId = tab.id
        aiSelected = false
    }

    fun closeTab(tab: TerminalTab) {
        if (tabs.size <= 1) return
        if (busyTabId == tab.id) interrupt()
        tabs.remove(tab)
        if (activeId == tab.id) activeId = tabs.first().id
    }

    Column(Modifier.fillMaxSize()) {
        // 镜像管理条（顶部避让状态栏，背景仍铺满到状态栏区域）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AddBox, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    "Linux 环境（镜像）",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                if (!installing) {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.AddBox, contentDescription = "自定义镜像")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImageDropdown(
                    profiles = profiles,
                    selectedId = selectedId,
                    label = { profileLabel(it) },
                    enabled = !installing,
                    onSelect = { selectedId = it },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        if (isSelectedInstalled()) {
                            scope.launch {
                                api?.setCurrentRootfs(selectedId)
                                loadProfiles()
                                snackbar.showSnackbar("已切换到：$selectedId")
                            }
                        } else {
                            scope.launch {
                                installing = true
                                val msg = api?.installRootfs(selectedId) ?: "未知结果"
                                installing = false
                                loadProfiles()
                                snackbar.showSnackbar(msg)
                            }
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (isSelectedInstalled()) "使用" else "安装")
                }
                IconButton(
                    onClick = { showResetConfirm = true },
                    enabled = isSelectedInstalled() && !installing,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重置镜像")
                }
            }
            if (installing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "正在下载并安装「$selectedId」...",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        HorizontalDivider()

        // 标签栏：用户终端标签 + 只读「AI」标签（agent 的 terminal_exec 记录）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEach { tab ->
                TabChip(
                    title = tab.title,
                    selected = !aiSelected && tab.id == activeId,
                    running = busyTabId == tab.id,
                    onClick = {
                        aiSelected = false
                        activeId = tab.id
                        historyIndex = -1
                    },
                    onClose = if (tabs.size > 1) ({ closeTab(tab) }) else null,
                )
            }
            TabChip(
                title = if (aiRunning) "AI ·" else "AI",
                selected = aiSelected,
                running = aiRunning,
                onClick = { aiSelected = true },
                onClose = null,
            )
            IconButton(onClick = { addTab() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "新建终端标签", modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider()

        // 输出区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(YayaColors.Background)
                .padding(8.dp),
        ) {
            if (aiSelected) {
                if (aiEntries.isEmpty()) {
                    Text(
                        "AI 还没有在本容器执行过命令。模型调用 terminal_exec 时会出现在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "模型执行的命令（只读）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { TerminalLog.clear() }) { Text("清空") }
                        }
                        LazyColumn(state = aiListState, modifier = Modifier.fillMaxSize()) {
                            items(aiEntries, key = { it.id }) { entry ->
                                Column(Modifier.padding(bottom = 10.dp)) {
                                    Text(
                                        "$ ${entry.command}",
                                        style = MonoSmall.copy(fontSize = fontSize.sp),
                                        color = YayaColors.Primary,
                                    )
                                    entry.lines.forEach { line ->
                                        Text(
                                            line,
                                            style = MonoSmall.copy(fontSize = fontSize.sp),
                                            color = ink,
                                        )
                                    }
                                    Text(
                                        when {
                                            entry.running -> "运行中…"
                                            entry.exitCode != null -> "退出码 ${entry.exitCode}"
                                            else -> "已超时或被中断（无退出码）"
                                        },
                                        style = MonoSmall.copy(fontSize = (fontSize - 2).coerceAtLeast(9f).sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                val lines = activeTab?.lines ?: emptyList()
                if (lines.isEmpty()) {
                    Text(
                        if (isRunning) "输入命令并回车执行。" else "容器未启动：点右上角 ▶ 启动 Linux 环境。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(state = uiListState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                line,
                                style = MonoSmall.copy(fontSize = fontSize.sp),
                                color = ink,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider()

        // 辅助按键栏（任务 33）：Esc/Tab/方向键/常用符号 + Ctrl-C + 字号/配色
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            AuxKey("Esc") { insertText("\u001b") }
            AuxKey("Tab") { insertText("\t") }
            AuxKey("Ctrl-C", enabled = busyTabId != null) { interrupt() }
            AuxKey("↑") { recallHistory(-1) }
            AuxKey("↓") { recallHistory(1) }
            AuxKey("←") { moveCaret(-1) }
            AuxKey("→") { moveCaret(1) }
            AuxKey("|") { insertText("|") }
            AuxKey("/") { insertText("/") }
            AuxKey("~") { insertText("~") }
            AuxKey("-") { insertText("-") }
            AuxKey("_") { insertText("_") }
            AuxKey("A-") {
                fontSize = (fontSize - 1f).coerceAtLeast(9f)
                prefs.edit().putFloat(KEY_FONT_SIZE, fontSize).apply()
            }
            AuxKey("A+") {
                fontSize = (fontSize + 1f).coerceAtMost(22f)
                prefs.edit().putFloat(KEY_FONT_SIZE, fontSize).apply()
            }
            AuxKey(TERMINAL_INKS[inkIndex].first) {
                inkIndex = (inkIndex + 1) % TERMINAL_INKS.size
                prefs.edit().putInt(KEY_INK, inkIndex).apply()
            }
        }

        // 命令输入区：与页面背景同色（手势行区域不出现异色带），输入框白色卡片贴底
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Row(
                // 内容避开手势栏保证输入框完整露出；Surface 背景仍铺满到手势栏下（与页面同色）
                modifier = Modifier.fillMaxWidth()
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
                    .padding(8.dp).imePadding(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(if (aiSelected) "切到终端标签后可输入命令" else "输入命令...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = isRunning && !aiSelected && busyTabId == null,
                )
                IconButton(
                    onClick = { runCommand() },
                    enabled = isRunning && !aiSelected && busyTabId == null,
                    modifier = Modifier.pressScale(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "执行")
                }
            }
        }
    }

    // 容器启停（浮动在右上）
    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = {
                if (isRunning) {
                    interrupt()
                    scope.launch { api?.stopContainer() }
                    isRunning = false
                } else {
                    scope.launch {
                        val ok = api?.startContainer() == true
                        isRunning = ok
                        if (!ok) {
                            val err = api?.containerError()
                            snackbar.showSnackbar("启动失败：${err ?: "未知原因"}")
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        ) {
            Icon(
                if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = if (isRunning) "停止容器" else "启动容器",
            )
        }
    }

    // 自定义镜像对话框
    if (showAddDialog) {
        AddRootfsDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, sha ->
                showAddDialog = false
                scope.launch {
                    val id = api?.addRootfsProfile(name, url, sha) ?: "添加失败"
                    loadProfiles()
                    snackbar.showSnackbar("已添加：$id")
                }
            },
        )
    }

    // 重置确认
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("重置镜像") },
            text = { Text("删除「$selectedId」的 rootfs 并清空数据，确认吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    scope.launch {
                        val msg = api?.resetRootfs(selectedId) ?: "未知结果"
                        loadProfiles()
                        snackbar.showSnackbar(msg)
                    }
                }) { Text("重置") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 标签：选中态高亮，运行中带点位，可关闭。 */
@Composable
private fun TabChip(
    title: String,
    selected: Boolean,
    running: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) YayaColors.PrimaryContainer
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            )
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = if (onClose != null) 2.dp else 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (running && !title.endsWith("·")) "$title ·" else title,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) YayaColors.OnPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onClose?.let { close ->
            IconButton(onClick = close, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "关闭标签", modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 辅助按键：小号文本按钮（保持终端区紧凑）。 */
@Composable
private fun AuxKey(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MonoSmall.copy(fontSize = 12.sp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageDropdown(
    profiles: List<JSONObject>,
    selectedId: String,
    label: (JSONObject) -> String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = profiles.firstOrNull { it.optString("id") == selectedId }?.let(label) ?: selectedId,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("镜像") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { p ->
                DropdownMenuItem(
                    text = { Text(label(p), maxLines = 1) },
                    onClick = {
                        onSelect(p.optString("id"))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AddRootfsDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, sha: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义镜像") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("rootfs 下载 URL（tar.gz）") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = sha,
                    onValueChange = { sha = it },
                    label = { Text("SHA256（可选）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = name.trim()
                val u = url.trim()
                if (n.isNotEmpty() && u.isNotEmpty()) onAdd(n, u, sha.trim())
            }) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
