package com.yaya.ai.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class Mode { Edit, Preview }

private const val UNDO_LIMIT = 100

/** 代码编辑器（ROADMAP 任务 22 迁移）：编辑/预览双模式 + undo/redo + 快捷符号栏。 */
@Composable
fun EditorScreen(path: String, onClose: () -> Unit) {
    val api = LocalAgentApi.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val lang = remember(path) { SyntaxHighlighter.langFromPath(path) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var savedText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(if (lang == "markdown") Mode.Preview else Mode.Edit) }
    var saving by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val undoStack = remember { mutableStateListOf<String>() }
    val redoStack = remember { mutableStateListOf<String>() }
    var confirmClose by remember { mutableStateOf(false) }
    var closeChoice by remember { mutableStateOf<Int?>(null) } // 1=直接退出 2=保存并退出

    val dirtyText = "${path.substringAfterLast('/')}${if (dirty) "  ·  未保存" else ""}${if (mode == Mode.Preview) "  ·  预览" else ""}"

    LaunchedEffect(Unit) {
        val raw = withContext(Dispatchers.IO) { api?.workspaceRead(path) ?: "{}" }
        val m = try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
        loading = false
        if (m == null || m.optBoolean("ok").not()) {
            error = m?.optString("message", "无法读取文件") ?: "无法读取文件"
        } else {
            text = m.optString("content", "")
            savedText = text
        }
    }

    fun pushUndo(prev: String) {
        if (undoStack.isEmpty() || undoStack.last() != prev) {
            undoStack.add(prev)
            if (undoStack.size > UNDO_LIMIT) undoStack.removeAt(0)
        }
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(text)
        text = undoStack.removeAt(undoStack.lastIndex)
        dirty = text != savedText
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(text)
        text = redoStack.removeAt(redoStack.lastIndex)
        dirty = text != savedText
    }

    fun insert(s: String) {
        // Compose BasicTextField 无 selection 控制，统一追加到末尾（符号栏高频插入场景够用）。
        pushUndo(text)
        text += s
        dirty = true
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                api?.workspaceWrite(path, text, true) ?: "{}"
            }
            val ok = try {
                JSONObject(res).optBoolean("ok")
            } catch (_: Exception) {
                false
            }
            saving = false
            savedText = text
            dirty = !ok
            snackbar.showSnackbar(if (ok) "已保存" else "保存失败")
        }
    }

    fun requestClose() {
        if (!dirty) {
            onClose()
            return
        }
        confirmClose = true
    }

    fun performClose(choice: Int) {
        confirmClose = false
        when (choice) {
            1 -> { dirty = false; onClose() }
            2 -> {
                save()
                // 保存成功后若不再 dirty 则关闭（保存是异步的，由状态驱动）
                dirty = false
                onClose()
            }
        }
    }

    // 系统返回键拦截未保存确认
    BackHandler(enabled = dirty) { confirmClose = true }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：返回 + 标题 + 操作
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { requestClose() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    path.substringAfterLast('/'),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${SyntaxHighlighter.langLabel(lang)}${if (dirty) "  ·  未保存" else ""}${if (mode == Mode.Preview) "  ·  预览" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lang == "markdown") {
                IconButton(onClick = { mode = if (mode == Mode.Preview) Mode.Edit else Mode.Preview }) {
                    Icon(
                        if (mode == Mode.Preview) Icons.Filled.Code else Icons.Filled.Visibility,
                        contentDescription = "切换",
                    )
                }
            }
            IconButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) {
                Icon(Icons.Filled.Undo, contentDescription = "撤销")
            }
            IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) {
                Icon(Icons.Filled.Redo, contentDescription = "重做")
            }
            IconButton(onClick = { save() }, enabled = !saving) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Save, contentDescription = "保存")
                }
            }
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
            else -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    if (mode == Mode.Edit) {
                        BasicTextField(
                            value = text,
                            onValueChange = { new ->
                                if (new != text) {
                                    pushUndo(text)
                                    text = new
                                    dirty = true
                                }
                            },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(12.dp),
                        )
                    } else if (lang == "markdown") {
                        Text(
                            text, // markdown 预览：纯文本（渲染后续升级）
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        Text(
                            SyntaxHighlighter.render(text, lang, darkMode = true),
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (mode == Mode.Edit) {
                    SymbolBar(insert = ::insert)
                }
            }
        }
    }

    // 未保存确认对话框
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("未保存的修改") },
            text = { Text("文件已修改但尚未保存。") },
            confirmButton = {
                TextButton(onClick = { performClose(2) }) { Text("保存并退出") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmClose = false }) { Text("继续编辑") }
                    TextButton(onClick = { performClose(1) }) { Text("直接退出") }
                }
            },
        )
    }
}

@Composable
private fun SymbolBar(insert: (String) -> Unit) {
    val symbols: List<Pair<String, String>> = listOf(
        "Tab" to "\t", "␣" to "  ",
        "{" to "{", "}" to "}", "[" to "[", "]" to "]", "(" to "(", ")" to ")",
        "\"" to "\"", "'" to "'", "`" to "`", ";" to ";", ":" to ":",
        "," to ",", "." to ".", "/" to "/", "\\" to "\\", "|" to "|", "&" to "&",
        "?" to "?", "#" to "#", "=" to "=", "<" to "<", ">" to ">", "*" to "*",
        "+" to "+", "-" to "-", "_" to "_",
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(symbols) { (label, value) ->
            Text(
                label,
                modifier = Modifier
                    .clickable { insert(value) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}