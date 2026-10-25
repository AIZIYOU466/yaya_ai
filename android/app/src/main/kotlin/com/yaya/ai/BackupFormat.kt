package com.yaya.ai

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/** 备份失败：消息直接展示给用户（R15 不静默），不得吞掉。 */
class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 备份容器格式（ROADMAP 任务 34）——纯 JVM 实现，不依赖 Android API，可在 `src/test` 直接单测。
 *
 * 文件布局（大端）：
 * ```
 * "YAYABAK1" (8B 魔数) | version (1B) | kdf 迭代数 (4B) | salt (16B) | nonce (12B) | AES-GCM 密文 + 16B tag
 * ```
 * 明文是标准 zip：`meta/manifest.json` + 数据条目（`shared_prefs` 下的 XML、`files/workspace` 与
 * `files/skills` 目录下的文件）。
 *
 * 加密参数：PBKDF2-HMAC-SHA1 派生 256 位密钥（minSdk 24 可用，避免 `PBKDF2WithHmacSHA256`
 * 的 API 26 限制），AES-256-GCM（128 位认证标签）。密码错误或文件被篡改时 GCM 校验失败，
 * 一律报「密码错误或备份文件已损坏」，绝不解出半截数据。
 */
object BackupFormat {
    /** 魔数含格式主版本，便于肉眼识别文件类型。 */
    private val MAGIC = "YAYABAK1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val ITERATIONS = 210_000
    const val MIN_PASSWORD_LENGTH = 8
    const val FILE_SUFFIX = ".yayabak"
    const val MIME = "application/octet-stream"

    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_ITERATIONS = 10_000_000
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private const val BUFFER = 64 * 1024

    /** 归档里的清单条目名（解包后被 BackupHost 校验格式与服务端可读信息）。 */
    const val MANIFEST_ENTRY = "meta/manifest.json"

