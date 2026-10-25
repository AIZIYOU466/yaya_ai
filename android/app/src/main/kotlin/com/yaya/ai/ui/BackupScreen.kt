package com.yaya.ai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.yaya.ai.BackupFormat
import com.yaya.ai.BackupHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复页（ROADMAP 任务 34）。
 *
 * 导出/导入都经 SAF（`CreateDocument` / `OpenDocument`），不需要存储权限；
 * 文件为密码加密的 `.yayabak` 容器（见 [BackupFormat]）。失败信息直接展示（R15 不静默）。
 */
@Composable
fun BackupScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    fun refreshSummary() {
        scope.launch {
            summary = withContext(Dispatchers.IO) {
                runCatching { JSONObject(BackupHost.summary(context)) }.getOrNull()
            }
        }
    }

    LaunchedEffect(Unit) { refreshSummary() }

    fun onResult(raw: String) {
        val obj = runCatching { JSONObject(raw) }.getOrNull()
        val ok = obj?.optBoolean("ok") == true
        statusIsError = !ok
        status = obj?.optString("message")?.takeIf { it.isNotBlank() }
            ?: if (ok) "操作已完成" else "操作失败（返回内容无法解析）"
        busy = false
        refreshSummary()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFormat.MIME),
    ) { uri ->
        if (uri == null) {
            statusIsError = false
            status = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        busy = true
        statusIsError = false
        status = "正在加密导出…"
        val pwd = password
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { BackupHost.export(context, uri, pwd) }
                    .getOrElse { """{"ok":false,"message":"导出失败：${it.message ?: it.javaClass.simpleName}"}""" }
            }
            onResult(raw)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            statusIsError = false
            status = "已取消选择文件"
        } else {
            pendingRestore = uri
        }
    }

    val ready = password.length >= BackupFormat.MIN_PASSWORD_LENGTH && !busy
    val canExport = ready && password == confirm
    val canRestore = ready

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("备份与恢复", style = MaterialTheme.typography.titleLarge)
        Text(
            "把配置、工作区文件与技能打包成一个密码加密的 .yayabak 文件，可在新设备上恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard {
            val prefsFiles = summary?.optInt("prefsFiles", -1) ?: -1
            val workspaceFiles = summary?.optInt("workspaceFiles", -1) ?: -1
            val skillsFiles = summary?.optInt("skillsFiles", -1) ?: -1
            val bytes = summary?.optLong("bytes", 0L) ?: 0L
            Text("本次备份内容", style = MaterialTheme.typography.titleSmall)
            Text(
                if (prefsFiles < 0) "统计中…"
                else "配置 $prefsFiles 个文件 · 工作区 $workspaceFiles 个文件 · 技能 $skillsFiles 个文件（${formatSize(bytes)}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "不包含：会话与记忆数据库、rootfs 镜像、任务断点状态。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("备份密码（至少 ${BackupFormat.MIN_PASSWORD_LENGTH} 位）") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text("确认密码（导出时必填）") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirm.isNotEmpty() && confirm != password,
            supportingText = {
                if (confirm.isNotEmpty() && confirm != password) Text("两次输入的密码不一致")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val name = "yaya-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}${BackupFormat.FILE_SUFFIX}"
                    exportLauncher.launch(name)
                },
                enabled = canExport,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导出备份")
            }
            FilledTonalButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = canRestore,
                modifier = Modifier.weight(1f),
            ) {
                Text("选择备份恢复")
            }
        }

        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("处理中…", style = MaterialTheme.typography.bodySmall)
            }
        }

        status?.let { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }

        Text(
            "恢复说明：配置逐项写回并立即生效；工作区与技能为合并覆盖（不会删除备份中没有的文件）；" +
                "任务断点状态不恢复，避免恢复后自动续跑旧任务。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("确认恢复备份") },
            text = {
                Text(
                    "将用所选备份覆盖当前配置，并合并写入工作区与技能文件。" +
                        "该操作不可撤销（建议先导出一份当前备份）。是否继续？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    busy = true
                    statusIsError = false
                    status = "正在解密并恢复…"
                    val pwd = password
                    scope.launch {
                        val raw = withContext(Dispatchers.IO) {
                            runCatching { BackupHost.restore(context, uri, pwd) }
                                .getOrElse { """{"ok":false,"message":"恢复失败：${it.message ?: it.javaClass.simpleName}"}""" }
                        }
                        onResult(raw)
                    }
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
    else -> "${bytes}B"
}
