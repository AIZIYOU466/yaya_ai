package com.yaya.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject

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
    companion object {
        private const val TAG = "AgentHost"
        private const val AGENT_CHANNEL_ID = "yaya_agent"
        private val libLoaded: Boolean = try {
            System.loadLibrary("yaya_core_jni")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }

        fun isAvailable(): Boolean = libLoaded
    }

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
}