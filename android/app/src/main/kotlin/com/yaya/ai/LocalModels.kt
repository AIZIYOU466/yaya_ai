package com.yaya.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yaya.ai.ui.ProviderStore
import java.io.File

/** 端侧模型导入/删除的显式失败（R15：不静默）。 */
class LocalModelException(message: String) : Exception(message)

/**
 * 端侧模型文件管理（ROADMAP 任务 31）。
 *
 * **根因**：Android 上 `.gguf` 只可能来自 SAF（`content://` URI）或应用私有目录，手填绝对路径
 * 在真机上拿不到有效值 —— 于是 `config.modelPath` 恒为空，R2 第 2 条端侧分支等于永远不生效。
 * 因此把 SAF 选中的文件**复制**进 `filesDir/models/`，得到稳定真实路径，选择结果写回既有键
 * [ProviderStore.KEY_MODEL_PATH]；`ChatScreen` 下发 `config.modelPath` → JNI `LocalBackend`
 * 链路无需改动。
 *
 * 规则：只接受 `.gguf`；复制先写 `*.part` 再改名（中断不留半成品）；导入前检查剩余空间；
 * 删除只允许模型目录内的文件。失败一律抛 [LocalModelException]。
 */
object LocalModels {
    const val DIR = "models"

    /** 导入时给系统预留的空间，避免把设备写满。 */
    private const val FREE_SPACE_MARGIN = 64L * 1024 * 1024
    private const val BUFFER = 1 shl 20

    /** 进度回调节流（同时减少 Compose 状态写入次数）。 */
    private const val PROGRESS_STEP = 8L * 1024 * 1024

    data class LocalModel(val name: String, val path: String, val size: Long)

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 模型目录内的 `.gguf` 文件（按名称排序）。 */
    fun list(context: Context): List<LocalModel> {
        val files = dir(context).listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && LocalModelFiles.hasModelExtension(it.name) }
            .map { LocalModel(it.name, it.absolutePath, it.length()) }
            .sortedBy { it.name.lowercase() }
    }

    /** 当前选中的模型路径（空串表示未选择）。 */
    fun selected(context: Context): String =
        ProviderStore.prefs(context).getString(ProviderStore.KEY_MODEL_PATH, "").orEmpty().trim()

    fun select(context: Context, path: String) {
        ProviderStore.prefs(context).edit()
            .putString(ProviderStore.KEY_MODEL_PATH, path.trim())
            .apply()
    }

    fun clearSelection(context: Context) {
        ProviderStore.prefs(context).edit()
            .remove(ProviderStore.KEY_MODEL_PATH)
            .apply()
    }

    /**
     * 校验选中项：文件不存在（卸载重装、被清理、旧手填路径）时清除选择并返回空串。
     * 返回值为当前应展示/下发的路径。
     */
    fun reconcileSelection(context: Context): String {
        val path = selected(context)
        if (path.isEmpty()) return ""
        val file = File(path)
        if (file.isFile) return path
        clearSelection(context)
        return ""
    }

    /**
     * 从 SAF URI 导入模型：复制到 `filesDir/models/<唯一名>`，成功后**自动选中**。
     * [onProgress] 在 IO 线程回调（已按 8MB 节流），参数为 `(已复制字节, 源声明大小或 -1)`。
     */
    fun import(
        context: Context,
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
    ): LocalModel {
        val dir = dir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw LocalModelException("无法创建模型目录：${dir.absolutePath}")
        }

        var displayName = ""
        var declaredSize = -1L
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) displayName = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) declaredSize = cursor.getLong(sizeIdx)
                }
            }

        val safeName = LocalModelFiles.sanitizeName(displayName)
            ?: throw LocalModelException(
                "只支持 .gguf 模型文件（所选文件：${displayName.ifBlank { "未知名称" }}）"
            )
        if (declaredSize > 0) {
            val usable = dir.usableSpace
            if (declaredSize + FREE_SPACE_MARGIN > usable) {
                throw LocalModelException(
                    "存储空间不足：模型 ${LocalModelFiles.formatSize(declaredSize)}，" +
                        "可用 ${LocalModelFiles.formatSize(usable)}"
                )
            }
        }

        val targetName = LocalModelFiles.uniqueName(safeName, list(context).map { it.name })
        val target = File(dir, targetName)
        val part = File(dir, "$targetName.part")
        var copied = 0L
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw LocalModelException("无法读取所选文件")
            input.use { ins ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER)
                    var lastReport = 0L
                    while (true) {
                        val n = ins.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (copied - lastReport >= PROGRESS_STEP) {
                            lastReport = copied
                            onProgress(copied, declaredSize)
                        }
                    }
                    out.flush()
                }
            }
            if (copied <= 0) throw LocalModelException("所选文件为空")
            if (declaredSize > 0 && copied != declaredSize) {
                throw LocalModelException("复制不完整：$copied/$declaredSize 字节")
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            select(context, target.absolutePath)
            onProgress(copied, copied)
            return LocalModel(target.name, target.absolutePath, target.length())
        } catch (e: Exception) {
            part.delete()
            target.delete()
            throw if (e is LocalModelException) e
            else LocalModelException("导入失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 删除模型文件；若删除的是当前选中项，一并清除选择。 */
    fun delete(context: Context, model: LocalModel) {
        val dir = dir(context).canonicalFile
        val file = File(model.path).canonicalFile
        if (file.parentFile != dir) throw LocalModelException("拒绝删除模型目录之外的文件")
        if (file.exists() && !file.delete()) {
            throw LocalModelException("删除失败：${model.name}")
        }
        if (selected(context) == model.path) clearSelection(context)
    }
}
