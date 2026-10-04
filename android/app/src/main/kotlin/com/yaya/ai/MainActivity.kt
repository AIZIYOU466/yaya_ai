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
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : FlutterActivity() {
    private val AGENT_CHANNEL = "com.yaya.ai/agent"
    private val AGENT_EVENTS = "com.yaya.ai/agent/events"
    private val MODEL_CHANNEL = "com.yaya.ai/model"
    private val REQUEST_NOTIFICATION_PERMISSION = 1001

    private lateinit var agentHost: AgentHost
    private var agentEventSink: EventChannel.EventSink? = null
    private var agentTaskThread: Thread? = null

    // 网络信号（commit 2）：连接丢失/恢复 → Rust core 路由避开/流中止。
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 省电模式信号（commit 2）：置位时 core 跳过金丝雀等额外请求。
    private val powerSaveReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (::agentHost.isInitialized) agentHost.setPowerSave(pm.isPowerSaveMode)
        }
    }

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
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 拒绝时 AgentHost.sendNotification 会在调用方显式报错，无需在此处理。
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        agentHost = AgentHost(this) { json ->
            runOnUiThread { agentEventSink?.success(json) }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AGENT_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startAgent" -> {
                        if (agentTaskThread?.isAlive == true) {
                            result.error("BUSY", "已有任务在运行，请先停止", null)
                            return@setMethodCallHandler
                        }
                        val taskId = call.argument<String>("taskId") ?: "task"
                        val prompt = call.argument<String>("prompt") ?: ""
                        val configJson = call.argument<String>("configJson") ?: "{}"
                        agentTaskThread = Thread {
                            val resultText = agentHost.run(taskId, prompt, configJson)
                            // libLoaded=false 等非事件化失败路径：经事件通道送回错误。
                            if (resultText.startsWith("ERROR:")) {
                                runOnUiThread {
                                    agentEventSink?.success(
                                        JSONObject().apply {
                                            put("type", "error")
                                            put("message", resultText.removePrefix("ERROR: "))
                                        }.toString()
                                    )
                                }
                            }
                        }.apply {
                            name = "yaya-agent"
                            start()
                        }
                        result.success(true)
                    }
                    "stopAgent" -> {
                        agentHost.cancel()
                        agentTaskThread?.interrupt()
                        agentTaskThread = null
                        result.success(true)
                    }
                    "respondApproval" -> {
                        val id = call.argument<String>("id") ?: ""
                        val allow = call.argument<Boolean>("allow") ?: false
                        agentHost.submitApproval(id, allow)
                        result.success(true)
                    }
                    "loadRecentSession" -> result.success(agentHost.recentSessionJson())
                    "listSessions" -> result.success(agentHost.sessionsJson())
                    "loadSession" -> {
                        val id = call.argument<String>("sessionId") ?: ""
                        result.success(agentHost.sessionJson(id))
                    }
                    "renameSession" -> {
                        val id = call.argument<String>("sessionId") ?: ""
                        val title = call.argument<String>("title") ?: ""
                        agentHost.renameSession(id, title)
                        result.success(true)
                    }
                    "deleteSession" -> {
                        val id = call.argument<String>("sessionId") ?: ""
                        agentHost.deleteSession(id)
                        result.success(true)
                    }
                    "checkpoints" -> {
                        val sid = call.argument<String>("sessionId") ?: ""
                        result.success(agentHost.checkpointsJson(sid))
                    }
                    "getStats" -> result.success(agentHost.stats())
                    "workspaceRoot" -> result.success(agentHost.workspaceRoot())
                    "workspaceList" -> {
                        val path = call.argument<String>("path") ?: ""
                        result.success(agentHost.wsList(path))
                    }
                    // 单文件读写走后台线程：read 上限 10MB，主线程读会冻结 UI。
                    "workspaceRead" -> {
                        val path = call.argument<String>("path") ?: ""
                        Thread {
                            val msg = agentHost.wsRead(path)
                            runOnUiThread { result.success(msg) }
                        }.apply {
                            name = "yaya-ws-read"
                            start()
                        }
                    }
                    "workspaceWrite" -> {
                        val path = call.argument<String>("path") ?: ""
                        val content = call.argument<String>("content") ?: ""
                        val overwrite = call.argument<Boolean>("overwrite") ?: true
                        val req = JSONObject()
                            .put("path", path)
                            .put("content", content)
                            .put("overwrite", overwrite)
                            .toString()
                        Thread {
                            val msg = agentHost.wsWrite(req)
                            runOnUiThread { result.success(msg) }
                        }.apply {
                            name = "yaya-ws-write"
                            start()
                        }
                    }
                    "workspaceDelete" -> {
                        val path = call.argument<String>("path") ?: ""
                        result.success(agentHost.wsDelete(path))
                    }
                    // Git 版本管理（ROADMAP 任务 23）：经 proot 容器对 /workspace 执行 git。
                    "gitRun" -> {
                        val subargs = call.argument<List<String>>("subargs") ?: emptyList()
                        val timeout = call.argument<Number>("timeoutMs")?.toLong() ?: 30000L
                        val arr = JSONArray()
                        for (s in subargs) arr.put(s)
                        Thread {
                            val msg = GitHost.run(this, arr.toString(), timeout)
                            runOnUiThread { result.success(msg) }
                        }.apply {
                            name = "yaya-git-run"
                            start()
                        }
                    }
                    "gitDetect" -> result.success(GitHost.detect(this))
                    "localAvailable" -> result.success(agentHost.localAvailable())
                    "networkAvailable" -> result.success(isNetworkAvailable())
                    "rootfsInstalled" -> result.success(RootfsInstaller.isInstalledCurrent(this))
                    "rootfsProfiles" -> {
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
                        result.success(arr.toString())
                    }
                    "currentRootfs" -> result.success(RootfsInstaller.currentId(this))
                    "installRootfs" -> {
                        val id = call.argument<String>("id") ?: RootfsInstaller.currentId(this)
                        Thread {
                            val msg = RootfsInstaller.install(this, id)
                            runOnUiThread { result.success(msg) }
                        }.apply {
                            name = "yaya-rootfs-install"
                            start()
                        }
                    }
                    "setCurrentRootfs" -> {
                        val id = call.argument<String>("id") ?: ""
                        RootfsInstaller.setCurrent(this, id)
                        result.success(true)
                    }
                    "resetRootfs" -> {
                        val id = call.argument<String>("id") ?: ""
                        result.success(RootfsInstaller.reset(this, id))
                    }
                    "addRootfsProfile" -> {
                        val name = call.argument<String>("name") ?: ""
                        val url = call.argument<String>("url") ?: ""
                        val sha256 = call.argument<String>("sha256") ?: ""
                        result.success(RootfsInstaller.addCustom(this, name, url, sha256))
                    }
                    "removeRootfsProfile" -> {
                        val id = call.argument<String>("id") ?: ""
                        result.success(RootfsInstaller.removeCustom(this, id))
                    }
                    "startContainer" -> {
                        val ok = try {
                            ProotManager.start(this)
                        } catch (e: Exception) {
                            ProotManager.lastError = e.message ?: e.toString()
                            false
                        }
                        result.success(ok)
                    }
                    "containerError" -> result.success(ProotManager.lastError)
                    "stopContainer" -> {
                        ProotManager.stop()
                        result.success(true)
                    }
                    "startAgentService" -> {
                        AgentForegroundService.start(this)
                        result.success(true)
                    }
                    "stopAgentService" -> {
                        AgentForegroundService.stop(this)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, AGENT_EVENTS)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    agentEventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    agentEventSink = null
                }
            })

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, MODEL_CHANNEL)
            .setStreamHandler(ModelStreamHandler())

        registerSignals()
    }

    /** 注册环境信号（commit 2）：网络变化 + 省电模式，只传信号，决策在 Rust core。 */
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