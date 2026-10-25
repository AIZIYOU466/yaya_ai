package com.yaya.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 终端「AI」标签的命令日志（ROADMAP 任务 33）。
 *
 * Agent 经 `terminal_exec` 执行的每条命令在这里按「一次命令一条记录」累计，
 * 终端页以**只读标签**展示（与用户自己在终端页执行的命令互不干扰）。
 *
 * 线程模型：命令由 agent 线程发起（`begin`/`end`），输出行来自 proot pump 线程
 * （`append`），读取在 UI 线程（`snapshot`）。因此全部状态用锁保护，UI 只订阅
 * [version]（每次变更自增）再取快照，避免跨线程直接改 Compose 状态。
 */
object TerminalLog {
    /** 一次 agent 命令的记录（只读快照）。 */
    data class Entry(
        val id: Long,
        val command: String,
        val lines: List<String>,
        val running: Boolean,
        val exitCode: Int?,
        val startedAt: Long,
    )

    /** 最多保留最近 20 次 agent 命令（防止长会话无限增长）。 */
    private const val MAX_ENTRIES = 20

    /** 单条命令最多保留 2000 行输出（超出丢弃最早的行）。 */
    private const val MAX_LINES = 2000

    private class Builder(
        val id: Long,
        val command: String,
        val startedAt: Long,
    ) {
        val lines = ArrayList<String>()
        var running = true
        var exitCode: Int? = null

        fun snapshot(): Entry = Entry(id, command, lines.toList(), running, exitCode, startedAt)
    }

    private val lock = Any()
    private val entries = ArrayDeque<Builder>()
    private var nextId = 1L
    private var runningId: Long? = null
    private val _version = MutableStateFlow(0)

    /** 版本号：每次 begin/append/end/clear 自增；UI 据此触发重组。 */
    val version: StateFlow<Int> = _version

    /** 开始一条 agent 命令，返回记录 id。 */
    fun begin(command: String): Long = synchronized(lock) {
        val id = nextId++
        entries.addLast(Builder(id, command, System.currentTimeMillis()))
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
        runningId = id
        bump()
        id
    }

    /** 追加一行输出到当前运行中的 agent 命令（无运行中记录时忽略）。 */
    fun append(line: String) {
        synchronized(lock) {
            val current = runningId ?: return
            val builder = entries.firstOrNull { it.id == current } ?: return
            if (builder.lines.size >= MAX_LINES) builder.lines.removeAt(0)
            builder.lines.add(line)
            bump()
        }
    }

    /** 结束当前 agent 命令；[exitCode] 为 null 表示超时/异常终止。 */
    fun end(exitCode: Int?) {
        synchronized(lock) {
            val current = runningId ?: return
            entries.firstOrNull { it.id == current }?.let {
                it.running = false
                it.exitCode = exitCode
            }
            runningId = null
            bump()
        }
    }

    /** 快照（新 → 旧之外的顺序：按执行先后）。 */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.map { it.snapshot() } }

    /** 当前是否有 agent 命令在跑（终端标签页显示运行指示）。 */
    fun isRunning(): Boolean = synchronized(lock) { runningId != null }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            runningId = null
            bump()
        }
    }

    private fun bump() {
        _version.value = _version.value + 1
    }
}
