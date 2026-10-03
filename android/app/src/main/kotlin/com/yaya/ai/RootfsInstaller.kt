package com.yaya.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** 一个 rootfs 镜像配置（内置或用户自定义）。 */
data class RootfsProfile(
    val id: String,
    val name: String,
    val url: String,
    val sha256: String,
    val note: String = "",
    val builtin: Boolean = true,
)

/**
 * 内置 Linux rootfs 安装器（对标 AiCode 的 ContainerInstaller / 镜像目录）。
 *
 * - 镜像目录：内置可信镜像（Alpine 官方）+ 用户自定义（URL + SHA256）。
 * - 每个镜像一个目录 filesDir/rootfs_<id>，安装完成写 .installed 标记。
 * - 下载 → SHA256 校验 → gzip tar 解压（防路径穿越）→ 版本标记。
 */
object RootfsInstaller {
    private const val PREFS = "yaya_rootfs"
    private const val KEY_CURRENT = "current"
    private const val KEY_CUSTOM = "custom_profiles"
    private const val MARKER = ".installed"

    private val BUILTIN = listOf(
        RootfsProfile(
            id = "alpine",
            name = "Alpine Linux 3.21",
            url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.8-aarch64.tar.gz",
            sha256 = "f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001",
            note = "官方 minirootfs，约 4MB，最快",
        ),
    )

    fun profiles(context: Context): List<RootfsProfile> = BUILTIN + customProfiles(context)

    fun currentId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CURRENT, null) ?: BUILTIN.first().id
    }

    fun setCurrent(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT, id).apply()
    }

    fun rootfsDir(context: Context, id: String): File {
        // 兼容旧版单镜像安装（alpine_rootfs）
        if (id == "alpine") {
            val current = File(context.filesDir, "rootfs_alpine")
            if (!current.exists()) {
                val legacy = File(context.filesDir, "alpine_rootfs")
                if (legacy.exists() && File(legacy, "etc/alpine-release").exists()) return legacy
            }
        }
        return File(context.filesDir, "rootfs_$id")
    }

    fun isInstalled(context: Context, id: String): Boolean =
        File(rootfsDir(context, id), MARKER).exists()

    fun isInstalledCurrent(context: Context): Boolean =
        isInstalled(context, currentId(context))

    /** 阻塞式安装指定镜像；返回结果文本（供后台线程调用）。 */
    fun install(context: Context, id: String): String {
        val profile = profiles(context).firstOrNull { it.id == id }
            ?: return "镜像不存在: $id"
        return try {
            val tmp = File(context.cacheDir, "rootfs-$id.tar.gz")
            download(profile.url, tmp)

            val actual = sha256(tmp)
            if (profile.sha256.isNotEmpty() && actual != profile.sha256) {
                tmp.delete()
                return "校验失败：期望 ${profile.sha256}\n实际 $actual（请重试）"
            }

            val dest = rootfsDir(context, id)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            val failed = tmp.inputStream().use { extractTar(it, dest) }
            tmp.delete()
            File(dest, MARKER).writeText("ok")
            val base = "镜像 ${profile.name} 安装完成"
            if (failed > 0) "$base（$failed 个条目被跳过）" else base
        } catch (e: Exception) {
            val trace = e.stackTrace.take(8).joinToString("\n") { "    at $it" }
            "安装失败：${e::class.java.simpleName}: ${e.message ?: e}\n$trace"
        }
    }

    fun reset(context: Context, id: String): String {
        val dir = rootfsDir(context, id)
        if (dir.exists()) dir.deleteRecursively()
        return "已重置 $id（下次启动容器前需重新安装）"
    }

    /** 添加自定义镜像（URL 需指向 tar.gz rootfs），返回新镜像 id。 */
    fun addCustom(context: Context, name: String, url: String, sha256: String): String {
        val n = name.trim()
        val u = url.trim()
        if (n.isEmpty() || u.isEmpty()) return "名称与 URL 不能为空"
        val id = "custom_${System.currentTimeMillis()}"
        val profile = RootfsProfile(id, n, u, sha256.trim(), note = "自定义", builtin = false)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = customProfiles(context).toMutableList().apply { add(profile) }
        prefs.edit().putString(KEY_CUSTOM, encode(list)).apply()
        return id
    }

    fun removeCustom(context: Context, id: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kept = customProfiles(context).filterNot { it.id == id }
        prefs.edit().putString(KEY_CUSTOM, encode(kept)).apply()
        return "已删除 $id"
    }

    private fun customProfiles(context: Context): List<RootfsProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOM, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size < 4) null
            else RootfsProfile(p[0], p[1], p[2], p[3], note = "自定义", builtin = false)
        }
    }

    private fun encode(list: List<RootfsProfile>): String =
        list.joinToString("\n") { "${it.id}|${it.name}|${it.url}|${it.sha256}" }

    private fun download(url: String, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "YAYai/1.0")
        }
        conn.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 解压 gzip tar 流到 dest。处理 GNU longname、符号链接；拒绝路径穿越。返回失败的条目数。 */
    private fun extractTar(input: InputStream, dest: File): Int {
        var failed = 0
        GZIPInputStream(input).use { gz ->
            val header = ByteArray(512)
            var longName: String? = null
            while (readFully(gz, header)) {
                if (header.all { it == 0.toByte() }) break

                val rawName = header.copyOfRange(0, 100)
                    .toString(Charsets.US_ASCII)
                    .trimEnd('\u0000', ' ')
                val name = longName ?: rawName
                longName = null

                // tar 的 size 字段是八进制（如 "00000000000000000520" = 336 字节）
                val size = header.copyOfRange(124, 136)
                    .toString(Charsets.US_ASCII)
                    .trimEnd('\u0000', ' ')
                    .toLongOrNull(8) ?: 0L
                val type = header[156].toInt().toChar()

                if (type == 'L') {
                    // GNU longname：下一段数据即真实文件名（数据后还有 512 对齐 padding）
                    val data = ByteArray(size.toInt())
                    readFully(gz, data)
                    longName = String(data, Charsets.US_ASCII).trimEnd('\u0000')
                    skipFully(gz, (512 - (size % 512)) % 512)
                    continue
                }

                // 路径穿越防护
                val clean = name.removePrefix("./").removePrefix("/")
                val parts = clean.split('/')
                if (parts.any { it == ".." } || clean.isEmpty()) {
                    skipFully(gz, size)
                    continue
                }
                val target = File(dest, clean)

                try {
                    when (type) {
                        '5' -> target.mkdirs()
                        '2' -> {
                            // 符号链接：Android 沙箱内用 toybox ln 创建
                            val link = header.copyOfRange(157, 257)
                                .toString(Charsets.US_ASCII)
                                .trimEnd('\u0000', ' ')
                            target.parentFile?.mkdirs()
                            target.delete()
                            val created = try {
                                Runtime.getRuntime()
                                    .exec(arrayOf("ln", "-s", link, target.absolutePath))
                                    .waitFor() == 0
                            } catch (_: Exception) {
                                false
                            }
                            if (!created) failed++
                        }
                        else -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(8192)
                                var remaining = size
                                while (remaining > 0) {
                                    val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    remaining -= n
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 单条目失败不中断安装；关键文件在则可继续使用
                    failed++
                }
                skipFully(gz, (512 - (size % 512)) % 512)
            }
        }
        return failed
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off > 0
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() < 0) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}