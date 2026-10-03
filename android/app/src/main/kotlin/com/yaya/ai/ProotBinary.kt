package com.yaya.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 内置 proot 二进制（termux 官方 proot 5.1.107.96 aarch64，随 APK assets 打包）。
 *
 * 运行时首次解压到 filesDir/yaya-bin（可执行位 + LD_LIBRARY_PATH 指回同目录，
 * 满足 proot 对 libtalloc.so.2 / libandroid-shmem.so 的依赖；loader 位于
 * bin/libexec/proot/ 供 proot 自动发现）。
 */
object ProotBinary {
    private const val ASSETS_PREFIX = "proot"
    private const val BIN_DIR = "yaya-bin"

    /** 确保 proot 就绪并返回其可执行文件；失败抛带原因的异常。 */
    fun ensure(context: Context): File {
        val dir = File(context.filesDir, BIN_DIR)
        val proot = File(dir, "proot")
        if (proot.exists() && proot.canExecute()) return proot

        dir.mkdirs()
        // 目录需可遍历（exec 依赖目录 x 位）
        dir.setExecutable(true, false)
        copyAsset(context, "$ASSETS_PREFIX/proot", proot)
        makeExecutable(proot)

        for (name in listOf("loader", "loader32")) {
            val target = File(dir, "libexec/proot/$name")
            target.parentFile?.mkdirs()
            copyAsset(context, "$ASSETS_PREFIX/libexec/proot/$name", target)
            makeExecutable(target)
        }
        for (name in listOf("libtalloc.so.2", "libandroid-shmem.so")) {
            copyAsset(context, "$ASSETS_PREFIX/$name", File(dir, name))
        }
        if (!proot.canExecute()) {
            throw IllegalStateException("proot 无法获得执行权限（SELinux 可能拒绝 data 目录 exec）")
        }
        return proot
    }

    /** 设置全用户执行位；setExecutable 失败时用 chmod 兜底。 */
    private fun makeExecutable(file: File) {
        file.setExecutable(true, false)
        file.setReadable(true, false)
        if (!file.canExecute()) {
            try {
                Runtime.getRuntime()
                    .exec(arrayOf("chmod", "755", file.absolutePath))
                    .waitFor()
            } catch (_: Exception) {
            }
        }
    }

    private fun copyAsset(context: Context, asset: String, target: File) {
        context.assets.open(asset).use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }
    }
}