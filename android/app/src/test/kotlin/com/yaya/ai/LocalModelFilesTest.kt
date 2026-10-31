package com.yaya.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 端侧模型文件名/体积规则单测（ROADMAP 任务 31）。 */
class LocalModelFilesTest {

    @Test
    fun acceptsGgufExtensionCaseInsensitively() {
        assertTrue(LocalModelFiles.hasModelExtension("qwen2.5-3b-instruct-q4_k_m.gguf"))
        assertTrue(LocalModelFiles.hasModelExtension("MODEL.GGUF"))
        assertFalse(LocalModelFiles.hasModelExtension("model.bin"))
        assertFalse(LocalModelFiles.hasModelExtension("gguf"))
    }

    @Test
    fun sanitizesDisplayNameToSafeFile() {
        assertEquals("qwen-3b.gguf", LocalModelFiles.sanitizeName("qwen-3b.gguf"))
        // SAF 显示名可能带目录段：只保留基名（防目录穿越）
        assertEquals("qwen-3b.gguf", LocalModelFiles.sanitizeName("../../etc/qwen-3b.gguf"))
        assertEquals("qwen-3b.gguf", LocalModelFiles.sanitizeName("C:\\models\\qwen-3b.gguf"))
        // 非法字符被剔除
        assertEquals("abc.gguf", LocalModelFiles.sanitizeName("a:b*c?.gguf"))
        // 首尾点/空格清理
        assertEquals("m.gguf", LocalModelFiles.sanitizeName("  ..m.gguf..  "))
    }

    @Test
    fun rejectsUnsupportedOrEmptyNames() {
        assertNull(LocalModelFiles.sanitizeName(""))
        assertNull(LocalModelFiles.sanitizeName("   "))
        assertNull(LocalModelFiles.sanitizeName("model.bin"))
        assertNull(LocalModelFiles.sanitizeName(".gguf"))
        assertNull(LocalModelFiles.sanitizeName(".."))
        assertNull(LocalModelFiles.sanitizeName("/"))
    }

    @Test
    fun truncatesLongStemKeepingExtension() {
        val long = "a".repeat(400) + ".gguf"
        val safe = LocalModelFiles.sanitizeName(long)!!
        assertEquals(LocalModelFiles.MAX_STEM_LENGTH + LocalModelFiles.EXTENSION.length, safe.length)
        assertTrue(safe.endsWith(".gguf"))
    }

    @Test
    fun uniqueNameAppendsSuffixOnCollision() {
        assertEquals("m.gguf", LocalModelFiles.uniqueName("m.gguf", emptyList()))
        assertEquals("m-1.gguf", LocalModelFiles.uniqueName("m.gguf", listOf("m.gguf")))
        assertEquals("m-2.gguf", LocalModelFiles.uniqueName("m.gguf", listOf("m.gguf", "m-1.gguf")))
        // 大小写不敏感：避免同名文件在大小写无感实现上互相覆盖
        assertEquals("m-1.gguf", LocalModelFiles.uniqueName("m.gguf", listOf("M.GGUF")))
    }

    @Test
    fun formatsSizesWithFixedUnits() {
        assertEquals("512 B", LocalModelFiles.formatSize(512))
        assertEquals("1.0 KB", LocalModelFiles.formatSize(1024))
        assertEquals("1.5 MB", LocalModelFiles.formatSize(1024L * 1024 * 3 / 2))
        assertEquals("2.00 GB", LocalModelFiles.formatSize(1024L * 1024 * 1024 * 2))
        assertEquals("未知", LocalModelFiles.formatSize(-1))
    }
}
