package com.yaya.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** SSH 后端配置（持久化于 `FlutterSharedPreferences` 的 `flutter.ai_ssh`）。 */
data class SshSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val auth: String = AUTH_PASSWORD,
    val password: String = "",
    val keyPath: String = "",
    val passphrase: String = "",
    val remoteWorkspace: String = "/workspace",
) {
    /** 必填项是否齐全（不探测网络，仅本地校验）。 */
    val isComplete: Boolean
        get() = host.isNotBlank() && user.isNotBlank() && when (auth) {
            AUTH_KEY -> keyPath.isNotBlank()
            else -> password.isNotEmpty()
        }

    fun toClientConfig(): SshConfig = SshConfig(
        host = host.trim(),
        port = port.coerceIn(1, 65535),
        user = user.trim(),
        auth = if (auth == AUTH_KEY) {
            SshAuth.PrivateKey(keyPath, passphrase.ifEmpty { null })
        } else {
            SshAuth.Password(password)
        },
        remoteWorkspace = remoteWorkspace.ifBlank { "/workspace" },
    )

    companion object {
        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"
    }
}

/**
 * 远程 SSH 执行后端（ROADMAP 任务 35）。
 *
 * 定位（R5）：**平台能力**，不是 core 逻辑 —— SSH 只替换 `terminal_exec` 与文件工具的执行通道，
 * 工具语义/路径校验/授权仍由 core 唯一规范源决定。终端页与 Git 页维持本地 proot（本次范围）。
 *
 * 行为：
 * - 配置在 `flutter.ai_ssh`；私钥经 SAF 导入应用私有目录（`filesDir/ssh/keys/`），不进工作区。
 * - 主机密钥 TOFU 钉扎存 `flutter.ai_ssh_pins`；指纹变化拒绝连接，用户可在设置中重置。
 * - 单一连接复用（`SshClient` 串行化），配置变更或连接类错误时重建并重试一次。
 * - 失败抛 [SshException]（R15 不静默）。
 *
 * 已知限制：密码/口令明文存 SharedPreferences（与应用现有 API Key 存储口径一致，备份走加密容器）。
 */
object SshHost {
    private const val PREFS = "FlutterSharedPreferences"
    const val KEY_SSH = "flutter.ai_ssh"
    private const val KEY_PINS = "flutter.ai_ssh_pins"

    private val lock = Any()
    private var client: SshClient? = null
    private var clientSignature: String? = null

    fun load(context: Context): SshSettings {
        val raw = prefs(context).getString(KEY_SSH, null) ?: return SshSettings()
        return try {
            val o = JSONObject(raw)
            SshSettings(
                enabled = o.optBoolean("enabled", false),
                host = o.optString("host"),
                port = o.optInt("port", 22),
                user = o.optString("user"),
                auth = if (o.optString("auth") == SshSettings.AUTH_KEY) {
                    SshSettings.AUTH_KEY
                } else {
                    SshSettings.AUTH_PASSWORD
                },
                password = o.optString("password"),
                keyPath = o.optString("keyPath"),
                passphrase = o.optString("passphrase"),
                remoteWorkspace = o.optString("remoteWorkspace").ifBlank { "/workspace" },
            )
        } catch (_: Exception) {
            SshSettings()
        }
    }

    fun save(context: Context, settings: SshSettings) {
        prefs(context).edit()
            .putString(KEY_SSH, toJson(settings).toString())
            .apply()
        synchronized(lock) { closeLocked() }
    }

    /** 已启用且本地必填齐全（不做网络探测，探测会阻塞调用线程）。 */
    fun isActive(context: Context): Boolean =
        load(context).let { it.enabled && it.isComplete }

    fun remoteWorkspace(context: Context): String =
        load(context).remoteWorkspace.ifBlank { "/workspace" }

    /** 已记录的主机密钥指纹（`SHA256:...`），无记录返回 null。 */
    fun pinnedFingerprint(context: Context): String? {
        val cfg = load(context)
        if (cfg.host.isBlank()) return null
        return pins(context).lookup(cfg.host.trim(), cfg.port.coerceIn(1, 65535))
    }

    /** 清除当前主机指纹（服务器换钥或确认无中间人后由用户显式操作）。 */
    fun resetHostKey(context: Context) {
        val cfg = load(context)
        if (cfg.host.isBlank()) return
        val map = pinsJson(context)
        map.remove(pinKey(cfg.host, cfg.port.coerceIn(1, 65535)))
        prefs(context).edit().putString(KEY_PINS, map.toString()).apply()
        synchronized(lock) { closeLocked() }
    }

    /** 探活 + 返回指纹：`{ok,message,fingerprint}`（不改动 enabled，供设置页测试按钮）。 */
    fun test(context: Context, settings: SshSettings): String {
        if (!settings.isComplete) {
            return JSONObject().put("ok", false)
                .put("message", "配置不完整：需要 host / user / 密码或私钥")
                .toString()
        }
        val probe = SshClient(settings.toClientConfig(), pins(context))
        return try {
            probe.connect()
            val (out, code) = probe.exec("uname -srm 2>/dev/null || echo unknown", 10_000)
            val fp = probe.fingerprint.orEmpty()
            JSONObject().put("ok", true)
                .put("message", "连接成功（退出码 $code）：${out.lineSequence().firstOrNull().orEmpty()}")
                .put("fingerprint", fp)
                .toString()
        } catch (e: SshException) {
            JSONObject().put("ok", false)
                .put("message", e.message ?: "连接失败")
                .put("fingerprint", probe.fingerprint.orEmpty())
                .toString()
        } finally {
            probe.close()
        }
    }

