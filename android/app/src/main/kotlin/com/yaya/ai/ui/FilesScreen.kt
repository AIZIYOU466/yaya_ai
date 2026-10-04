package com.yaya.ai.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 目录树节点：展开/加载/子节点为运行时状态。 */
private class FsNode(val path: String, val isDir: Boolean) {
    var expanded by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var children by mutableStateOf<List<FsNode>>(emptyList())
}

/** 长按菜单触发的对话框规格（由 [dialog] 状态驱动，确认/取消后置 null）。 */
private sealed interface FsDialog {
    data class NewFile(val node: FsNode) : FsDialog
    data class Rename(val node: FsNode) : FsDialog
    data class Delete(val node: FsNode) : FsDialog
}

/** 文件浏览页（ROADMAP 任务 22 迁移）：缩进树形目录，按需展开加载。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(onOpenFile: (String) -> Unit = {}, onOpenGit: () -> Unit = {}) {
    val api = LocalAgentApi.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var rootAbs by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var rootChildren by remember { mutableStateOf<List<FsNode>>(emptyList()) }
    var menuNode by remember { mutableStateOf<FsNode?>(null) }
    var dialog by remember { mutableStateOf<FsDialog?>(null) }

    suspend fun list(path: String): List<FsNode> = withContext(Dispatchers.IO) {
        val raw = api?.workspaceList(path) ?: return@withContext emptyList()
        val m = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return@withContext emptyList()
        }
        if (m.optBoolean("ok").not()) return@withContext emptyList()
        val nodes = mutableListOf<FsNode>()
        for (name in m.optString("content", "").split('\n')) {
            if (name.isEmpty()) continue
            val isDir = name.endsWith('/')
            nodes.add(FsNode(name.substring(0, name.length - if (isDir) 1 else 0), isDir))
        }
        nodes.sortWith(compareBy<FsNode> { !it.isDir }.thenBy { it.path })
        nodes
    }

    suspend fun reload(expanded: Map<String, Boolean>) = withContext(Dispatchers.IO) {
        rootAbs = api?.workspaceRoot() ?: ""
        suspend fun restore(parent: List<FsNode>) {
            for (n in parent) {
                if (n.isDir && expanded[n.path] == true) {
                    n.expanded = true
                    n.children = list(n.path)
                    restore(n.children)
                }
            }
        }
        rootChildren = list("").also { restore(it) }
        loading = false
    }

    LaunchedEffect(Unit) { reload(emptyMap()) }

    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val refresh = { loading = true; scope.launch { reload(collectExpanded(rootChildren)) } }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("文件", style = MaterialTheme.typography.titleMedium)
                if (rootAbs.isNotEmpty()) {
                    Text(
                        rootAbs,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenGit) {
                Icon(Icons.Filled.AccountTree, contentDescription = "Git 版本管理")
            }
            IconButton(onClick = { if (!loading) refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }
        HorizontalDivider()

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            rootChildren.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("工作区为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "长按任意处，或让 AI 在此创建文件",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 4.dp, bottom = 32.dp)) {
                items(rootChildren) { n ->
                    TreeNode(
                        n,
                        0,
                        onTap = {
                            if (it.isDir) {
                                if (it.children.isNotEmpty()) {
                                    it.expanded = !it.expanded
                                } else {
                                    it.loading = true
                                    it.expanded = true
                                    scope.launch {
                                        it.children = list(it.path)
                                        it.loading = false
                                    }
                                }
                            } else {
                                onOpenFile(it.path)
                            }
                        },
                        onLongPress = { menuNode = it },
                    )
                }
            }
        }
    }

    // 长按操作菜单
    menuNode?.let { node ->
        ModalBottomSheet(onDismissRequest = { menuNode = null }) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    node.path,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                if (node.isDir) {
                    ActionItem("新建文件") { menuNode = null; dialog = FsDialog.NewFile(node) }
                }
                if (!node.isDir) {
                    ActionItem("重命名") { menuNode = null; dialog = FsDialog.Rename(node) }
                }
                ActionItem("删除") { menuNode = null; dialog = FsDialog.Delete(node) }
            }
        }
    }

    // 对话框（确认后执行对应操作）
    dialog?.let { d ->
        val node = when (d) {
            is FsDialog.NewFile -> d.node
            is FsDialog.Rename -> d.node
            is FsDialog.Delete -> d.node
        }
        when (d) {
            is FsDialog.NewFile -> TextPromptDialog(
                title = "新建文件",
                label = "文件名（可含路径，如 src/main.dart）",
                initial = "",
                onDismiss = { dialog = null },
                onConfirm = { name ->
                    dialog = null
                    if (name.isNotEmpty()) {
                        val rel = if (node.path.isEmpty()) name else "${node.path}/$name"
                        if (rel.contains("..") || rel.startsWith('/')) {
                            toast("路径非法：$name")
                        } else {
                            scope.launch {
                                val res = api?.workspaceWrite(rel, "", true) ?: ""
                                toast(if (jsonOk(res)) "已创建 $name" else "创建失败")
                                refresh()
                            }
                        }
                    }
                },
            )
            is FsDialog.Rename -> TextPromptDialog(
                title = "重命名",
                label = "新文件名（同目录内）",
                initial = node.path.substringAfterLast('/'),
                onDismiss = { dialog = null },
                onConfirm = { newName ->
                    dialog = null
                    if (newName.isNotEmpty() && newName != node.path.substringAfterLast('/')) {
                        if (newName.contains('/') || newName.contains('\\') || newName.contains("..")) {
                            toast("文件名非法：$newName")
                        } else {
                            scope.launch {
                                val old = node.path
                                val dir = if (old.contains('/')) {
                                    old.substring(0, old.lastIndexOf('/'))
                                } else {
                                    ""
                                }
                                val newPath = if (dir.isEmpty()) newName else "$dir/$newName"
                                // WorkspaceFileAccess 无 rename：读旧→写新→删旧。
                                val r = api?.workspaceRead(old) ?: ""
                                val m = try {
                                    JSONObject(r)
                                } catch (_: Exception) {
                                    null
                                }
                                if (m != null && m.optBoolean("ok")) {
                                    val w = api?.workspaceWrite(newPath, m.optString("content", ""), true) ?: ""
                                    if (jsonOk(w)) {
                                        api?.workspaceDelete(old)
                                        toast("已重命名")
                                    } else {
                                        toast("重命名失败")
                                    }
                                } else {
                                    toast("读取失败")
                                }
                                refresh()
                            }
                        }
                    }
                },
            )
            is FsDialog.Delete -> AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("删除") },
                text = { Text("确定删除 ${node.path}？此操作不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        scope.launch {
                            val res = api?.workspaceDelete(node.path) ?: ""
                            toast(if (jsonOk(res)) "已删除 ${node.path}" else "删除失败")
                            refresh()
                        }
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { dialog = null }) { Text("取消") }
                },
            )
        }
    }
}

private fun jsonOk(raw: String): Boolean = try {
    JSONObject(raw).optBoolean("ok")
} catch (_: Exception) {
    false
}

private fun collectExpanded(nodes: List<FsNode>): Map<String, Boolean> {
    val m = mutableMapOf<String, Boolean>()
    fun walk(list: List<FsNode>) {
        for (n in list) {
            if (n.expanded) m[n.path] = true
            walk(n.children)
        }
    }
    walk(nodes)
    return m
}

@Composable
private fun ActionItem(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(label)
    }
}

/** 单输入框对话框（新建/重命名共用），确认/取消后调用回调并自行消失。 */
@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeNode(
    node: FsNode,
    depth: Int,
    onTap: (FsNode) -> Unit,
    onLongPress: (FsNode) -> Unit,
) {
    val indent = 8.dp + (depth * 16).dp
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { onTap(node) }, onLongClick = { onLongPress(node) })
                .padding(start = indent, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(20.dp)) {
                if (node.isDir) {
                    Icon(
                        if (node.expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            NodeIcon(node)
            Spacer(Modifier.width(8.dp))
            Text(
                node.path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = if (node.isDir) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (node.expanded && node.isDir) {
            when {
                node.loading -> Row(
                    Modifier.padding(start = indent + 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                node.children.isEmpty() -> Text(
                    "（空目录）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = indent + 28.dp, bottom = 8.dp),
                )
                else -> node.children.forEach { c ->
                    TreeNode(c, depth + 1, onTap, onLongPress)
                }
            }
        }
    }
}

@Composable
private fun NodeIcon(node: FsNode) {
    val lang = SyntaxHighlighter.langFromPath(node.path)
    val (icon, color) = if (node.isDir) {
        (if (node.expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder) to Color(0xFF42A5F5)
    } else {
        when (lang) {
            "markdown" -> Icons.Filled.Article to Color(0xFF42A5F5)
            "json" -> Icons.Filled.DataObject to Color(0xFFFFCA28)
            "shell" -> Icons.Filled.Terminal to Color(0xFF66BB6A)
            "dart" -> Icons.Filled.Code to Color(0xFF42A5F5)
            "kotlin" -> Icons.Filled.Code to Color(0xFF26A69A)
            "java" -> Icons.Filled.Code to Color(0xFFFF7043)
            "js" -> Icons.Filled.Code to Color(0xFFFFCA28)
            "rust" -> Icons.Filled.Code to Color(0xFFEF5350)
            "c" -> Icons.Filled.Code to Color(0xFF78909C)
            "yaml" -> Icons.Filled.Code to Color(0xFFEC407A)
            "xml" -> Icons.Filled.Code to Color(0xFFFF7043)
            "python" -> Icons.Filled.Code to Color(0xFF9575CD)
            else -> Icons.Filled.Description to Color(0xFF90A4AE)
        }
    }
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
}