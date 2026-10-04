package com.yaya.ai.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** MCP 服务器配置（对应 providers.dart 的 MCPServerInfo）。 */
data class MCPServer(
    val name: String,
    val type: String,
    val enabled: Boolean,
    val url: String?,
    val command: String?,
    val args: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("type", type)
        put("enabled", enabled)
        if (url != null) put("url", url)
        if (command != null) put("command", command)
        put("args", JSONArray(args))
    }

    companion object {
        fun fromJson(o: JSONObject): MCPServer {
            val argsArr = o.optJSONArray("args")
            val args = if (argsArr == null) {
                emptyList()
            } else {
                (0 until argsArr.length()).map { argsArr.optString(it) }
            }
            return MCPServer(
                name = o.optString("name"),
                type = o.optString("type", "stdio"),
                enabled = o.optBoolean("enabled", false),
                url = o.optString("url").ifEmpty { null },
                command = o.optString("command").ifEmpty { null },
                args = args,
            )
        }
    }
}

private const val KEY_MCP = "flutter.mcp_servers"

/** MCP 服务器管理页（ROADMAP 任务 10 迁移）：列表 + 增删改 + 启用开关。
 *  配置持久化在 FlutterSharedPreferences（与 Dart 时代同文件同前缀，数据兼容）。 */
@Composable
fun McpScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val prefs = remember { context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE) }
    var servers by remember { mutableStateOf<List<MCPServer>>(emptyList()) }
    var editing by remember { mutableStateOf<MCPServer?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MCPServer?>(null) }

    fun persist(list: List<MCPServer>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_MCP, arr.toString()).apply()
        servers = list
    }

    LaunchedEffect(Unit) {
        servers = try {
            val raw = prefs.getString(KEY_MCP, null) ?: return@LaunchedEffect
            val arr = JSONArray(raw)
            (0 until arr.length()).map { MCPServer.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            servers.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = 12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("还没有 MCP 服务器", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(
                    onClick = { showAdd = true },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加第一个服务器", modifier = Modifier.padding(start = 4.dp))
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(servers, key = { it.name }) { s ->
                    McpCard(s, onToggle = {
                        persist(servers.map { if (it.name == s.name) it.copy(enabled = !it.enabled) else it })
                    }, onEdit = { editing = s }, onDelete = { deleting = s })
                }
            }
        }
    }

    // 新增/编辑对话框
    if (showAdd || editing != null) {
        McpEditDialog(
            existing = editing,
            onDismiss = { showAdd = false; editing = null },
            onSave = { s ->
                showAdd = false
                editing = null
                val exists = servers.any { it.name == s.name }
                persist(if (exists) servers.map { if (it.name == s.name) s else it } else servers + s)
                scope.launch { snackbar.showSnackbar(if (exists) "已保存 ${s.name}" else "已添加服务器 ${s.name}") }
            },
        )
    }

    // 删除确认
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除 \"${s.name}\" 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    persist(servers.filterNot { it.name == s.name })
                    scope.launch { snackbar.showSnackbar("已删除 ${s.name}") }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun McpCard(
    server: MCPServer,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(8.dp),
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                server.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = server.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "删除")
            }
        }
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Text(
                if (server.type == "stdio") "类型: stdio" else "类型: ${server.type}（暂不支持）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (server.command != null) {
                Text(
                    "命令: ${server.command} ${server.args.joinToString(" ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (server.url != null) {
                Text(
                    "URL: ${server.url}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun Modifier.cardBorder(): Modifier = this

@Composable
private fun McpEditDialog(
    existing: MCPServer?,
    onDismiss: () -> Unit,
    onSave: (MCPServer) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: "stdio") }
    var command by remember { mutableStateOf(existing?.command ?: "") }
    var args by remember { mutableStateOf(existing?.args?.joinToString(" ") ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增 MCP 服务器" else "编辑服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（不得包含 __）") },
                    singleLine = true,
                )
                TypeDropdown(type = type, onType = { type = it })
                if (type == "stdio") {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("启动命令（如 npx）") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = args,
                        onValueChange = { args = it },
                        label = { Text("启动参数（空格分隔）") },
                        singleLine = true,
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        singleLine = true,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                if (trimmed.isNotEmpty()) {
                    onSave(
                        MCPServer(
                            name = trimmed,
                            type = type,
                            enabled = enabled,
                            command = if (type == "stdio" && command.trim().isNotEmpty()) command.trim() else null,
                            url = if (type == "http" && url.trim().isNotEmpty()) url.trim() else null,
                            args = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                        )
                    )
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(type: String, onType: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.material3.ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = type,
            onValueChange = {},
            readOnly = true,
            label = { Text("类型") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("stdio（本机命令）") }, onClick = { onType("stdio"); expanded = false })
            DropdownMenuItem(text = { Text("http（暂不支持）") }, onClick = { onType("http"); expanded = false })
        }
    }
}