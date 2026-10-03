package com.yaya.ai

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel
import org.json.JSONObject

/**
 * 终端命令输出流（EventChannel `com.yaya.ai/model`）。
 *
 * Agent 的模型推理已迁移至 Rust Core（经 JNI 回调），此通道现仅承载 proot 终端的命令回显。
 */
class ModelStreamHandler : EventChannel.StreamHandler {
    private val mainHandler = Handler(Looper.getMainLooper())

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
        if (command.isEmpty()) {
            out.error("BAD_ARGS", "缺少 command 参数")
            return
        }

        ProotManager.setLineListener { line ->
            if (line.startsWith(ProotManager.EXIT_MARKER)) {
                ProotManager.setLineListener(null)
                out.endOfStream()
            } else {
                out.success(line)
            }
        }
        ProotManager.executeCommand(command)
    }

    override fun onCancel(arguments: Any?) {
        ProotManager.stopCommand()
        ProotManager.setLineListener(null)
    }
}