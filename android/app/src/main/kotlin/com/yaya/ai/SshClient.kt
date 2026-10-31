package com.yaya.ai

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties

/** SSH 会话/通道失败（面向用户的中文原因，保留原始异常做 cause）。 */
class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** SSH 认证方式。 */
sealed class SshAuth {
    data class Password(val password: String) : SshAuth()
    data class PrivateKey(val path: String, val passphrase: String?) : SshAuth()
}

/** SSH 连接参数（纯数据，平台层从配置构造）。 */
data class SshConfig(
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: SshAuth,
    val remoteWorkspace: String = "/workspace",
    val connectTimeoutMs: Int = 15_000,
)

/** 主机密钥钉扎（TOFU）：平台持久化，纯 JVM 测试用内存实现。 */
interface HostKeyPins {
    /** 已记录的指纹（`SHA256:<hex>`）；null = 首次连接。 */
    fun lookup(host: String, port: Int): String?

    /** 记录首次接受的指纹。 */
    fun accept(host: String, port: Int, fingerprint: String)
}

/** 远端目录条目。 */
data class RemoteEntry(val name: String, val isDir: Boolean, val size: Long)

/**
 * 纯 JVM 的 SSH 客户端（JSch），不含 Android API —— 可在容器内对真实 sshd 验证（R15 可验证性）。
 *
 * 安全语义：
 * - **主机密钥钉扎（TOFU）**：首次连接记录 SHA-256 指纹；之后指纹变化一律拒绝并给出明确
 *   提示（可能是中间人攻击或服务器换钥），由用户显式重置。
 * - 只使用密码/私钥认证；`StrictHostKeyChecking=yes` 仅作兜底，实际判定在 [HostKeyPins]。
 * - 所有操作串行化（SFTP 通道非线程安全；与 proot 单容器串行语义一致）。
 *
 * 失败一律抛 [SshException]（R15 不静默）。
 */
