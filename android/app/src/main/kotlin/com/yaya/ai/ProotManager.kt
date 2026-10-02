package com.yaya.ai

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object ProotManager {
    private var process: Process? = null
    private var rootfsDir: String = ""

    fun start(context: Context): Boolean {
        rootfsDir = context.filesDir.absolutePath + "/debian_rootfs"
        val rootfs = File(rootfsDir)
        if (!rootfs.exists()) return false

        val cmd = arrayOf(
            "proot",
            "--rootfs=$rootfsDir",
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/storage/emulated/0:/sdcard",
            "/bin/bash", "-l"
        )
        process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        return process != null
    }

    fun executeCommand(command: String, onOutput: (String) -> Unit) {
        val p = process ?: return
        p.outputStream.write("$command\n".toByteArray())
        p.outputStream.flush()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        var line = reader.readLine()
        while (line != null) {
            onOutput(line)
            line = reader.readLine()
        }
    }

    fun stopCommand() {
        process?.destroy()
        process = null
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
