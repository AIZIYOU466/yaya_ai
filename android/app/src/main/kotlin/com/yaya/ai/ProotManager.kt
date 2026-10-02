package com.yaya.ai

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object ProotManager {
    const val EXIT_MARKER = "<YAYA_EXIT_"

    private var process: Process? = null
    @Volatile
    private var readerThread: Thread? = null
    @Volatile
    private var lineListener: ((String) -> Unit)? = null

    fun start(context: Context): Boolean {
        if (process?.isAlive == true) return true
        val rootfs = File(context.filesDir.absolutePath + "/debian_rootfs")
        if (!rootfs.exists()) return false

        val cmd = arrayOf(
            "proot",
            "--rootfs=${rootfs.absolutePath}",
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/storage/emulated/0:/sdcard",
            "/bin/bash", "-l"
        )
        process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        pumpOutput()
        return true
    }

    private fun pumpOutput() {
        val p = process ?: return
        readerThread = Thread {
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    lineListener?.invoke(line)
                }
            } catch (_: Exception) {
            } finally {
                lineListener?.invoke("${EXIT_MARKER}EXITED>")
            }
        }.apply {
            isDaemon = true
            name = "proot-pump"
            start()
        }
    }

    fun setLineListener(listener: ((String) -> Unit)?) {
        lineListener = listener
    }

    fun executeCommand(command: String) {
        val p = process ?: return
        // 哨兵行通知 ModelStreamHandler 结束本次输出流（不销毁容器进程）
        val payload = "$command; printf '\\n$EXIT_MARKER%s>\\n' \"\$?\"\n"
        p.outputStream.write(payload.toByteArray())
        p.outputStream.flush()
    }

    fun stopCommand() {
        val p = process ?: return
        try {
            p.outputStream.write(3)
            p.outputStream.flush()
        } catch (_: Exception) {
        }
    }

    fun stop() {
        lineListener = null
        readerThread?.interrupt()
        readerThread = null
        process?.destroy()
        process = null
    }
}
