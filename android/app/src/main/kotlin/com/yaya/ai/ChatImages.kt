package com.yaya.ai

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * 聊天图片附件存储（任务 26）：`filesDir/chat_images/`。
 *
 * 消息表只存受管绝对路径（不存 base64）：检查点快照随消息复制时不会重复携带图片数据；
 * 删除会话/清理消息时按路径删除文件。所有路径先做受管目录校验，防任意文件引用。
 */
object ChatImages {
    private const val DIR = "chat_images"
    private const val EXT = ".jpg"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    /** 预留一个新文件名（尚未落盘）。 */
    fun newPath(context: Context): String =
        File(dir(context), "${UUID.randomUUID()}$EXT").absolutePath

    /** 写入压缩后的 JPEG，返回受管路径。 */
    fun save(context: Context, bytes: ByteArray): String? = try {
        val file = File(newPath(context))
        file.writeBytes(bytes)
        file.absolutePath
    } catch (_: Exception) {
        null
    }

    /** 路径必须落在受管目录内（防越界引用）。 */
    fun isManaged(context: Context, path: String): Boolean = try {
        val root = dir(context).canonicalPath + File.separator
        File(path).canonicalPath.startsWith(root)
    } catch (_: Exception) {
        false
    }

    fun delete(context: Context, paths: Collection<String>) {
        paths.forEach { path ->
            if (isManaged(context, path)) runCatching { File(path).delete() }
        }
    }
}
