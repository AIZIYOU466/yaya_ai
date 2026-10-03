package com.yaya.ai

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

class MainActivity : FlutterActivity() {
    private val AGENT_CHANNEL = "com.yaya.ai/agent"
    private val AGENT_EVENTS = "com.yaya.ai/agent/events"
    private val MODEL_CHANNEL = "com.yaya.ai/model"
    private val REQUEST_NOTIFICATION_PERMISSION = 1001

    private lateinit var agentHost: AgentHost
    private var agentEventSink: EventChannel.EventSink? = null
    private var agentTaskThread: Thread? = null

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
                    "localAvailable" -> result.success(agentHost.localAvailable())
                    "networkAvailable" -> result.success(isNetworkAvailable())
                    "startContainer" -> result.success(ProotManager.start(this))
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
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}