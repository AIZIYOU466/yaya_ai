package com.yaya.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 远端工作区路径归一化/越界校验单测（ROADMAP 任务 35）。 */
class RemotePathsTest {

    @Test
    fun normalizesDotsAndDuplicateSlashes() {
        assertEquals("/workspace", RemotePaths.normalize("/workspace/"))
        assertEquals("/workspace/a/b", RemotePaths.normalize("/workspace/./a//b"))
        assertEquals("/workspace/b", RemotePaths.normalize("/workspace/a/../b"))
        assertEquals("/", RemotePaths.normalize("/"))
        assertEquals("/a", RemotePaths.normalize("///a"))
    }

    @Test
    fun rejectsRelativeOrEscapingPaths() {
        assertNull(RemotePaths.normalize("relative/path"))
        assertNull(RemotePaths.normalize("/../etc"))
        assertNull(RemotePaths.normalize("/a/../../b"))
        assertNull(RemotePaths.normalize("/a\u0000b"))
    }

    @Test
    fun resolvesInsideRemoteWorkspace() {
        assertEquals("/workspace/src/main.kt", RemotePaths.resolve("/workspace", "src/main.kt"))
        assertEquals("/workspace", RemotePaths.resolve("/workspace", ""))
        assertEquals("/workspace", RemotePaths.resolve("/workspace", "."))
        // 与本地工作区同语义：绝对路径被剥掉前导 `/`，仍落在工作区内
        assertEquals("/workspace/etc/passwd", RemotePaths.resolve("/workspace", "/etc/passwd"))
        assertEquals("/workspace/a", RemotePaths.resolve("/workspace/", "a"))
    }

    @Test
    fun rejectsEscapesOutsideRemoteWorkspace() {
        assertNull(RemotePaths.resolve("/workspace", "../etc/passwd"))
        assertNull(RemotePaths.resolve("/workspace", "src/../../etc"))
        assertNull(RemotePaths.resolve("workspace", "a"))
        assertNull(RemotePaths.resolve("/workspace", "a\u0000b"))
    }

    @Test
    fun rootSlashAllowsEverything() {
        assertEquals("/a/b", RemotePaths.resolve("/", "a/b"))
    }
}
