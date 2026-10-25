package com.yaya.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject
import com.yaya.ai.ui.App

/**
 * UI 层 ↔ 执行层接口（迁移自 Flutter Platform Channel，AGENTS.md R5）。
 *
 * Compose UI 经此接口调用 Kotlin 执行层；Agent 事件流经 [events]（Rust Core
 * 的 Event JSON），终端命令输出经 [executeCommand] 的 Flow。所有方法都是
 * 原 MethodChannel 同名分发的直接映射，语义与 Flutter 时代完全一致。
 */
interface AgentApi {
    /** Agent 事件流（每项为事件 JSON，协议见 core/src/agent/events.rs）。 */
    val events: Flow<String>

    /** 启动一次 Agent 任务（后台线程运行，事件经 [events] 回推）。 */
    fun startAgent(taskId: String, prompt: String, configJson: String): Boolean

    /** 挂起当前任务（保存检查点，不丢弃）；配合 [resumeAgent] 续跑（任务 27）。 */
    fun suspendAgent()

    /** 从检查点继续挂起的任务；无断点返回 false。 */
    fun resumeAgent(taskId: String, configJson: String): Boolean

    /** 是否存在可恢复的任务断点（冷启动恢复暂停状态）。 */
    fun hasPendingTask(): Boolean

    /** 丢弃挂起任务的断点（用户明确放弃）。 */
    fun clearPendingTask()

    fun stopAgent()

    /** 回传用户对一次授权请求的选择（见事件 `approval_request`）。 */
    fun respondApproval(id: String, allow: Boolean)

    /** 最近会话 `{sessionId,title,messages:[...]}`；无会话返回 null。 */
    fun loadRecentSession(): String?

    /** 所有会话列表 `[{id,title,updatedAt,messageCount}]`，新 → 旧。 */
    fun listSessions(): String

    /** 指定会话 `{sessionId,title,messages}`；不存在返回 null。 */
    fun loadSession(sessionId: String): String?

    fun renameSession(sessionId: String, title: String)
    fun deleteSession(sessionId: String)

    /** 会话检查点列表 `[{id,createdAt,messages:[...]}]`，新 → 旧。 */
    fun checkpoints(sessionId: String): String

    /** 回滚到指定检查点（快照覆盖会话消息）；成功返回回滚后消息 JSON `[{role,text,ok}]`。 */
    fun restoreCheckpoint(sessionId: String, checkpointId: Long): String?

    /** 全局统计 `{sessions,messages,toolCalls,errors,totalTokens,checkpoints}`。 */
    fun getStats(): String

    // ── 工作区文件（JSON 返回，见 WorkspaceFileAccess）──
    fun workspaceRoot(): String
    fun workspaceList(path: String): String
    fun workspaceRead(path: String): String
    fun workspaceWrite(path: String, content: String, overwrite: Boolean): String
    fun workspaceDelete(path: String): String

    // ── Git（经 proot 容器对 /workspace 执行，见 GitHost）──
    fun gitRun(subargs: List<String>, timeoutMs: Long): String
    fun gitDetect(): String

    // ── 环境与根fs ──
    fun localAvailable(): Boolean
    fun networkAvailable(): Boolean
    fun rootfsInstalled(): Boolean
    fun rootfsProfiles(): String
    fun currentRootfs(): String
    fun installRootfs(id: String): String
    fun setCurrentRootfs(id: String): Boolean
    fun resetRootfs(id: String): String
    fun addRootfsProfile(name: String, url: String, sha256: String): String
    fun removeRootfsProfile(id: String): String

    // ── proot 终端容器 ──
    fun startContainer(): Boolean
    fun stopContainer(): Boolean
    fun containerError(): String?

    /** 在终端容器执行命令，逐行输出；命令结束（EXIT_MARKER）后 Flow 关闭。 */
    fun executeCommand(command: String): Flow<String>

    // ── 前台服务 ──
    fun startAgentService()
    fun stopAgentService()

    // ── 云端模型 ──
    /**
     * 按协议拉取模型列表（阻塞，调用方在 IO 线程执行）。
     * `type`: openai / anthropic / gemini。返回 `{"ok":true,"models":["..."]}` 或
     * `{"ok":false,"message":"..."}`——失败必须显式回传（R15 不静默）。
     */
    fun listModels(baseUrl: String, apiKey: String, type: String): String
}

class MainActivity : ComponentActivity(), AgentApi {
    private val REQUEST_NOTIFICATION_PERMISSION = 1001

    private lateinit var agentHost: AgentHost
    private var agentTaskThread: Thread? = null

