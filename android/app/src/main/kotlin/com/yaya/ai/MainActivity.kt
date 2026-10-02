package com.yaya.ai

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

class MainActivity : FlutterActivity() {
    private val AGENT_CHANNEL = "com.yaya.ai/agent"
    private val MODEL_CHANNEL = "com.yaya.ai/model"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AGENT_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "observeScreen" -> {
                        val tree = AgentAccessibilityService.instance?.captureScreenTree()
                        if (tree != null) {
                            result.success(tree.toString())
                        } else {
                            result.error("UNAVAILABLE", "Accessibility service not running", null)
                        }
                    }
                    "executeAction" -> {
                        val data = call.argument<String>("data") ?: call.arguments as String
                        val action = JSONObject(data)
                        val ok = AgentAccessibilityService.instance?.executeAction(action) ?: false
                        result.success(ok)
                    }
                    "startAgentService" -> {
                        AgentForegroundService.start(this)
                        result.success(true)
                    }
                    "stopAgentService" -> {
                        AgentForegroundService.stop(this)
                        result.success(true)
                    }
                    "startContainer" -> {
                        val ok = ProotManager.start(this)
                        result.success(ok)
                    }
                    "stopContainer" -> {
                        ProotManager.stop()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, MODEL_CHANNEL)
            .setStreamHandler(ModelStreamHandler(this))
    }
}
