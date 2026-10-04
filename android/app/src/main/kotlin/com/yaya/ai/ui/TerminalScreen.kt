package com.yaya.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val api = LocalAgentApi.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var profiles by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var currentId by remember { mutableStateOf("alpine") }
    var selectedId by remember { mutableStateOf("alpine") }
    var installing by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val output = remember { mutableStateListOf<String>() }
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var cmdJob by remember { mutableStateOf<Job?>(null) }

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

    // 新输出行时自动滚动到底
    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            listState.animateScrollToItem(output.lastIndex)
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

    Column(Modifier.fillMaxSize()) {
        // 镜像管理条
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
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

        // 终端输出区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF000000))
                .padding(8.dp),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(output) { _, line ->
                    Text(
                        line,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    )
                }
            }
        }
        HorizontalDivider()

        // 命令输入
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = { Text("输入命令...") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = isRunning,
            )
            IconButton(
                onClick = {
                    val cmd = command.trim()
                    if (cmd.isNotEmpty() && isRunning) {
                        output.add("\$ $cmd")
                        command = ""
                        cmdJob?.cancel()
                        cmdJob = scope.launch {
                            api?.executeCommand(cmd)?.collect { line -> output.add(line) }
                        }
                    }
                },
                enabled = isRunning,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "执行")
            }
        }
    }

    // 容器启停（浮动在右上）
    Box(Modifier.fillMaxSize()) {
        IconButton(
            onClick = {
                if (isRunning) {
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