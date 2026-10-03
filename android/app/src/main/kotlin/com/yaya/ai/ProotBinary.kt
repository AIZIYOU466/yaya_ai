package com.yaya.ai

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * 内置 proot 二进制定位与依赖准备。
 *
 * - proot / loader 以 native lib 形式随 jniLibs 打包（libproot.so /
 *   libproot_loader.so），安装后落在 nativeLibraryDir（apk_data_file 域），
 *   这是 targetSdk 29+ 上唯一允许 app execve 的位置（filesDir 的
 *   app_data_file 被内核 W^X 禁止）。
 * - 两个依赖库（libtalloc.so.2 / libandroid-shmem.so）从 assets 解压到
 *   filesDir/yaya-libs：它们只需被 dlopen（不需要 exec 权限），app_data_file
 *   的 dlopen 在 Android 上允许。
 */
object ProotBinary {
    private const val LIBS_DIR = "yaya-libs"

    /** 定位 nativeLibraryDir 中打包的 proot 可执行文件；未打包则抛异常。 */
    fun find(context: Context): File {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val proot = File(dir, "libproot.so")
        if (!proot.exists()) {
            throw IllegalStateException("proot 未打包（当前 ABI 不支持，需 arm64 设备）")
        }
        return proot
    }

    /** 确保依赖库就绪并返回其目录（供 LD_LIBRARY_PATH 使用）。 */
    fun ensureLibs(context: Context): File {
        val dir = File(context.filesDir, LIBS_DIR)
        if (!File(dir, "libtalloc.so.2").exists()) {
            dir.mkdirs()
            copyAsset(context, "proot/libtalloc.so.2", File(dir, "libtalloc.so.2"))
            copyAsset(context, "proot/libandroid-shmem.so", File(dir, "libandroid-shmem.so"))
        }
        return dir
    }

    private fun copyAsset(context: Context, asset: String, target: File) {
        context.assets.open(asset).use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out) }
        }
    }
}