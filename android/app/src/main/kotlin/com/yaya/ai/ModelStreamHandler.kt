package com.yaya.ai

import android.content.Context
import io.flutter.plugin.common.EventChannel
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class ModelStreamHandler(private val context: Context) : EventChannel.StreamHandler {

    private var thread: Thread? = null

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        val args = JSONObject(arguments as String)
        val prompt = args.optString("prompt", "")
        val modelPath = args.optString("modelPath", null)
        val command = args.optString("command", null)

        thread = Thread {
            try {
                if (command != null) {
                    ProotManager.executeCommand(command) { line ->
                        events.success(line)
                    }
                } else if (prompt.isNotEmpty()) {
                    // 模型推理：通过 JNI 调用 llama.cpp
                    // 如果 JNI 不可用，返回提示
                    try {
                        ModelBridge.generate(modelPath, prompt) { token ->
                            events.success(token)
                        }
                    } catch (e: UnsatisfiedLinkError) {
                        events.success("[JNI llama.cpp 未加载，请编译 native 库]")
                    }
                }
            } catch (e: Exception) {
                events.error("STREAM_ERROR", e.message, null)
            }
        }
        thread?.start()
    }

    override fun onCancel(arguments: Any?) {
        ModelBridge.cancel()
        ProotManager.stopCommand()
        thread?.interrupt()
        thread = null
    }
}
