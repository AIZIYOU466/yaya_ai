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

    /** 会话检查点列表 `[{messages:[...]}]`，新 → 旧。 */
    fun checkpoints(sessionId: String): String

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
        agentTaskThread = Thread {
            val resultText = agentHost.run(taskId, prompt, configJson)
            // libLoaded=false 等非事件化失败路径：经事件通道送回错误。
            if (resultText.startsWith("ERROR:")) {
                eventsFlow.tryEmit(
                    JSONObject().apply {
                        put("type", "error")
                        put("message", resultText.removePrefix("ERROR: "))
                    }.toString()
                )
            }
        }.apply {
            name = "yaya-agent"
            start()
        }
        return true
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