class SshClient(
    private val config: SshConfig,
    private val pins: HostKeyPins,
) : AutoCloseable {
    private val lock = Any()
    private var session: Session? = null
    private var sftpChannel: ChannelSftp? = null

    /** 本次会话协商出的主机密钥指纹（连接成功后可用）。 */
    @Volatile
    var fingerprint: String? = null
        private set

    fun isConnected(): Boolean = session?.isConnected == true

    fun connect() {
        synchronized(lock) { ensureSession() }
    }

    // ---------- exec ----------

    /**
     * 执行远端命令，返回 `(输出, 退出码)`。stdout 与 stderr 合并（与本地 proot 行为一致）；
     * 输出超过 [MAX_OUTPUT_BYTES] 截断并显式提示。超时抛 [SshException]。
     */
    fun exec(command: String, timeoutMs: Long): Pair<String, Int> = synchronized(lock) {
        val ch = openExec(command)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        var truncated = false
        try {
            ch.setErrStream(err, true)
            ch.connect(config.connectTimeoutMs)
            val reader = Thread {
                truncated = copyCapped(ch.inputStream, out)
            }.apply {
                isDaemon = true
                name = "ssh-exec-reader"
                start()
            }
            reader.join(timeoutMs)
            if (reader.isAlive) {
                ch.disconnect()
                reader.join(1_000)
                throw SshException("命令超时（${timeoutMs}ms）")
            }
            // exit-status 可能与 EOF 到达有微小时间差，最多等 3 秒。
            val deadline = System.currentTimeMillis() + 3_000
            while (ch.exitStatus < 0 && !ch.isClosed && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val text = buildString {
                append(out.toByteArray().toString(Charsets.UTF_8))
                val stderr = err.toByteArray().toString(Charsets.UTF_8)
                if (stderr.isNotBlank()) {
                    if (isNotEmpty() && !endsWith("\n")) append('\n')
                    append(stderr)
                }
                if (truncated) {
                    if (isNotEmpty() && !endsWith("\n")) append('\n')
                    append("[输出过长，已截断至 ${MAX_OUTPUT_BYTES / 1024}KB]")
                }
            }
            text.trimEnd() to ch.exitStatus
        } catch (e: SshException) {
            throw e
        } catch (e: Exception) {
            throw SshException("SSH 命令执行失败：${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            ch.disconnect()
        }
    }

    // ---------- SFTP ----------

    fun list(path: String): List<RemoteEntry> = synchronized(lock) {
        val ch = requireSftp()
        try {
            val raw = ch.ls(path)
            raw.mapNotNull { item ->
                val entry = item as ChannelSftp.LsEntry
                val name = entry.filename
                if (name == "." || name == "..") null
                else RemoteEntry(name, entry.attrs.isDir, entry.attrs.size)
            }.sortedBy { it.name.lowercase() }
        } catch (e: SftpException) {
            throw sftpError("列出目录失败", path, e)
        }
    }

    /** 读取文件全文；超过 [maxBytes] 抛错（与本地工作区 10MB 上限一致）。 */
    fun read(path: String, maxBytes: Long): String = synchronized(lock) {
        val ch = requireSftp()
        try {
            val attrs = ch.lstat(path)
            if (attrs.isDir) throw SshException("尝试读取目录：$path")
            if (attrs.size > maxBytes) {
                throw SshException("文件过大（>${maxBytes / 1024}KB），请分段处理")
            }
            val buf = ByteArrayOutputStream()
            ch.get(path).use { input ->
                input.copyTo(buf, DEFAULT_BUFFER)
            }
            if (buf.size().toLong() > maxBytes) {
                throw SshException("文件过大（>${maxBytes / 1024}KB），请分段处理")
            }
            buf.toByteArray().toString(Charsets.UTF_8)
        } catch (e: SftpException) {
            throw sftpError("读取失败", path, e)
        }
    }

    fun exists(path: String): Boolean = synchronized(lock) {
        val ch = requireSftp()
        try {
            ch.lstat(path)
            true
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) false
            else throw sftpError("查询失败", path, e)
        }
    }

    /** 写入文件（自动建父目录）；返回是否新建。overwrite=false 且已存在时抛错。 */
    fun write(path: String, content: String, overwrite: Boolean): Boolean = synchronized(lock) {
        val ch = requireSftp()
        val created = try {
            ch.lstat(path)
            if (!overwrite) throw SshException("文件已存在：$path（overwrite=false）")
            false
        } catch (e: SftpException) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) true
            else throw sftpError("查询失败", path, e)
        }
        try {
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { mkdirs(ch, it) }
            val bytes = content.toByteArray(Charsets.UTF_8)
            ch.put(ByteArrayInputStream(bytes), path)
            created
        } catch (e: SftpException) {
            throw sftpError("写入失败", path, e)
        }
    }

    /** 删除文件或空目录。 */
    fun delete(path: String) = synchronized(lock) {
        val ch = requireSftp()
        try {
            val attrs = ch.lstat(path)
            if (attrs.isDir) ch.rmdir(path) else ch.rm(path)
        } catch (e: SftpException) {
            throw sftpError("删除失败", path, e)
        }
        Unit
    }

    override fun close() {
        synchronized(lock) {
            runCatching { sftpChannel?.disconnect() }
            sftpChannel = null
            runCatching { session?.disconnect() }
            session = null
        }
    }

    // ---------- 内部 ----------

    private fun ensureSession() {
        val current = session
        if (current != null && current.isConnected) return
        sftpChannel = null
        val jsch = JSch()
        val repo = PinnedHostKeys()
        jsch.hostKeyRepository = repo
        val auth = config.auth
        if (auth is SshAuth.PrivateKey) {
            val key = File(auth.path)
            if (!key.isFile) throw SshException("私钥文件不存在：${auth.path}")
            try {
                jsch.addIdentity(auth.path, auth.passphrase.orEmpty())
            } catch (e: JSchException) {
                throw SshException("私钥加载失败（格式需为 OpenSSH/PEM，口令需正确）：${e.message}", e)
            }
        }
        val s = try {
            jsch.getSession(config.user, config.host, config.port)
        } catch (e: JSchException) {
            throw SshException("SSH 会话创建失败：${e.message}", e)
        }
        if (auth is SshAuth.Password) s.setPassword(auth.password)
        val props = Properties()
        // 主机密钥由 PinnedHostKeys 判定；yes 仅作兜底，避免 JSch 走交互式提示。
        props["StrictHostKeyChecking"] = "yes"
        props["PreferredAuthentications"] =
            if (auth is SshAuth.PrivateKey) "publickey" else "password,keyboard-interactive"
        s.setConfig(props)
        s.serverAliveInterval = 30_000
        s.serverAliveCountMax = 3
        try {
            s.connect(config.connectTimeoutMs)
        } catch (e: JSchException) {
            runCatching { s.disconnect() }
            throw connectError(e)
        }
        session = s
    }

    private fun openExec(command: String): ChannelExec {
        ensureSession()
        val s = session ?: throw SshException("SSH 会话未建立")
        val ch = try {
            s.openChannel("exec") as ChannelExec
        } catch (e: JSchException) {
            throw SshException("打开 exec 通道失败：${e.message}", e)
        }
        ch.setCommand(command)
        ch.setInputStream(null)
        return ch
    }

    private fun requireSftp(): ChannelSftp {
        ensureSession()
        val existing = sftpChannel
        if (existing != null && existing.isConnected) return existing
        val s = session ?: throw SshException("SSH 会话未建立")
        val ch = try {
            s.openChannel("sftp") as ChannelSftp
        } catch (e: JSchException) {
            throw SshException("打开 SFTP 通道失败：${e.message}", e)
        }
        try {
            ch.connect(config.connectTimeoutMs)
        } catch (e: JSchException) {
            throw SshException("SFTP 通道连接失败：${e.message}", e)
        }
        sftpChannel = ch
        return ch
    }

    private fun mkdirs(ch: ChannelSftp, dir: String) {
        val norm = RemotePaths.normalize(dir) ?: throw SshException("非法远端目录：$dir")
        if (norm == "/") return
        var current = ""
        for (part in norm.trim('/').split('/')) {
            current += "/$part"
            try {
                ch.stat(current)
            } catch (e: SftpException) {
                if (e.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    throw sftpError("创建目录失败", current, e)
                }
                try {
                    ch.mkdir(current)
                } catch (mkdirError: SftpException) {
                    // 并发下可能已被创建：再确认一次，仍不行才算失败。
                    try {
                        ch.stat(current)
                    } catch (_: SftpException) {
                        throw sftpError("创建目录失败", current, mkdirError)
                    }
                }
            }
        }
    }

    private fun copyCapped(input: InputStream, out: ByteArrayOutputStream): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER)
        var truncated = false
        while (true) {
            val n = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) break
            val remaining = MAX_OUTPUT_BYTES - out.size()
            if (remaining <= 0) {
                truncated = true
                // 继续读空流，避免服务端因未读完而阻塞退出。
                continue
            }
            out.write(buffer, 0, minOf(n, remaining))
            if (n > remaining) truncated = true
        }
        return truncated
    }

    private fun sftpError(prefix: String, path: String, e: SftpException): SshException {
        val detail = when (e.id) {
            ChannelSftp.SSH_FX_NO_SUCH_FILE -> "路径不存在"
            ChannelSftp.SSH_FX_PERMISSION_DENIED -> "权限不足"
            else -> e.message ?: "SFTP 错误 ${e.id}"
        }
        return SshException("$prefix：$path（$detail）", e)
    }

    private fun connectError(e: JSchException): SshException {
        val msg = e.message.orEmpty()
        val lower = msg.lowercase()
        val reason = when {
            lower.contains("auth fail") || lower.contains("auth cancel") ->
                "认证失败（用户名/密码/私钥或口令不正确）"
            lower.contains("hostkey has been changed") || lower.contains("changed") ->
                "主机密钥与首次记录不一致（可能是中间人攻击或服务器更换了密钥）；" +
                    "确认无误后可在设置中重置主机密钥"
            lower.contains("unknownhostkey") ->
                "主机密钥未知（首次连接被拒绝）"
            lower.contains("connection refused") -> "连接被拒绝（检查主机/端口或 sshd 状态）"
            lower.contains("timeout") || lower.contains("timed out") -> "连接超时"
            lower.contains("network is unreachable") || lower.contains("no route") -> "网络不可达"
            msg.isBlank() -> "SSH 连接失败（无错误详情）"
            else -> "SSH 连接失败：$msg"
        }
        return SshException(reason, e)
    }

    /** TOFU 主机密钥仓库：首次记录，之后严格比对。 */
    private inner class PinnedHostKeys : HostKeyRepository {
        override fun check(host: String, key: ByteArray): Int {
            val fp = fingerprintOf(key)
            fingerprint = fp
            val pinned = pins.lookup(config.host, config.port)
            if (pinned == null) {
                pins.accept(config.host, config.port, fp)
                return HostKeyRepository.OK
            }
            return if (pinned == fp) HostKeyRepository.OK else HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

        override fun remove(host: String?, type: String?) = Unit

        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

        override fun getKnownHostsRepositoryID(): String = "yaya-pinned"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    companion object {
        private const val DEFAULT_BUFFER = 16 * 1024
        private const val MAX_OUTPUT_BYTES = 1024 * 1024

        /** SHA-256 指纹（`SHA256:<hex>`），不依赖 java.util.Base64（API 26+）。 */
        fun fingerprintOf(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            val hex = buildString(digest.size * 2) {
                for (b in digest) append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
            }
            return "SHA256:$hex"
        }
    }
}