    /**
     * 把 [files]（条目名 → 源文件）打成 zip 写到 [dest]；[meta] 写到 `meta/` 下作为清单。
     * 返回条目总数（含清单）。
     */
    fun zipFiles(dest: File, files: List<Pair<String, File>>, meta: Map<String, String>): Int {
        var count = 0
        ZipOutputStream(BufferedOutputStream(dest.outputStream(), BUFFER)).use { zos ->
            meta.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(safeEntryName("meta/$name")))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                count++
            }
            files.forEach { (name, file) ->
                if (!file.isFile) throw BackupException("备份文件不存在：$name")
                zos.putNextEntry(ZipEntry(safeEntryName(name)))
                file.inputStream().use { it.copyTo(zos, BUFFER) }
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    /**
     * 加密 [input] 到 [out]（写入完整容器）。
     *
     * 约定：本方法**负责关闭** [out]（GCM 的认证标签在 close 时写出），调用方不要再嵌套 `use`。
     */
    fun seal(input: File, out: OutputStream, password: CharArray) {
        if (password.size < MIN_PASSWORD_LENGTH) {
            runCatching { out.close() }
            throw BackupException("密码至少 $MIN_PASSWORD_LENGTH 位")
        }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, nonce))
        }
        val cos = CipherOutputStream(out, cipher)
        try {
            out.write(buildHeader(salt, nonce, ITERATIONS))
            input.inputStream().use { it.copyTo(cos, BUFFER) }
        } finally {
            cos.close()
        }
        out.flush()
    }

    /**
     * 解密 [input] 到 [dest]；密码错误/内容损坏时抛 [BackupException] 并删除不完整的 [dest]。
     *
     * 约定：**不关闭** [input]（由调用方 `use` 管理）；读取到 EOF 时 GCM 标签校验完成。
     */
    fun open(input: InputStream, dest: File, password: CharArray) {
        val bin = BufferedInputStream(input, BUFFER)
        val magic = ByteArray(MAGIC.size)
        readFully(bin, magic)
        if (!magic.contentEquals(MAGIC)) {
            throw BackupException("不是 YAYai 备份文件（文件头不匹配）")
        }
        val version = bin.read()
        if (version != VERSION) {
            throw BackupException("备份格式版本 $version 不受支持（当前支持 $VERSION）")
        }
        val iterations = readInt(bin)
        if (iterations <= 0 || iterations > MAX_ITERATIONS) {
            throw BackupException("备份文件头损坏（KDF 迭代数 $iterations）")
        }
        val salt = ByteArray(SALT_BYTES).also { readFully(bin, it) }
        val nonce = ByteArray(NONCE_BYTES).also { readFully(bin, it) }
        val key = deriveKey(password, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        }
        try {
            val cis = CipherInputStream(bin, cipher)
            dest.outputStream().use { fos -> cis.copyTo(fos, BUFFER) }
        } catch (e: IOException) {
            dest.delete()
            throw BackupException("密码错误或备份文件已损坏", e)
        } catch (e: GeneralSecurityException) {
            dest.delete()
            throw BackupException("密码错误或备份文件已损坏", e)
        }
        if (!dest.isFile || dest.length() == 0L) {
            dest.delete()
            throw BackupException("密码错误或备份文件已损坏")
        }
    }

    /**
     * 解包 [zip] 到 [destRoot]，返回文件条目数。
     *
     * 防目录穿越（zip slip）：绝对路径、含 `..` 的条目、规范化后越界的条目一律拒绝；
     * 同时限制条目数与解压总量（半可逆操作，先校验后落盘）。
     */
    fun extract(zip: File, destRoot: File): Int {
        destRoot.mkdirs()
        val rootPath = destRoot.canonicalFile.path
        var files = 0
        var entries = 0
        var total = 0L
        ZipInputStream(BufferedInputStream(zip.inputStream(), BUFFER)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw BackupException("备份条目过多（上限 $MAX_ENTRIES）")
                val raw = entry.name.replace('\\', '/')
                if (raw.startsWith("/") || raw.split('/').any { it == ".." }) {
                    throw BackupException("备份包含非法路径：${entry.name}")
                }
                val target = File(destRoot, raw).canonicalFile
                val targetPath = target.path
                if (!targetPath.startsWith(rootPath) ||
                    (targetPath.length > rootPath.length &&
                        targetPath[rootPath.length] != File.separatorChar)
                ) {
                    throw BackupException("备份包含越界路径：${entry.name}")
                }
                when {
                    entry.isDirectory -> target.mkdirs()
                    raw.endsWith("/") -> target.mkdirs()
                    else -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { fos ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_TOTAL_BYTES) {
                                    throw BackupException("备份解压超过上限（${MAX_TOTAL_BYTES / 1024 / 1024}MB）")
                                }
                                fos.write(buf, 0, n)
                            }
                        }
                        files++
                    }
                }
                zis.closeEntry()
            }
        }
        return files
    }

    private fun buildHeader(salt: ByteArray, nonce: ByteArray, iterations: Int): ByteArray {
        val header = ByteArray(MAGIC.size + 1 + 4 + salt.size + nonce.size)
        var p = 0
        MAGIC.copyInto(header, p); p += MAGIC.size
        header[p++] = VERSION.toByte()
        header[p++] = (iterations ushr 24).toByte()
        header[p++] = (iterations ushr 16).toByte()
        header[p++] = (iterations ushr 8).toByte()
        header[p++] = iterations.toByte()
        salt.copyInto(header, p); p += salt.size
        nonce.copyInto(header, p)
        return header
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): javax.crypto.SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            return javax.crypto.spec.SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun safeEntryName(name: String): String {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty() || normalized.split('/').any { it == ".." }) {
            throw BackupException("非法归档条目：$name")
        }
        return normalized
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw BackupException("不是 YAYai 备份文件（文件过短）")
            off += n
        }
    }

    private fun readInt(input: InputStream): Int {
        val b = ByteArray(4)
        readFully(input, b)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }
}
