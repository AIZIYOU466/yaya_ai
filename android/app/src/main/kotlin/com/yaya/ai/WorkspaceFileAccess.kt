package com.yaya.ai

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 工作区文件访问（AGENTS.md R19 / ROADMAP 任务 21）：`filesDir/workspace/` 内的文件读写。
 *
 * 所有路径先 `normalize` 再做越界校验（必须以工作区根开头），防目录穿越逃出工作区；
 * 相对路径以工作区根为基准，禁止绝对路径（core 侧工具只传相对路径）。
 */
class WorkspaceFileAccess(context: Context) {
    private val root: File = File(context.filesDir, "workspace").also { it.mkdirs() }

    /** 相对工作区根的路径 → 绝对 File；越界/非法返回 null。 */
    private fun resolve(rel: String): File? {
        if (rel.contains('\u0000')) return null
        val f = File(root, rel.trimStart('/')).normalize()
        val rootAbs = root.absolutePath
        return if (f.absolutePath == rootAbs || f.absolutePath.startsWith(rootAbs + File.separator)) {
            f
        } else {
            null
        }
    }

    /** 列出目录：`{"ok":true,"content":"..."}`（条目名，目录带 `/` 后缀）。 */
    fun list(path: String): String {
        val dir = resolve(path) ?: return err("路径越界或非法: $path")
        if (!dir.isDirectory) return err("目录不存在: $path")
        val names = dir.listFiles()?.sortedBy { it.name }?.map { f ->
            if (f.isDirectory) "${f.name}/" else f.name
        } ?: return err("读取目录失败: $path")
        return ok().put("content", names.joinToString("\n")).toString()
    }

    /** 读取文件：`{"ok":true,"content":"..."}`。 */
    fun read(path: String): String {
        val f = resolve(path) ?: return err("路径越界或非法: $path")
        if (!f.isFile) return err("文件不存在: $path")
        if (f.length() > MAX_READ_BYTES) {
            return err("文件过大（>${MAX_READ_BYTES / 1024}KB），请分段处理")
        }
        return try {
            ok().put("content", f.readText()).toString()
        } catch (e: Exception) {
            err("读取失败: ${e.message}")
        }
    }

    /** 写入文件：入参 `{path,content,overwrite}`，返回 `{ok,created}`。 */
    fun write(json: String): String {
        val req = JSONObject(json)
        val path = req.optString("path")
        val content = req.optString("content")
        val overwrite = req.optBoolean("overwrite", true)
        val f = resolve(path) ?: return err("路径越界或非法: $path")
        if (f.exists() && !overwrite) return err("文件已存在: $path（overwrite=false）")
        return try {
            f.parentFile?.mkdirs()
            val created = !f.exists()
            if (!created) backup(f)
            f.writeText(content)
            ok().put("created", created).toString()
        } catch (e: Exception) {
            err("写入失败: ${e.message}")
        }
    }

    /** 目标是否存在：`{"ok":true,"exists":true}`。 */
    fun exists(path: String): String {
        val f = resolve(path) ?: return err("路径越界或非法: $path")
        return ok().put("exists", f.exists()).toString()
    }

    /** 覆盖已有文件前备份到 `backups/workspace/`（工作区外，模型不可见），保留最近 [`BACKUP_KEEP`] 份。 */
    private fun backup(f: File) {
        try {
            val dir = File(root.parentFile, "backups/workspace")
            dir.mkdirs()
            val bak = File(dir, "${System.currentTimeMillis()}_${f.name}.bak")
            f.copyTo(bak, overwrite = true)
            val keep = dir.listFiles { x -> x.isFile }?.toMutableList() ?: mutableListOf()
            while (keep.size > BACKUP_KEEP) {
                val oldest = keep.minByOrNull { it.lastModified() } ?: break
                oldest.delete()
                keep.remove(oldest)
            }
        } catch (_: Exception) {
            // 备份尽力而为，失败不阻断写入
        }
    }

    /** 删除文件（或空目录）。 */
    fun delete(path: String): String {
        val f = resolve(path) ?: return err("路径越界或非法: $path")
        if (!f.exists()) return err("文件不存在: $path")
        if (f.isDirectory && f.listFiles()?.isNotEmpty() == true) {
            return err("目录非空，无法删除: $path")
        }
        return try {
            f.delete()
            ok().toString()
        } catch (e: Exception) {
            err("删除失败: ${e.message}")
        }
    }

    /** 供 UI 展示工作区根路径。 */
    fun rootPath(): String = root.absolutePath

    private fun ok(): JSONObject = JSONObject().put("ok", true)

    private fun err(msg: String): String =
        JSONObject().put("ok", false).put("message", msg).toString()

    private companion object {
        /** 单次读入内存上限：10MB。 */
        const val MAX_READ_BYTES = 10 * 1024 * 1024L

        /** 工作区备份保留份数。 */
        const val BACKUP_KEEP = 5
    }
}
