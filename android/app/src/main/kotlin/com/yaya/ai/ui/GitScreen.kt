package com.yaya.ai.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class StatusEntry(val path: String, val x: String, val y: String) {
    val isUntracked: Boolean get() = x == "?" && y == "?"
    val isStaged: Boolean get() = x != " " && x != "?"
    val isModified: Boolean get() = y != " " && y != "?" && !isUntracked
}

private data class Branch(val name: String, val isCurrent: Boolean)
private data class Commit(val hash: String, val subject: String)

private enum class GitEnv { Loading, NoGit, NoRepo, Ready }
private enum class GitTab { Status, Branch, Log }

/** Git 版本管理（ROADMAP 任务 23 迁移）：状态 / 分支 / 提交三标签页，命令经 proot 执行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen() {
    val api = LocalAgentApi.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    var env by remember { mutableStateOf(GitEnv.Loading) }
    var envMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var staged by remember { mutableStateOf<List<StatusEntry>>(emptyList()) }
    var modified by remember { mutableStateOf<List<StatusEntry>>(emptyList()) }
    var untracked by remember { mutableStateOf<List<StatusEntry>>(emptyList()) }
    var branches by remember { mutableStateOf<List<Branch>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("") }
    var commits by remember { mutableStateOf<List<Commit>>(emptyList()) }
    var tab by remember { mutableStateOf(GitTab.Status) }
    var commitPrompt by remember { mutableStateOf(false) }
    var branchPrompt by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var sheetText by remember { mutableStateOf<Pair<String, String>?>(null) } // (title, monospace text)

    suspend fun gitRun(args: List<String>): JSONObject = try {
        JSONObject(api?.gitRun(args, 30000) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    suspend fun loadStatus() {
        val m = gitRun(listOf("status", "--porcelain=v1", "-z"))
        if (m.optBoolean("ok").not()) return
        val s = mutableListOf<StatusEntry>()
        val md = mutableListOf<StatusEntry>()
        val u = mutableListOf<StatusEntry>()
        for (rec in m.optString("output", "").split('\u0000')) {
            if (rec.length < 3) continue
            val e = StatusEntry(rec.substring(3), rec[0].toString(), rec[1].toString())
            when {
                e.isUntracked -> u.add(e)
                e.isStaged -> s.add(e)
                else -> md.add(e)
            }
        }
        staged = s; modified = md; untracked = u
    }

    suspend fun loadBranches() {
        val m = gitRun(listOf("branch"))
        if (m.optBoolean("ok").not()) return
        val list = mutableListOf<Branch>()
        var cur = ""
        for (l in m.optString("output", "").split('\n')) {
            val t = l.trim()
            if (t.isEmpty()) continue
            val isCur = l.startsWith("*")
            val name = l.substring(2).trim()
            if (isCur) cur = name
            list.add(Branch(name, isCur))
        }
        branches = list; currentBranch = cur
    }

    suspend fun loadLog() {
        val m = gitRun(listOf("log", "--oneline", "--decorate", "-50"))
        commits = if (m.optBoolean("ok").not()) {
            emptyList()
        } else {
            m.optString("output", "").split('\n').mapNotNull { l ->
                val t = l.trim()
                if (t.isEmpty()) null else {
                    val sp = t.indexOf(' ')
                    if (sp <= 0) null else Commit(t.substring(0, sp), t.substring(sp + 1))
                }
            }
        }
    }

    suspend fun loadAll() {
        busy = true
        loadStatus(); loadBranches(); loadLog()
        busy = false
    }

    suspend fun init() {
        env = GitEnv.Loading
        val m = JSONObject(api?.gitDetect() ?: "{}")
        when {
            m.optBoolean("gitOk").not() -> {
                env = GitEnv.NoGit
                envMessage = "终端容器未安装 git，请先在「终端」页执行：\napk add git"
            }
            m.optBoolean("isRepo").not() -> env = GitEnv.NoRepo
            else -> {
                env = GitEnv.Ready
                loadAll()
            }
        }
    }

    LaunchedEffect(Unit) { init() }

    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val refresh = { scope.launch { loadAll() } }

    fun git(args: List<String>, then: () -> Unit) {
        scope.launch { gitRun(args); then(); loadAll() }
    }

    when (env) {
        GitEnv.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        GitEnv.NoGit -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(envMessage)
        }
        GitEnv.NoRepo -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("工作区还不是 Git 仓库")
                TextButton(onClick = { scope.launch { gitRun(listOf("init")); init() } }) {
                    Text("git init 初始化")
                }
            }
        }
        GitEnv.Ready -> Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab.ordinal) {
                GitTab.entries.forEach { t ->
                    Tab(
                        selected = tab == t,
                        onClick = { tab = t },
                        text = { Text(when (t) {
                            GitTab.Status -> "状态"
                            GitTab.Branch -> "分支"
                            GitTab.Log -> "提交"
                        }) },
                    )
                }
            }
            if (busy) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                }
            }
            when (tab) {
                GitTab.Status -> StatusTab(
                    staged = staged, modified = modified, untracked = untracked,
                    toast = toast,
                    onDiff = { path, cached -> scope.launch {
                        val m = gitRun(if (cached) listOf("diff", "--cached", "--", path) else listOf("diff", "--", path))
                        if (m.optBoolean("ok").not()) toast(m.optString("message", "无法读取差异"))
                        else if (m.optString("output", "").trim().isEmpty()) toast("无差异")
                        else sheetText = "Diff · $path" to m.optString("output", "")
                    } },
                    onStage = { p -> git(listOf("add", "--", p)) {} },
                    onUnstage = { p -> git(listOf("restore", "--staged", "--", p)) {} },
                    onStageAll = { git(listOf("add", "-A")) {} },
                    onUnstageAll = { git(listOf("restore", "--staged", ".")) {} },
                    onRestore = { p -> confirm = "丢弃 ${p.let { it }} 的工作区改动（不可恢复）？" to { git(listOf("restore", "--", p)) {} } },
                    onRestoreAll = { confirm = "丢弃所有未暂存改动（不可恢复）？已暂存内容不受影响。" to { git(listOf("restore", ".")) {} } },
                    onCommit = { commitPrompt = true },
                )
                GitTab.Branch -> BranchTab(
                    branches = branches, currentBranch = currentBranch,
                    onNew = { branchPrompt = true },
                    onCheckout = { name -> confirm = "切换到 $name？（有未提交改动可能被阻止）" to { git(listOf("checkout", name)) {} } },
                    onDelete = { name ->
                        if (name == currentBranch) toast("不能删除当前分支")
                        else confirm = "删除分支 $name？（仅能删已合并分支）" to { git(listOf("branch", "-d", name)) {} }
                    },
                )
                GitTab.Log -> LogTab(
                    commits = commits,
                    onShow = { c -> scope.launch {
                        val m = gitRun(listOf("show", "--stat", "--oneline", c.hash))
                        if (m.optBoolean("ok").not()) toast(m.optString("message", "无法读取提交"))
                        else sheetText = c.hash to m.optString("output", "")
                    } },
                )
            }
        }
    }

    // 提交信息对话框
    if (commitPrompt) {
        TextPrompt(title = "提交", label = "提交信息", initial = "") { msg ->
            commitPrompt = false
            if (msg.trim().isNotEmpty()) {
                git(listOf("add", "-A")) {}
                scope.launch { gitRun(listOf("commit", "-m", msg.trim())); loadAll() }
            }
        }
    }
    // 新建分支对话框
    if (branchPrompt) {
        TextPrompt(title = "新建分支", label = "分支名", initial = "") { name ->
            branchPrompt = false
            if (name.trim().isNotEmpty()) git(listOf("checkout", "-b", name.trim())) {}
        }
    }
    // 确认对话框
    confirm?.let { (body, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("确认") },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = { confirm = null; action() }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            },
        )
    }
    // diff / 提交详情底部面板
    sheetText?.let { (title, text) ->
        ModalBottomSheet(onDismissRequest = { sheetText = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// ── 状态 tab ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusTab(
    staged: List<StatusEntry>,
    modified: List<StatusEntry>,
    untracked: List<StatusEntry>,
    toast: (String) -> Unit,
    onDiff: (String, Boolean) -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onRestore: (String) -> Unit,
    onRestoreAll: () -> Unit,
    onCommit: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
        if (staged.isEmpty() && modified.isEmpty() && untracked.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("工作区干净", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (staged.isNotEmpty()) {
            item { SectionHeader("已暂存", onStageAll, "全部取消暂存", onUnstageAll) }
            items(staged, key = { "s${it.path}" }) { e ->
                StatusRow(e, onDiff = { onDiff(e.path, true) }, onClick = { onUnstage(e.path) }, hint = "点击取消暂存")
            }
        }
        if (modified.isNotEmpty()) {
            item { SectionHeader("已修改", onStageAll, "全部回退", onRestoreAll) }
            items(modified, key = { "m${it.path}" }) { e ->
                StatusRow(e, onDiff = { onDiff(e.path, false) }, onClick = { onStage(e.path) }, hint = "点击暂存")
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    TextButton(onClick = { onRestore(".") }) { Text("全部回退未暂存") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCommit) { Text("提交...") }
                }
            }
        }
        if (untracked.isNotEmpty()) {
            item { SectionHeader("未跟踪", onStageAll, "全部暂存", onStageAll) }
            items(untracked, key = { "u${it.path}" }) { e ->
                StatusRow(e, onDiff = null, onClick = { onStage(e.path) }, hint = "点击暂存")
            }
        }
        if (staged.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    TextButton(onClick = onCommit) { Text("提交...") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionHeader(title: String, left: () -> Unit, rightLabel: String, right: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = left) { Text("全部暂存") }
        TextButton(onClick = right) { Text(rightLabel) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusRow(
    e: StatusEntry,
    onDiff: (() -> Unit)?,
    onClick: () -> Unit,
    hint: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (e.isUntracked) "??" else "${e.x}${e.y}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(e.path, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onDiff != null) {
            TextButton(onClick = onDiff) { Text("diff") }
        }
    }
}

// ── 分支 tab ──

@Composable
private fun BranchTab(
    branches: List<Branch>,
    currentBranch: String,
    onNew: () -> Unit,
    onCheckout: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("当前分支：$currentBranch", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onNew) { Text("新建分支") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(branches, key = { it.name }) { b ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (b.isCurrent) "* " else "  ",
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        b.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        style = if (b.isCurrent) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    )
                    if (!b.isCurrent) {
                        TextButton(onClick = { onCheckout(b.name) }) { Text("切换") }
                        TextButton(onClick = { onDelete(b.name) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

// ── 提交 tab ──

@Composable
private fun LogTab(commits: List<Commit>, onShow: (Commit) -> Unit) {
    if (commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无提交", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(commits, key = { it.hash }) { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onShow(c) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    c.hash.take(7),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(c.subject, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        }
    }
}

// ── 通用输入对话框 ──

@Composable
private fun TextPrompt(title: String, label: String, initial: String, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm("") }) { Text("取消") }
        },
    )
}