    // 网络信号：连接丢失/恢复 → Rust core 路由避开/流中止。
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 省电模式信号：置位时 core 跳过金丝雀等额外请求。
    private val powerSaveReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (::agentHost.isInitialized) agentHost.setPowerSave(pm.isPowerSaveMode)
        }
    }

    /** Agent 事件流：Rust Core 事件 JSON，UI 侧按 events.rs 协议消费。 */
    private val eventsFlow = MutableSharedFlow<String>(extraBufferCapacity = 512)
    override val events: Flow<String> get() = eventsFlow

    override fun onStart() {
        super.onStart()
        // 回前台：清除后台信号（任务可在下次 run 时继续）。
        if (::agentHost.isInitialized) agentHost.setAppBackground(false)
    }

    override fun onStop() {
        // 切后台：置位后 Rust 侧不再发起新的模型生成（SSE 无法真正暂停）。
        if (::agentHost.isInitialized) agentHost.setAppBackground(true)
        super.onStop()
    }

    // Android 13+ notify 工具需运行时权限：首次启动请求一次，用户拒绝则工具显式报错。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // edge-to-edge：内容延伸到手势栏下 + 系统栏透明无 scrim + 浅色主题深色图标。
        // enableEdgeToEdge 处理各 Android 版本/ROM 的 edge case，比手动 setDecorFits 可靠
        // （手动版在部分设备上顶部生效但底部手势栏区域仍露系统白色）。
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
            ),
        )
        // 刘海/挖孔屏也伸进去：不设置时该区域停在窗口边缘，顶部露出一条窗口底色带
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
        }
        agentHost = AgentHost(this) { json -> eventsFlow.tryEmit(json) }
        registerSignals()
        setContent { App() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 拒绝时 AgentHost.sendNotification 会在调用方显式报错，无需在此处理。
    }

    // ── AgentApi 实现（原 MethodChannel 分发，迁移时逐条保留语义）──

    override fun startAgent(taskId: String, prompt: String, configJson: String): Boolean {
        if (agentTaskThread?.isAlive == true) return false // 已有任务在运行
        startAgentThread { agentHost.run(taskId, prompt, configJson) }
        return true
    }

    override fun suspendAgent() {
        agentHost.suspend()
    }

    override fun resumeAgent(taskId: String, configJson: String): Boolean {
        if (agentTaskThread?.isAlive == true) return false
        startAgentThread { agentHost.resume(taskId, configJson) }
        return true
    }

    override fun hasPendingTask(): Boolean = agentHost.hasPendingTask()

    override fun clearPendingTask() {
        agentHost.clearPendingTask()
    }

    /** 后台执行一次 native 循环；前台服务只是活跃窗口加速器（任务 44）。 */
    private fun startAgentThread(block: () -> String) {
        // 前台服务 = 当前活跃窗口的加速器（任务 44：执行与保活解耦，
        // 任务状态在 loop_state 检查点；服务超时/被杀后经检查点续跑）。
        AgentForegroundService.start(this)
        agentTaskThread = Thread {
            val resultText = block()
            // libLoaded=false 等非事件化失败路径：经事件通道送回错误。
            if (resultText.startsWith("ERROR:")) {
                eventsFlow.tryEmit(
                    JSONObject().apply {
                        put("type", "error")
                        put("message", resultText.removePrefix("ERROR: "))
                    }.toString()
                )
            }
            // 任务线程结束：释放前台服务（下次任务再启动；onTimeout 耗尽路径由系统负责重启）。
            AgentForegroundService.stop(this)
        }.apply {
            name = "yaya-agent"
            start()
        }
    }

    override fun stopAgent() {
        agentHost.cancel()
        agentTaskThread?.interrupt()
        agentTaskThread = null
    }

    override fun respondApproval(id: String, allow: Boolean) {
        agentHost.submitApproval(id, allow)
    }

    override fun loadRecentSession(): String? = agentHost.recentSessionJson()
    override fun listSessions(): String = agentHost.sessionsJson()
    override fun loadSession(sessionId: String): String? = agentHost.sessionJson(sessionId)
    override fun renameSession(sessionId: String, title: String) {
        agentHost.renameSession(sessionId, title)
    }
    override fun deleteSession(sessionId: String) {
        agentHost.deleteSession(sessionId)
    }
    override fun checkpoints(sessionId: String): String = agentHost.checkpointsJson(sessionId)
    override fun restoreCheckpoint(sessionId: String, checkpointId: Long): String? =
        agentHost.restoreCheckpoint(sessionId, checkpointId)
    override fun getStats(): String = agentHost.stats()

    // ── 工作区文件 ——

    override fun workspaceRoot(): String = agentHost.workspaceRoot()
    override fun workspaceList(path: String): String = agentHost.wsList(path)
    override fun workspaceRead(path: String): String = agentHost.wsRead(path)
    override fun workspaceWrite(path: String, content: String, overwrite: Boolean): String {
        val req = JSONObject()
            .put("path", path)
            .put("content", content)
            .put("overwrite", overwrite)
            .toString()
        return agentHost.wsWrite(req)
    }
    override fun workspaceDelete(path: String): String = agentHost.wsDelete(path)

    // ── Git ──

    override fun gitRun(subargs: List<String>, timeoutMs: Long): String {
        val arr = JSONArray()
        for (s in subargs) arr.put(s)
        return GitHost.run(this, arr.toString(), timeoutMs)
    }
    override fun gitDetect(): String = GitHost.detect(this)

    // ── 环境与根fs ──

    override fun localAvailable(): Boolean = agentHost.localAvailable()
    override fun networkAvailable(): Boolean = isNetworkAvailable()
    override fun rootfsInstalled(): Boolean = RootfsInstaller.isInstalledCurrent(this)
    override fun rootfsProfiles(): String {
        val arr = JSONArray()
        for (p in RootfsInstaller.profiles(this)) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("url", p.url)
                    put("sha256", p.sha256)
                    put("note", p.note)
                    put("builtin", p.builtin)
                    put("installed", RootfsInstaller.isInstalled(this@MainActivity, p.id))
                }
            )
        }
        return arr.toString()
    }
    override fun currentRootfs(): String = RootfsInstaller.currentId(this)
    override fun installRootfs(id: String): String = RootfsInstaller.install(this, id)
    override fun setCurrentRootfs(id: String): Boolean {
        RootfsInstaller.setCurrent(this, id)
        return true
    }
    override fun resetRootfs(id: String): String = RootfsInstaller.reset(this, id)
    override fun addRootfsProfile(name: String, url: String, sha256: String): String =
        RootfsInstaller.addCustom(this, name, url, sha256)
    override fun removeRootfsProfile(id: String): String = RootfsInstaller.removeCustom(this, id)

    // ── proot 终端 ──

    override fun startContainer(): Boolean {
        return try {
            ProotManager.start(this)
        } catch (e: Exception) {
            ProotManager.lastError = e.message ?: e.toString()
            false
        }
    }
    override fun stopContainer(): Boolean {
        ProotManager.stop()
        return true
    }
    override fun containerError(): String? = ProotManager.lastError

    override fun executeCommand(command: String): Flow<String> = callbackFlow {
        ProotManager.setLineListener { line ->
            if (line.startsWith(ProotManager.EXIT_MARKER)) {
                ProotManager.setLineListener(null)
                close()
            } else {
                trySend(line)
            }
        }
        ProotManager.executeCommand(command)
        awaitClose {
            ProotManager.stopCommand()
            ProotManager.setLineListener(null)
        }
    }

    // ── 前台服务 ──

    override fun listModels(baseUrl: String, apiKey: String, type: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return """{"ok":false,"message":"Base URL 为空"}"""
        if (apiKey.isBlank()) return """{"ok":false,"message":"API Key 为空"}"""
        // 协议差异（任务 25）：路径 / 认证头 / 响应字段。
        val url: String
        val headers = mutableListOf<Pair<String, String>>()
        val gemini = type == "gemini"
        when (type) {
            "anthropic" -> {
                url = if (base.endsWith("/v1")) "$base/models" else "$base/v1/models"
                headers += "x-api-key" to apiKey
                headers += "anthropic-version" to "2023-06-01"
            }
            "gemini" -> {
                url = if (base.endsWith("/v1beta")) "$base/models" else "$base/v1beta/models"
                headers += "x-goog-api-key" to apiKey
            }
            else -> {
                url = "$base/models"
                headers += "Authorization" to "Bearer $apiKey"
            }
        }
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                val code = conn.responseCode
                if (code != 200) {
                    val detail = runCatching {
                        (conn.errorStream ?: conn.inputStream).bufferedReader().readText().take(160)
                    }.getOrDefault("")
                    return JSONObject().apply {
                        put("ok", false)
                        put("message", "HTTP $code${if (detail.isBlank()) "" else "：$detail"}")
                    }.toString()
                }
                val body = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(body)
                val ids = mutableListOf<String>()
                if (gemini) {
                    val arr = root.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        val supportsChat = methods == null ||
                            (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                        if (!supportsChat) continue
                        m.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }?.let { ids += it }
                    }
                } else {
                    val arr = root.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { ids += it }
                    }
                }
                JSONObject().apply {
                    put("ok", true)
                    put("models", JSONArray(ids.distinct().sorted()))
                }.toString()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("message", "请求失败：${e.message ?: e.javaClass.simpleName}")
            }.toString()
        }
    }

    override fun startAgentService() {
        AgentForegroundService.start(this)
    }
    override fun stopAgentService() {
        AgentForegroundService.stop(this)
    }

    // ── 环境信号注册（只传信号，决策在 Rust core）──

    private fun registerSignals() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    if (::agentHost.isInitialized) agentHost.setNetworkLost(true)
                }

                override fun onAvailable(network: Network) {
                    // 网络恢复：不预连，仅清除信号，等下一个真实请求触发。
                    if (::agentHost.isInitialized) agentHost.setNetworkLost(false)
                }
            }
            try {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    cb,
                )
                networkCallback = cb
            } catch (_: Exception) {
            }
        }
        // 初始状态：无网络/省电模式在启动时即上报。
        if (::agentHost.isInitialized) {
            agentHost.setNetworkLost(!isNetworkAvailable())
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            agentHost.setPowerSave(pm?.isPowerSaveMode == true)
        }
        try {
            registerReceiver(powerSaveReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.unregisterNetworkCallback(cb)
        }
        networkCallback = null
        try {
            unregisterReceiver(powerSaveReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}