package com.yaya.ai

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * 加密备份与恢复（ROADMAP 任务 34）。
 *
 * 备份范围（明文 zip → [BackupFormat] 加密容器）：
 * - `shared_prefs` 下的 XML：全部配置（模型提供商 / MCP 服务器 / 运行模式 / 根fs 等）
 * - `files/workspace` 目录：工作区文件
 * - `files/skills` 目录：技能目录
 *
 * **不在范围**：会话/记忆数据库（`databases/yaya_agent.db`，运行中直接覆盖有损坏风险）、
 * rootfs 镜像（体积大且可重装）、任务断点（`agent_state`，恢复后会自动续跑旧任务）。
 *
 * 恢复语义：
 * - 配置经 SharedPreferences API 逐键写回（不覆盖 XML 文件——进程内缓存实例会把文件改动反向覆盖）
 * - 工作区/技能为**合并覆盖**：写入备份中的文件，不删除备份中不存在的现有文件
 * - 失败一律显式返回 `{ok:false,message}`（R15 不静默）
 */
object BackupHost {
    private const val PREFS_DIR = "shared_prefs"
    private const val BACKUP_FORMAT = "yayai-backup"
    private val DATA_DIRS = listOf("workspace", "skills")

    /** 不随备份恢复的配置：任务断点（恢复后会自动续跑旧任务）。 */
    private const val SKIP_RESTORE_PREFS = "agent_state"

    /** 备份内容概览（UI 展示用）：`{prefsFiles,workspaceFiles,skillsFiles,bytes}`。 */
    fun summary(context: Context): String = try {
        val payload = collect(context)
        val prefs = payload.files.count { it.first.startsWith("$PREFS_DIR/") }
        val workspace = payload.files.count { it.first.startsWith("files/workspace/") }
        val skills = payload.files.count { it.first.startsWith("files/skills/") }
        JSONObject().apply {
            put("ok", true)
            put("prefsFiles", prefs)
            put("workspaceFiles", workspace)
            put("skillsFiles", skills)
            put("bytes", payload.bytes)
        }.toString()
    } catch (e: Exception) {
        fail(e)
    }

    /**
     * 导出加密备份到 [uri]（SAF 目标文件）。密码至少 8 位。
     * 返回 `{ok,message,files,entries,bytes,size}`。
     */
    fun export(context: Context, uri: Uri, password: String): String {
        val tmp = File(context.cacheDir, "yaya-export-${System.currentTimeMillis()}.zip")
        return try {
            requirePassword(password)
            val payload = collect(context)
            if (payload.files.isEmpty()) throw BackupException("没有可备份的数据")
            val manifest = JSONObject().apply {
                put("format", BACKUP_FORMAT)
                put("formatVersion", BackupFormat.VERSION)
                put("createdAt", System.currentTimeMillis())
                put("appVersion", appVersion(context))
                put("entries", payload.files.size)
                put("includesDb", false)
            }
            BackupFormat.zipFiles(tmp, payload.files, mapOf("manifest.json" to manifest.toString()))
            val stream = context.contentResolver.openOutputStream(uri)
                ?: throw BackupException("无法写入所选文件")
            // seal 负责关闭 stream（GCM 认证标签在 close 时写出）
            BackupFormat.seal(tmp, stream, password.toCharArray())
            JSONObject().apply {
                put("ok", true)
                put("kind", "export")
                put("files", payload.files.size)
                put("bytes", payload.bytes)
                put("size", tmp.length())
                put(
                    "message",
                    "已导出 ${payload.files.size} 个文件（明文 ${formatBytes(payload.bytes)}，" +
                        "加密后 ${formatBytes(tmp.length())}）",
                )
            }.toString()
        } catch (e: Exception) {
            fail(e)
        } finally {
            tmp.delete()
        }
    }

