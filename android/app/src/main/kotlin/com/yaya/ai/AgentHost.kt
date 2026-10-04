package com.yaya.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Kotlin ↔ Rust Core 的宿主（AGENTS.md R5/R6/R10）。
 *
 * `nativeRunLoop` 在 Rust 侧运行循环机；循环机经 JNI 回调本类的方法获取平台能力：
 * [observeScreen] / [executeAction] / [generate] / [mcpListTools] / [mcpCallTool] / [onEvent]。
 * 方法名与签名必须与 `jni/src/lib.rs` 一致。
 */
class AgentHost(
    private val context: Context,
    private val eventSink: (String) -> Unit,
) {
    // 本地持久化（AGENTS.md R13）：初始化失败不阻断 Agent，仅跳过写库。
    private val database: AgentDatabase? = try {
        AgentDatabase.get(context)
    } catch (_: Exception) {
        null
    }
    // 工作区文件访问（AGENTS.md R19）：初始化失败不阻断 Agent。
    private val workspace: WorkspaceFileAccess? = try {
        WorkspaceFileAccess(context)
    } catch (_: Exception) {
        null
    }
    companion object {
        private const val TAG = "AgentHost"
        private const val AGENT_CHANNEL_ID = "yaya_agent"
        /** 授权等待上限：超时按拒绝处理（安全默认）。 */
        private const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L
        private val libLoaded: Boolean = try {
            System.loadLibrary("yaya_core_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

        fun isAvailable(): Boolean = libLoaded
    }

    // 授权确认的跨线程握手：Rust 循环机（后台线程）阻塞等待，
    // UI 线程经 [submitApproval] 回传用户选择。单任务模型，同一时刻仅一个待确认。
    private val approvalLock = Object()
    private var pendingApprovalId: String? = null
    private var pendingApprovalResult: Boolean? = null

    // 持久化状态：当前会话与流式 assistant 文本累积。
    private var currentSessionId: String? = null
    private val assistantBuffer = StringBuilder()

    private external fun nativeRunLoop(
        taskId: String,
        prompt: String,
        configJson: String,
        host: Any,
    ): String

    private external fun nativeCancel()

    /** 同步运行一次任务循环；应在后台线程调用。返回最终文本或 "ERROR: ..."。 */
    fun run(taskId: String, prompt: String, configJson: String): String {
        if (!libLoaded) {
            return "ERROR: Rust Core 库 libyaya_core_jni.so 未加载（需 scripts/build-android.sh 交叉编译）"
        }
        currentSessionId = taskId
        database?.let { db ->
            db.upsertSession(taskId, prompt.take(60))
            db.insertMessage(taskId, "user", prompt, true)
        }
        syncMcpServers(configJson)
        return nativeRunLoop(taskId, prompt, configJson, this)
    }

    /** 按配置启停 stdio MCP 服务器；仅 stdio 受支持（AGENTS.md R10）。 */
    private fun syncMcpServers(configJson: String) {
        try {
            val arr = JSONObject(configJson).optJSONArray("mcpServers") ?: return
            val stdio = mutableListOf<McpServerConfig>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (!s.optBoolean("enabled", false)) continue
                val name = s.optString("name")
                if (s.optString("type", "stdio") != "stdio") {
                    Log.w(TAG, "MCP 服务器 $name 类型不受支持（仅 stdio），已跳过")
                    continue
                }
                val command = s.optString("command")
                if (name.isEmpty() || command.isEmpty()) continue
                val args = mutableListOf<String>()
                s.optJSONArray("args")?.let { a ->
                    for (j in 0 until a.length()) args.add(a.optString(j))
                }
                stdio.add(McpServerConfig(name, command, args))
            }
            McpProcessManager.syncEnabled(stdio)
        } catch (e: Exception) {
            Log.w(TAG, "同步 MCP 服务器失败: ${e.message}")
        }
    }

    fun cancel() {
        if (libLoaded) nativeCancel()
        // 唤醒可能阻塞中的授权等待，按拒绝处理。
        synchronized(approvalLock) {
            pendingApprovalResult = false
            approvalLock.notifyAll()
        }
    }

    /**
     * 供 Rust `JniApprover` 请求用户确认：入参 `{tool,args,reversibility}`，
     * 返回 `{"allow":bool}`。经事件通道发 `approval_request` 给 Dart，
     * 阻塞等待用户在 UI 上的选择；超时或取消按拒绝处理。
     */
    fun requestApproval(json: String): String {
        val req = try {
            JSONObject(json)
        } catch (e: Exception) {
            return JSONObject().put("allow", false).toString()
        }
        val id = UUID.randomUUID().toString()
        synchronized(approvalLock) {
            pendingApprovalId = id
            pendingApprovalResult = null
        }
        eventSink(
            JSONObject().apply {
                put("type", "approval_request")
                put("id", id)
                put("tool", req.optString("tool"))
                put("args", req.opt("args") ?: JSONObject())
                put("reversibility", req.optString("reversibility"))
            }.toString()
        )

        val deadline = System.currentTimeMillis() + APPROVAL_TIMEOUT_MS
        synchronized(approvalLock) {
            while (pendingApprovalResult == null) {
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0) break
                try {
                    approvalLock.wait(remain)
                } catch (_: InterruptedException) {
                    break
                }
            }
            val allow = pendingApprovalResult == true
            pendingApprovalId = null
            pendingApprovalResult = null
            return JSONObject().put("allow", allow).toString()
        }
    }

    /** UI 线程经此回传用户对某个授权请求的选择。 */
    fun submitApproval(id: String, allow: Boolean) {
        synchronized(approvalLock) {
            if (pendingApprovalId == id) {
                pendingApprovalResult = allow
                approvalLock.notifyAll()
            }
        }
    }

    fun localAvailable(): Boolean = ModelBridge.isAvailable() && !ModelBridge.isStub()

    // ── 以下为 Rust 经 JNI 回调的方法 ──────────────────────────

    fun executeAction(json: String): String {
        return try {
            val action = JSONObject(json)
            when (action.optString("type")) {
                "terminal" -> {
                    val command = action.optString("command", "")
                    val timeout = action.optLong("timeout_ms", 30000L)
                    val out = ProotManager.runCommandBlocking(context, command, timeout)
                    JSONObject().put("ok", true).put("message", out).toString()
                }
                "clipboard_read" ->
                    JSONObject().put("ok", true).put("message", clipboardText()).toString()
                "clipboard_write" -> {
                    setClipboardText(action.optString("text", ""))
                    JSONObject().put("ok", true).put("message", "已写入剪贴板").toString()
                }
                "notify" -> {
                    sendNotification(action.optString("title", ""), action.optString("body", ""))
                    JSONObject().put("ok", true).put("message", "已发送通知").toString()
                }
                else ->
                    JSONObject().put("ok", false)
                        .put("message", "未知动作: ${action.optString("type")}")
                        .toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("message", e.message ?: "执行失败").toString()
        }
    }

    // ── 剪贴板与通知（不依赖无障碍服务） ──────────────────

    private fun clipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ""
        val clip = cm.primaryClip ?: return ""
        return if (clip.itemCount > 0) clip.getItemAt(0).coerceToText(context).toString() else ""
    }

    private fun setClipboardText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: throw IllegalStateException("剪贴板服务不可用")
        cm.setPrimaryClip(ClipData.newPlainText("yaya_agent", text))
    }

    private fun sendNotification(title: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: throw IllegalStateException("通知服务不可用")
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("通知权限未授予（请在系统设置中允许通知）")
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                AGENT_CHANNEL_ID,
                "Agent 通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(channel)
            Notification.Builder(context, AGENT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        @Suppress("DEPRECATION")
        builder
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
            .let { manager.notify(1001, it) }
    }

    /** 供 Rust `LocalBackend` 调端侧模型推理：入参为已渲染好的 prompt（`{prompt, model}`）。 */
    fun generatePrompt(requestJson: String): String {
        return try {
            val req = JSONObject(requestJson)
            val prompt = req.optString("prompt", "")
            val modelPath = req.optString("model_path", "")
            if (prompt.isEmpty()) {
                JSONObject().put("ok", false).put("text", "缺少 prompt").toString()
            } else {
                val sb = StringBuilder()
                val err = ModelBridge.generate(modelPath.ifEmpty { null }, prompt) { token ->
                    sb.append(token)
                }
                if (err == null) {
                    JSONObject().put("ok", true).put("text", sb.toString()).toString()
                } else {
                    // 显式错误信封：Rust 侧 LocalBackend 据此返回 Err，绝不当作模型输出。
                    JSONObject().put("ok", false).put("text", err).toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("text", e.message ?: "生成失败").toString()
        }
    }

    fun onEvent(json: String) {
        eventSink(json)
        persistEvent(json)
    }

    /** 持久化事件流：消息入库 + 工具执行前自动保存检查点。失败不打断 Agent。 */
    private fun persistEvent(json: String) {
        val sid = currentSessionId ?: return
        val db = database ?: return
        try {
            val ev = JSONObject(json)
            when (ev.optString("type")) {
                "tool_call" -> {
                    val args = ev.opt("args")
                    db.insertMessage(sid, "tool", "调用 ${ev.optString("name")} $args", true)
                    // 执行前快照：回滚点（AGENTS.md R13）。
                    db.saveCheckpoint(sid, db.messagesJson(sid))
                }
                "tool_result" -> {
                    db.insertMessage(sid, "tool", ev.optString("content"), ev.optBoolean("ok", false))
                }
                "notice" -> {
                    db.insertMessage(sid, "system", ev.optString("message"), true)
                }
                "token" -> {
                    assistantBuffer.append(ev.optString("text"))
                }
                "usage" -> {
                    // 存入 messages 表供统计面板聚合（任务 17）。
                    val total = ev.optInt("total_tokens", 0)
                    if (total > 0) db.insertMessage(sid, "usage", total.toString(), true)
                }
                "tool_policy" -> {
                    // 决策链记录（任务 18）：verdict/name/reason 存入 messages 表供复盘。
                    val name = ev.optString("name")
                    val verdict = ev.optString("verdict")
                    val reason = ev.optString("reason")
                    db.insertMessage(sid, "policy", "$verdict: $name — $reason", true)
                }
                "done" -> {
                    flushAssistant()
                }
                "error" -> {
                    flushAssistant()
                    db.insertMessage(sid, "system", "错误: ${ev.optString("message")}", false)
                }
                // state / tool_policy / usage / approval_request 不落库。
            }
        } catch (_: Exception) {
            // 持久化失败不影响 Agent 主流程。
        }
    }

    private fun flushAssistant() {
        val sid = currentSessionId ?: return
        val db = database ?: return
        val text = assistantBuffer.toString().trim()
        assistantBuffer.setLength(0)
        if (text.isEmpty()) return
        db.insertMessage(sid, "assistant", text, true)
        db.touchSession(sid)
    }

    /** 最近会话 JSON（供 Dart 重启恢复）；无会话返回 null。 */
    fun recentSessionJson(): String? = database?.recentSessionJson()

    /** 所有会话列表 JSON（多会话管理，任务 24）。 */
    fun sessionsJson(): String = database?.sessionsJson() ?: "[]"

    /** 重命名会话。 */
    fun renameSession(id: String, title: String) {
        database?.renameSession(id, title)
    }

    /** 删除会话及其消息与检查点。 */
    fun deleteSession(id: String) {
        database?.deleteSession(id)
    }

    /** 指定会话 JSON（含消息）；不存在返回 null。 */
    fun sessionJson(id: String): String? = database?.sessionJson(id)

    /** 会话检查点列表 JSON：`[{messages:[...]}]`，新 → 旧。 */
    fun checkpointsJson(sessionId: String): String {
        val arr = JSONArray()
        database?.checkpointMessagesList(sessionId)?.forEach { j ->
            arr.put(JSONObject().apply { put("messages", JSONArray(j)) })
        }
        return arr.toString()
    }

    /** 供 Rust 列出 MCP 工具：`{"ok":true,"tools":[...]}` 或 `{"ok":false,"message":...}`。 */
    fun mcpListTools(): String =
        try {
            JSONObject()
                .put("ok", true)
                .put("tools", McpProcessManager.listTools())
                .toString()
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("message", e.message ?: "列出 MCP 工具失败")
                .toString()
        }

    /** 供 Rust 调用 MCP 工具：入参 `{server,tool,arguments}`，返回 `{ok,message}`。 */
    fun mcpCallTool(json: String): String =
        try {
            val req = JSONObject(json)
            val out = McpProcessManager.callTool(
                req.optString("server"),
                req.optString("tool"),
                req.optJSONObject("arguments") ?: JSONObject(),
            )
            JSONObject().put("ok", true).put("message", out).toString()
        } catch (e: Exception) {
            JSONObject()
                .put("ok", false)
                .put("message", e.message ?: "MCP 调用失败")
                .toString()
        }

    // ── 技能与记忆（Rust core 经 JNI 调用，AGENTS.md R13） ──────────────────

    /** 技能根目录：App 私有目录 `filesDir/skills`（不存在则创建）。 */
    fun skillsDir(): String {
        val dir = java.io.File(context.filesDir, "skills")
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath
    }

    /** 记忆清单：`{"items":[{name,description}]}`。 */
    fun memList(): String =
        JSONObject().put("items", JSONArray(database?.memListJson() ?: "[]")).toString()

    /** 读取记忆：`{"ok":true,"content":...}` 或 `{"ok":false,"message":...}`。 */
    fun memRead(name: String): String {
        val content = database?.memRead(name)
        return if (content != null) {
            JSONObject().put("ok", true).put("content", content).toString()
        } else {
            JSONObject().put("ok", false).put("message", "记忆 $name 不存在").toString()
        }
    }

    /** 保存记忆：入参 `{name,description,content}`。 */
    fun memSave(json: String): String {
        val req = JSONObject(json)
        val name = req.optString("name")
        if (name.isEmpty()) {
            return JSONObject().put("ok", false).put("message", "缺少 name").toString()
        }
        database?.memSave(name, req.optString("description"), req.optString("content"))
        return JSONObject().put("ok", true).put("message", "已保存记忆 $name").toString()
    }

    /** 局部编辑：入参 `{name,old_string,new_string}`。 */
    fun memEdit(json: String): String {
        val req = JSONObject(json)
        val name = req.optString("name")
        if (name.isEmpty()) {
            return JSONObject().put("ok", false).put("message", "缺少 name").toString()
        }
        val edited = database?.memEdit(
            name,
            req.optString("old_string"),
            req.optString("new_string"),
        ) ?: false
        return if (edited) {
            JSONObject().put("ok", true).put("message", "已更新记忆 $name").toString()
        } else {
            JSONObject()
                .put("ok", false)
                .put("message", "记忆 $name 不存在或未找到目标片段")
                .toString()
        }
    }

    /** 删除记忆。 */
    fun memDelete(name: String): String {
        val deleted = database?.memDelete(name) ?: false
        return if (deleted) {
            JSONObject().put("ok", true).put("message", "已删除记忆 $name").toString()
        } else {
            JSONObject().put("ok", false).put("message", "记忆 $name 不存在").toString()
        }
    }

    /** 全局统计（任务 17）：供 Dart 统计面板展示。 */
    fun stats(): String = database?.statsJson() ?: "{}"

    // ── 工作区文件（JNI 回调，AGENTS.md R19） ──────────────────

    fun wsList(path: String): String = workspace?.list(path) ?: errJson("工作区不可用")
    fun wsRead(path: String): String = workspace?.read(path) ?: errJson("工作区不可用")
    fun wsWrite(json: String): String = workspace?.write(json) ?: errJson("工作区不可用")
    fun wsEdit(json: String): String = workspace?.edit(json) ?: errJson("工作区不可用")
    fun wsDelete(path: String): String = workspace?.delete(path) ?: errJson("工作区不可用")

    /** 工作区根路径（UI 展示用）。 */
    fun workspaceRoot(): String = workspace?.rootPath() ?: ""

    private fun errJson(msg: String): String =
        JSONObject().put("ok", false).put("message", msg).toString()
}