package com.yaya.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 内置 Linux rootfs 安装器（对标 AiCode 的 ContainerInstaller）。
 *
 * 来源：Alpine Linux 官方 minirootfs（aarch64），约 3.8MB，官方 SHA256 校验。
 * 下载 → 校验 → 解压（gzip 内置支持 + 自写 tar 解压，防路径穿越）→ 输出到 filesDir/alpine_rootfs。
 */
object RootfsInstaller {
    const val ROOTFS_DIR_NAME = "alpine_rootfs"
    // 官方目录核实（2026-10-03）：dl-cdn.alpinelinux.org v3.21 aarch64
    private const val ROOTFS_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.8-aarch64.tar.gz"
    private const val EXPECTED_SHA256 =
        "f25a96d2846a4bc439093107c1b48a8b0c93dcb411e2cb9cfded6f790b2bc001"

    fun rootfsDir(context: Context): File = File(context.filesDir, ROOTFS_DIR_NAME)

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "etc/alpine-release").exists()

    /** 阻塞式安装；返回结果文本，异常以文本形式返回（供后台线程调用）。 */
    fun install(context: Context): String {
        return try {
            val tmp = File(context.cacheDir, "rootfs.tar.gz")
            download(ROOTFS_URL, tmp)

            val actual = sha256(tmp)
            if (actual != EXPECTED_SHA256) {
                tmp.delete()
                return "校验失败：期望 $EXPECTED_SHA256\n实际 $actual（请重试）"
            }

            val dest = rootfsDir(context)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()
            tmp.inputStream().use { extractTar(it, dest) }
            tmp.delete()
            "Linux 环境安装完成（Alpine ${if (isInstalled(context)) "已就绪" else "校验异常"}）"
        } catch (e: Exception) {
            "安装失败：${e.message ?: e.toString()}"
        }
    }

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

    /** 解压 gzip tar 流到 dest。处理 GNU longname、符号链接；拒绝路径穿越。 */
    private fun extractTar(input: InputStream, dest: File) {
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

                val size = header.copyOfRange(124, 136)
                    .toString(Charsets.US_ASCII)
                    .trimEnd('\u0000', ' ')
                    .toLongOrNull() ?: 0L
                val type = header[156].toInt().toChar()

                if (type == 'L') {
                    // GNU longname：下一段数据即真实文件名
                    val data = ByteArray(size.toInt())
                    readFully(gz, data)
                    longName = String(data, Charsets.US_ASCII).trimEnd('\u0000')
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

                when (type) {
                    '5' -> target.mkdirs()
                    '2' -> {
                        // 符号链接：Android 沙箱内用 toybox ln 创建
                        val link = header.copyOfRange(157, 257)
                            .toString(Charsets.US_ASCII)
                            .trimEnd('\u0000', ' ')
                        target.parentFile?.mkdirs()
                        target.delete()
                        try {
                            Runtime.getRuntime()
                                .exec(arrayOf("ln", "-s", link, target.absolutePath))
                                .waitFor()
                        } catch (_: Exception) {
                        }
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
                skipFully(gz, (512 - (size % 512)) % 512)
            }
        }
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