    /**
     * 从 [uri] 恢复备份：配置立即生效，工作区/技能文件合并覆盖。
     * 返回 `{ok,message,prefsFiles,prefsKeys,dataFiles,unsupported,skippedPrefs}`。
     */
    fun restore(context: Context, uri: Uri, password: String): String {
        val tmpZip = File(context.cacheDir, "yaya-restore.zip")
        val staging = File(context.cacheDir, "yaya-restore")
        return try {
            requirePassword(password)
            tmpZip.delete()
            staging.deleteRecursively()
            staging.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: throw BackupException("无法读取所选文件")
            input.use { BackupFormat.open(it, tmpZip, password.toCharArray()) }
            BackupFormat.extract(tmpZip, staging)
            val manifest = readManifest(staging)

            var prefsFiles = 0
            var prefsKeys = 0
            var unsupported = 0
            var skippedPrefs = 0
            File(staging, PREFS_DIR).listFiles()?.sortedBy { it.name }?.forEach { xml ->
                if (!xml.isFile || !xml.name.endsWith(".xml")) return@forEach
                val prefsName = xml.name.removeSuffix(".xml")
                if (prefsName == SKIP_RESTORE_PREFS) {
                    skippedPrefs++
                    return@forEach
                }
                val values = PrefXml.read(xml)
                unsupported += values.values.count { it is PrefValue.Unsupported }
                prefsKeys += applyPrefs(context, prefsName, values)
                prefsFiles++
            }

            var dataFiles = 0
            DATA_DIRS.forEach { dirName ->
                val src = File(staging, "files/$dirName")
                if (src.isDirectory) dataFiles += copyTree(src, File(context.filesDir, dirName))
            }

            val detail = buildString {
                append("已恢复配置 $prefsFiles 个文件 / $prefsKeys 项")
                append("，工作区与技能 $dataFiles 个文件")
                if (unsupported > 0) append("；$unsupported 项配置类型不支持已跳过")
                if (skippedPrefs > 0) append("；任务断点状态未恢复（避免自动续跑旧任务）")
                append("；会话与记忆数据库不在备份范围")
            }
            JSONObject().apply {
                put("ok", true)
                put("kind", "restore")
                put("prefsFiles", prefsFiles)
                put("prefsKeys", prefsKeys)
                put("dataFiles", dataFiles)
                put("unsupported", unsupported)
                put("skippedPrefs", skippedPrefs)
                put("createdAt", manifest?.optLong("createdAt") ?: 0L)
                put("message", detail)
            }.toString()
        } catch (e: Exception) {
            fail(e)
        } finally {
            tmpZip.delete()
            staging.deleteRecursively()
        }
    }

    // ── 内部实现 ──

    private class Payload(val files: MutableList<Pair<String, File>> = mutableListOf()) {
        var bytes: Long = 0
    }

    private fun collect(context: Context): Payload {
        val payload = Payload()
        fun add(entryName: String, file: File) {
            if (!file.isFile) return
            payload.files += entryName to file
            payload.bytes += file.length()
        }

        val prefsDir = File(context.dataDir, PREFS_DIR)
        prefsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { add("$PREFS_DIR/${it.name}", it) }

        DATA_DIRS.forEach { dirName ->
            val dir = File(context.filesDir, dirName)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.path }
                .forEach { file ->
                    val rel = file.relativeTo(dir).path.replace(File.separatorChar, '/')
                    add("files/$dirName/$rel", file)
                }
        }
        return payload
    }

    private fun readManifest(staging: File): JSONObject? {
        val file = File(staging, BackupFormat.MANIFEST_ENTRY)
        if (!file.isFile) throw BackupException("不是 YAYai 备份文件（缺少 manifest）")
        val manifest = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException("备份清单损坏（manifest 无法解析）", e)
        }
        if (manifest.optString("format") != BACKUP_FORMAT) {
            throw BackupException("不是 YAYai 备份文件（格式标识不匹配）")
        }
        return manifest
    }

    /** 逐键写回配置（先清空该 prefs 文件，使恢复结果与备份一致）；返回写入键数。 */
    private fun applyPrefs(context: Context, name: String, values: Map<String, PrefValue>): Int {
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = prefs.edit().clear()
        var applied = 0
        values.forEach { (key, value) ->
            when (value) {
                is PrefValue.Str -> {
                    editor.putString(key, value.value); applied++
                }
                is PrefValue.Flag -> {
                    editor.putBoolean(key, value.value); applied++
                }
                is PrefValue.Num -> {
                    if (value.integer) editor.putInt(key, value.value.toInt())
                    else editor.putLong(key, value.value)
                    applied++
                }
                is PrefValue.Real -> {
                    editor.putFloat(key, value.value); applied++
                }
                is PrefValue.StrSet -> {
                    editor.putStringSet(key, value.value); applied++
                }
                is PrefValue.Unsupported -> Unit
            }
        }
        if (!editor.commit()) throw BackupException("写入配置失败：$name")
        return applied
    }

    /** 合并覆盖复制；返回写入文件数。 */
    private fun copyTree(src: File, dst: File): Int {
        var count = 0
        src.walkTopDown().filter { it.isFile }.forEach { file ->
            val rel = file.relativeTo(src).path
            val target = File(dst, rel)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            count++
        }
        return count
    }

    private fun requirePassword(password: String) {
        if (password.length < BackupFormat.MIN_PASSWORD_LENGTH) {
            throw BackupException("密码至少 ${BackupFormat.MIN_PASSWORD_LENGTH} 位")
        }
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    private fun fail(e: Exception): String = JSONObject().apply {
        put("ok", false)
        put("message", e.message?.takeIf { it.isNotBlank() } ?: (e.javaClass.simpleName + "（备份操作失败）"))
    }.toString()
}