    /** 导入私钥到应用私有目录，返回路径（SAF 选中文件的副本）。 */
    fun importKey(context: Context, uri: Uri): String {
        val dir = File(context.filesDir, "ssh/keys").apply { mkdirs() }
        var displayName = ""
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !cursor.isNull(idx)) displayName = cursor.getString(idx)
                }
            }
        val safeName = displayName
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in "/\\:*?\"<>|" }
            .trim()
            .trim('.')
            .ifEmpty { "id_key" }
        val target = File(dir, "${System.currentTimeMillis()}-$safeName")
        val part = File(dir, "${target.name}.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw SshException("无法读取所选私钥文件")
            input.use { ins -> part.outputStream().use { out -> ins.copyTo(out) } }
            if (part.length() == 0L) throw SshException("所选私钥文件为空")
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target.absolutePath
        } catch (e: Exception) {
            part.delete()
            target.delete()
            throw if (e is SshException) e else SshException("导入私钥失败：${e.message}")
        }
    }

    // ---------- Agent 执行通道 ----------

    /** `terminal_exec`：远端命令 + 退出码（供 core 的 ExitCode 验证器断言）。 */
    fun exec(context: Context, command: String, timeoutMs: Long): Pair<String, Int> =
        withClient(context) { it.exec(command, timeoutMs) }

    fun list(context: Context, path: String): List<RemoteEntry> =
        withClient(context) { it.list(remotePath(context, path)) }

    fun read(context: Context, path: String): String =
        withClient(context) { it.read(remotePath(context, path), MAX_READ_BYTES) }

    fun exists(context: Context, path: String): Boolean =
        withClient(context) { it.exists(remotePath(context, path)) }

    fun write(context: Context, path: String, content: String, overwrite: Boolean): Boolean =
        withClient(context) { it.write(remotePath(context, path), content, overwrite) }

    fun delete(context: Context, path: String) =
        withClient(context) { it.delete(remotePath(context, path)) }

    /** 相对路径 → 远端绝对路径；越界即显式报错（与 R19 同语义）。 */
    private fun remotePath(context: Context, path: String): String =
        RemotePaths.resolve(remoteWorkspace(context), path)
            ?: throw SshException("路径越界或非法: $path")

    /** 关闭复用连接（应用退出/配置变更时调用）。 */
    fun close() {
        synchronized(lock) { closeLocked() }
    }

    // ---------- 内部 ----------

    private fun <T> withClient(context: Context, block: (SshClient) -> T): T {
        val settings = load(context)
        if (!settings.enabled) throw SshException("远程 SSH 未启用")
        if (!settings.isComplete) throw SshException("远程 SSH 配置不完整（host / user / 认证）")
        val clientConfig = settings.toClientConfig()
        val signature = signature(settings)
        synchronized(lock) {
            val first = ensureClient(clientConfig, signature, pins(context))
            try {
                return block(first)
            } catch (e: Exception) {
                if (!isConnectionError(e)) throw e
                closeLocked()
                return block(ensureClient(clientConfig, signature, pins(context)))
            }
        }
    }

    private fun ensureClient(config: SshConfig, signature: String, pins: HostKeyPins): SshClient {
        val cached = client
        if (cached != null && clientSignature == signature && cached.isConnected()) return cached
        closeLocked()
        val fresh = SshClient(config, pins)
        fresh.connect()
        client = fresh
        clientSignature = signature
        return fresh
    }

    private fun closeLocked() {
        runCatching { client?.close() }
        client = null
        clientSignature = null
    }

    /** 只对连接级错误重连重试；业务错误（文件不存在等）原样上抛。 */
    private fun isConnectionError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is com.jcraft.jsch.JSchException) return true
            t = t.cause
        }
        return false
    }

    private fun signature(s: SshSettings): String {
        val keyStamp = if (s.keyPath.isBlank()) 0L else File(s.keyPath).lastModified()
        return listOf(
            s.host.trim(), s.port.toString(), s.user.trim(), s.auth,
            s.keyPath, keyStamp.toString(),
            s.password.hashCode().toString(),
            s.passphrase.hashCode().toString(),
            s.remoteWorkspace.ifBlank { "/workspace" },
        ).joinToString("|")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun toJson(s: SshSettings): JSONObject = JSONObject().apply {
        put("enabled", s.enabled)
        put("host", s.host.trim())
        put("port", s.port.coerceIn(1, 65535))
        put("user", s.user.trim())
        put("auth", s.auth)
        put("password", s.password)
        put("keyPath", s.keyPath)
        put("passphrase", s.passphrase)
        put("remoteWorkspace", s.remoteWorkspace.ifBlank { "/workspace" })
    }

    private fun pins(context: Context): HostKeyPins = object : HostKeyPins {
        override fun lookup(host: String, port: Int): String? {
            val map = pinsJson(context)
            return map.optString(pinKey(host, port)).takeIf { it.isNotBlank() }
        }

        override fun accept(host: String, port: Int, fingerprint: String) {
            val map = pinsJson(context)
            map.put(pinKey(host, port), fingerprint)
            prefs(context).edit().putString(KEY_PINS, map.toString()).apply()
        }
    }

    private fun pinsJson(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_PINS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun pinKey(host: String, port: Int): String =
        "${host.trim().lowercase(Locale.ROOT)}:$port"

    /** 单次读取上限：与本地工作区一致（10MB）。 */
    private const val MAX_READ_BYTES = 10L * 1024 * 1024
}
