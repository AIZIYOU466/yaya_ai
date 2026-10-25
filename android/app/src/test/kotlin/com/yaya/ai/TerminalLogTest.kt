package com.yaya.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 终端「AI」标签日志单测（ROADMAP 任务 33）。 */
class TerminalLogTest {

    @Before
    fun reset() {
        TerminalLog.clear()
    }

    @Test
    fun recordsCommandLifecycle() {
        val before = TerminalLog.version.value
        TerminalLog.begin("ls /workspace")
        TerminalLog.append("main.kt")
        TerminalLog.append("README.md")
        val running = TerminalLog.snapshot().single()
        assertTrue(running.running)
        assertEquals("ls /workspace", running.command)
        assertEquals(listOf("main.kt", "README.md"), running.lines)
        assertNull(running.exitCode)

        TerminalLog.end(0)
        val done = TerminalLog.snapshot().single()
        assertFalse(done.running)
        assertEquals(0, done.exitCode)
        assertTrue(TerminalLog.version.value > before)
        assertFalse(TerminalLog.isRunning())
    }

    @Test
    fun appendWithoutRunningCommandIsIgnored() {
        TerminalLog.append("stray line")
        assertTrue(TerminalLog.snapshot().isEmpty())
    }

    @Test
    fun keepsOnlyRecentEntries() {
        repeat(25) { i ->
            TerminalLog.begin("cmd-$i")
            TerminalLog.end(0)
        }
        val entries = TerminalLog.snapshot()
        assertEquals(20, entries.size)
        assertEquals("cmd-5", entries.first().command)
        assertEquals("cmd-24", entries.last().command)
    }

    @Test
    fun capsLinesPerCommand() {
        TerminalLog.begin("yes | head -n 2100")
        repeat(2100) { i -> TerminalLog.append("line-$i") }
        TerminalLog.end(0)
        val lines = TerminalLog.snapshot().single().lines
        assertEquals(2000, lines.size)
        assertEquals("line-100", lines.first())
        assertEquals("line-2099", lines.last())
    }

    @Test
    fun clearDropsEverything() {
        TerminalLog.begin("rm -rf /tmp/x")
        TerminalLog.append("done")
        TerminalLog.end(0)
        TerminalLog.clear()
        assertTrue(TerminalLog.snapshot().isEmpty())
    }
}
