package com.yaya.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import org.json.JSONObject

class ModelStreamHandler(private val context: Context) : EventChannel.StreamHandler {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var worker: Thread? = null

    private inner class MainThreadSink(private val delegate: EventChannel.EventSink) {
        fun success(value: Any?) {
            mainHandler.post { delegate.success(value) }
        }

        fun error(code: String, message: String?) {
            mainHandler.post { delegate.error(code, message, null) }
        }

        fun endOfStream() {
            mainHandler.post { delegate.endOfStream() }
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
        val out = MainThreadSink(events)
        val raw = arguments as? String ?: ""
        val args = try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }

        val command = args.optString("command", "")
        if (command.isNotEmpty()) {
            ProotManager.setLineListener { line ->
                if (line.startsWith(ProotManager.EXIT_MARKER)) {
                    ProotManager.setLineListener(null)
                    out.endOfStream()
                } else {
                    out.success(line)
                }
            }
            ProotManager.executeCommand(command)
            return
        }

        val prompt = args.optString("prompt", "")
        val modelPath = if (args.has("modelPath")) args.optString("modelPath", "") else null
        val backend = args.optString("backend", "jni")

        worker = Thread {
            try {
                when (backend) {
                    "desktop" -> {
                        // 桌面 gRPC 客户端依赖 proto 生成物（com.yaya.agent.proto / com.yaya.ai.proto），
                        // 生成物经 proto.yml 进仓库后再接入 DesktopInferenceClient（AGENTS R5/R9）。
                        out.error(
                            "DESKTOP_ERROR",
                            "桌面推理未可用：gRPC 客户端未就绪（proto 生成物待 proto.yml 产出）"
                        )
                    }

                    else -> {
                        val err = ModelBridge.generate(modelPath, prompt) { token ->
                            out.success(token)
                        }
                        if (err == null) out.endOfStream() else out.error("MODEL_ERROR", err)
                    }
                }
            } catch (e: Exception) {
                out.error("STREAM_ERROR", e.message ?: e.toString())
            }
        }.apply { start() }
    }

    override fun onCancel(arguments: Any?) {
        ModelBridge.cancel()
        ProotManager.stopCommand()
        ProotManager.setLineListener(null)
        worker?.interrupt()
        worker = null
    }
}
