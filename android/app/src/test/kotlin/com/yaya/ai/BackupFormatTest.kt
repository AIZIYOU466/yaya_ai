package com.yaya.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 备份容器层单测（ROADMAP 任务 34）：加密往返 / 错误密码 / 解包与 zip-slip 防护 / 配置解析。 */
class BackupFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zipWith(entries: Map<String, String>): File {
        val zip = tmp.newFile("payload.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return zip
    }

    @Test
    fun sealAndOpenRoundTrip() {
        val root = tmp.newFolder("data")
        File(root, "workspace/src").mkdirs()
        val file = File(root, "workspace/src/main.kt")
        file.writeText("fun main() = Unit")
        val plainZip = tmp.newFile("backup.zip")
        val count = BackupFormat.zipFiles(
            plainZip,
            listOf("files/workspace/src/main.kt" to file),
            mapOf("manifest.json" to """{"format":"yayai-backup"}"""),
        )
        assertEquals(2, count)

        val sealed = tmp.newFile("backup.yayabak")
        BackupFormat.seal(plainZip, sealed.outputStream(), "correct-horse".toCharArray())
        assertTrue(sealed.length() > plainZip.length())

        val opened = tmp.newFile("opened.zip")
        sealed.inputStream().use { BackupFormat.open(it, opened, "correct-horse".toCharArray()) }
        assertEquals(plainZip.readBytes().size, opened.readBytes().size)

        val out = tmp.newFolder("restored")
        assertEquals(2, BackupFormat.extract(opened, out))
        assertTrue(File(out, BackupFormat.MANIFEST_ENTRY).isFile)
        assertEquals("fun main() = Unit", File(out, "files/workspace/src/main.kt").readText())
    }

    @Test
    fun wrongPasswordFailsWithoutLeavingPlaintext() {
        val plainZip = zipWith(mapOf("meta/manifest.json" to "{}"))
        val sealed = tmp.newFile("backup.yayabak")
        BackupFormat.seal(plainZip, sealed.outputStream(), "correct-horse".toCharArray())

        val dest = File(tmp.root, "should-not-exist.zip")
        try {
            sealed.inputStream().use { BackupFormat.open(it, dest, "wrong-password".toCharArray()) }
            fail("错误密码必须失败")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("密码"))
        }
        assertFalse(dest.exists())
    }

    @Test
    fun shortPasswordIsRejected() {
        val payload = zipWith(mapOf("a.txt" to "a"))
        try {
            BackupFormat.seal(payload, tmp.newFile("short.yayabak").outputStream(), "1234567".toCharArray())
            fail("密码过短必须失败")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("密码至少"))
        }
    }

    @Test
    fun nonBackupFileIsRejected() {
        val junk = tmp.newFile("junk.bin")
        junk.writeBytes(ByteArray(64) { 7 })
        try {
            junk.inputStream().use { BackupFormat.open(it, tmp.newFile("out.zip"), "correct-horse".toCharArray()) }
            fail("非备份文件必须失败")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("不是 YAYai 备份文件"))
        }
    }

    @Test
    fun extractRejectsZipSlip() {
        val zip = zipWith(mapOf("../escape.txt" to "pwned"))
        try {
            BackupFormat.extract(zip, tmp.newFolder("target"))
            fail("目录穿越必须被拒绝")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("非法路径"))
        }
    }

    @Test
    fun prefXmlParsesTypedValues() {
        val values = PrefXml.read(
            """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="flutter.ai_model_name">gpt-4o</string>
                <boolean name="flutter.agent_is_stream" value="true" />
                <int name="flutter.agent_mode" value="2" />
                <long name="agent_state.ts" value="1700000000000" />
                <float name="ui.scale" value="1.5" />
                <set name="flutter.tags">
                    <string>a</string>
                    <string>b</string>
                </set>
                <double name="future.value" value="0.5" />
            </map>
            """.trimIndent(),
        )
        assertEquals(PrefValue.Str("gpt-4o"), values["flutter.ai_model_name"])
        assertEquals(PrefValue.Flag(true), values["flutter.agent_is_stream"])
        assertEquals(PrefValue.Num(2, integer = true), values["flutter.agent_mode"])
        assertEquals(PrefValue.Num(1700000000000, integer = false), values["agent_state.ts"])
        assertEquals(PrefValue.Real(1.5f), values["ui.scale"])
        assertEquals(PrefValue.StrSet(setOf("a", "b")), values["flutter.tags"])
        assertEquals(PrefValue.Unsupported("double"), values["future.value"])
    }
}
