package com.yaya.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 真实 sshd 集成测试（ROADMAP 任务 35，R15 可验证性）。
 *
 * 容器/CI 没有 sshd 时自动跳过；本地验证方式：
 * ```
 * YAYA_SSH_TEST_HOST=127.0.0.1 YAYA_SSH_TEST_PORT=2222 \
 * YAYA_SSH_TEST_USER=sshtest YAYA_SSH_TEST_KEY=/tmp/key \
 * ./gradlew :app:testDebugUnitTest --tests '*SshClientIntegrationTest*'
 * ```
 */
class SshClientIntegrationTest {
    private val host = System.getenv("YAYA_SSH_TEST_HOST")
    private val port = System.getenv("YAYA_SSH_TEST_PORT")?.toIntOrNull() ?: 22
    private val user = System.getenv("YAYA_SSH_TEST_USER")
    private val keyPath = System.getenv("YAYA_SSH_TEST_KEY")
    private val passphrase = System.getenv("YAYA_SSH_TEST_KEY_PASSPHRASE")

    private class MemoryPins : HostKeyPins {
        val map = mutableMapOf<String, String>()
        override fun lookup(host: String, port: Int): String? = map["$host:$port"]
        override fun accept(host: String, port: Int, fingerprint: String) {
            map["$host:$port"] = fingerprint
        }
    }

    private fun config() = SshConfig(
        host = host!!,
        port = port,
        user = user!!,
        auth = SshAuth.PrivateKey(keyPath!!, passphrase),
        remoteWorkspace = REMOTE_DIR,
        connectTimeoutMs = 10_000,
    )

    @Before
    fun requireServer() {
        Assume.assumeTrue(
            "未设置 YAYA_SSH_TEST_HOST/USER/KEY，跳过真实 sshd 集成测试",
            !host.isNullOrBlank() && !user.isNullOrBlank() && !keyPath.isNullOrBlank() &&
                File(keyPath).isFile,
        )
    }

    @Test
    fun execReturnsOutputAndExitCode() {
        val pins = MemoryPins()
        SshClient(config(), pins).use { client ->
            client.connect()
            assertEquals(
                "主机密钥应在首次连接时钉扎",
                1,
                pins.map.size,
            )
            assertNotNull(client.fingerprint)
            val (out, code) = client.exec("printf 'hello\\n'; exit 3", 10_000)
            assertTrue("输出应包含 hello，实际: $out", out.contains("hello"))
            assertEquals(3, code)
        }
    }

    @Test
    fun sftpRoundTrip() {
        val client = SshClient(config(), MemoryPins())
        client.use {
            client.connect()
            client.exec("rm -rf $REMOTE_DIR && mkdir -p $REMOTE_DIR/sub", 10_000)

            assertFalse(client.exists("$REMOTE_DIR/none.txt"))
            assertEquals(true, client.write("$REMOTE_DIR/sub/a.txt", "第一行\nsecond\n", overwrite = true))
            assertTrue(client.exists("$REMOTE_DIR/sub/a.txt"))
            assertEquals("第一行\nsecond\n", client.read("$REMOTE_DIR/sub/a.txt", 1024 * 1024))

            val names = client.list("$REMOTE_DIR/sub").map { it.name to it.isDir }
            assertEquals(listOf("a.txt" to false), names)

            // overwrite=false 时已存在必须显式失败
            assertThrows(SshException::class.java) {
                client.write("$REMOTE_DIR/sub/a.txt", "x", overwrite = false)
            }
            // 自动建父目录
            assertEquals(true, client.write("$REMOTE_DIR/deep/b.txt", "b", overwrite = true))
            assertTrue(client.exists("$REMOTE_DIR/deep/b.txt"))

            client.delete("$REMOTE_DIR/sub/a.txt")
            assertFalse(client.exists("$REMOTE_DIR/sub/a.txt"))
            client.delete("$REMOTE_DIR/sub")
            client.delete("$REMOTE_DIR/deep/b.txt")
            client.delete("$REMOTE_DIR/deep")
            client.delete("$REMOTE_DIR")
        }
    }

    @Test
    fun changedHostKeyIsRejected() {
        val pins = MemoryPins()
        pins.map["$host:$port"] = "SHA256:0000"
        val client = SshClient(config(), pins)
        val error = try {
            client.connect()
            null
        } catch (e: SshException) {
            e
        } finally {
            client.close()
        }
        assertNotNull("指纹不匹配时必须拒绝连接", error)
        assertTrue("错误信息应提示主机密钥不一致，实际: ${error!!.message}", error.message!!.contains("主机密钥"))
    }

    private companion object {
        const val REMOTE_DIR = "/tmp/yaya-ssh-test"
    }
}
