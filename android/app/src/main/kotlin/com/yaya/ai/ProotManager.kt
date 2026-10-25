package com.yaya.ai

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ProotManager {

    /** API 26+ 直接 isAlive；更低版本用 exitValue() 抛异常判断存活。 */
    private fun Process?.isRunning(): Boolean = when {
        this == null -> false
        Build.VERSION.SDK_INT >= 26 -> isAlive
        else -> try {
            exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    const val EXIT_MARKER = "<YAYA_EXIT_"

    private var process: Process? = null
    @Volatile
    private var readerThread: Thread? = null
    @Volatile
    private var lineListener: ((String) -> Unit)? = null
    /// 全局输出观察者（任务 33）：`(source, line)`，source ∈ ui / agent。
    /// 终端页据此把 AI 经 terminal_exec 执行的命令输出收进「AI」标签。
    @Volatile
    private var outputObserver: ((String, String) -> Unit)? = null
    /** 当前输出归属：UI 命令 / Agent 命令（同一时刻只有一个写入者）。 */
    @Volatile
    private var lineSource: String = "ui"
    /// 最近一次启动失败的详细原因（供 UI 显示）。
    @Volatile
    var lastError: String? = null

    fun setOutputObserver(listener: ((String, String) -> Unit)?) {
        outputObserver = listener
    }

    fun start(context: Context): Boolean {
        if (process.isRunning()) return true
        val rootfs = RootfsInstaller.rootfsDir(context, RootfsInstaller.currentId(context))
        if (!rootfs.exists()) {
            lastError = "未安装 Linux 环境（请在终端页下载安装 Alpine rootfs）"
            return false
        }
        val proot = try {
            ProotBinary.find(context)
        } catch (e: Exception) {
            lastError = "proot 初始化失败：${e.message ?: e}"
            return false
        }
        val cmd = arrayOf(
            proot.absolutePath,
            "--rootfs=${rootfs.absolutePath}",
            "--bind=/dev", "--bind=/proc", "--bind=/sys",
            "--bind=/storage/emulated/0:/sdcard",
            // 把 App 工作区挂进容器固定 /workspace，使 git/terminal 与 file 工具看到同一目录
            // （ROADMAP 任务 23 前置：Git UI 依赖容器内 git 操作工作区）。
            "--bind=${File(context.filesDir, "workspace")}:/workspace",
            // Alpine 默认只有 busybox sh（无 bash），用 /bin/sh 保证通用。
            "/bin/sh", "-l"
        )
        try {
            val pb = ProcessBuilder(*cmd)
            val libDir = proot.parentFile.absolutePath
            val depsDir = ProotBinary.ensureLibs(context).absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = "$libDir:$depsDir"
            // termux proot 硬编码了 loader 绝对路径（/data/data/com.termux/...），
            // 必须用 PROOT_LOADER 指回 nativeLibraryDir 中的打包副本。
            pb.environment()["PROOT_LOADER"] = "$libDir/libproot_loader.so"
            process = pb.redirectErrorStream(true).start()
            lastError = null
            pumpOutput()
            return true
        } catch (e: Exception) {
            lastError = "启动 proot 失败：${e.message ?: e}"
            return false
        }
    }

    private fun pumpOutput() {
        val p = process ?: return
        readerThread = Thread {
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val line = reader.readLine() ?: break
                    outputObserver?.invoke(lineSource, line)
                    // 任务 33：agent 命令输出进「AI」标签日志（哨兵行不入日志）。
                    if (lineSource == "agent" && !line.contains(EXIT_MARKER)) {
                        TerminalLog.append(line)
                    }
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

    @Synchronized
    fun executeCommand(command: String) {
        val p = process ?: return
        lineSource = "ui"
        // 哨兵行通知 ModelStreamHandler 结束本次输出流（不销毁容器进程）
        val payload = "$command; printf '\\n$EXIT_MARKER%s>\\n' \"\$?\"\n"
        p.outputStream.write(payload.toByteArray())
        p.outputStream.flush()
    }

    /**
     * 同步执行一条命令并返回其输出，供 Agent 的 terminal_exec 工具使用。
     * 容器未启动则先启动；缺少 rootfs 时抛出明确错误（不静默）。
     */
    @Synchronized
    fun runCommandBlocking(context: Context, command: String, timeoutMs: Long): String {
        if (!process.isRunning()) {
            if (!start(context)) {
                throw IllegalStateException(lastError ?: "终端容器启动失败")
            }
        }
        val p = process ?: throw IllegalStateException("终端容器未启动")
        // 同步命令（Git 等）不算 agent 命令，不写「AI」标签日志。
        lineSource = "ui"

        val marker = "$EXIT_MARKER${System.nanoTime()}>"
        val output = StringBuilder()
        val done = CountDownLatch(1)
        val previous = lineListener
        lineListener = { line ->
            if (line.contains(marker)) done.countDown() else output.append(line).append('\n')
        }

        val payload = "$command; printf '\\n%s\\n' \"$marker\"\n"
        p.outputStream.write(payload.toByteArray())
        p.outputStream.flush()

        val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        lineListener = previous
        if (!finished) {
            throw IllegalStateException("命令超时（${timeoutMs}ms）")
        }
        return output.toString().trimEnd()
    }

    /**
     * 同步执行命令并返回输出 + 退出码，供 Agent 的 terminal_exec 工具验证退出码使用。
     * 与 [runCommandBlocking] 同逻辑，但 payload 额外输出 `$?` 以捕获进程退出码。
     */
    @Synchronized
    fun runCommandBlockingWithCode(context: Context, command: String, timeoutMs: Long): Pair<String, Int> {
        if (!process.isRunning()) {
            if (!start(context)) {
                throw IllegalStateException(lastError ?: "终端容器启动失败")
            }
        }
        val p = process ?: throw IllegalStateException("终端容器未启动")

        val marker = "$EXIT_MARKER${System.nanoTime()}>"
        val output = StringBuilder()
        var code = -1
        val done = CountDownLatch(1)
        val previous = lineListener
        lineListener = { line ->
            if (line.startsWith(marker)) {
                code = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                done.countDown()
            } else {
                output.append(line).append('\n')
            }
        }

        // 任务 33：agent 命令（terminal_exec）单独计入「AI」标签日志。
        lineSource = "agent"
        TerminalLog.begin(command)
        try {
            val payload = "$command; printf '\\n%s%d\\n' \"$marker\" \"\$?\"\n"
            p.outputStream.write(payload.toByteArray())
            p.outputStream.flush()

            val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                throw IllegalStateException("命令超时（${timeoutMs}ms）")
            }
            TerminalLog.end(code)
            return output.toString().trimEnd() to code
        } finally {
            lineListener = previous
            lineSource = "ui"
            // 超时/异常路径也要收尾（end 幂等，已结束不会重复改）。
            TerminalLog.end(null)
        }
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
