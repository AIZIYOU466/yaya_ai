package com.yaya.ai

object ModelBridge {
    const val STUB_NOTICE =
        "[STUB] 当前为桩实现，推理结果不可用（llama.cpp 未编译，使用 -PenableLlamaCpp=true 构建）"

    @Volatile private var modelLoaded = false
    @Volatile private var currentModelPath: String? = null

    private val libLoaded = try {
        System.loadLibrary("yaya_llama")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    private external fun nativeLoadModel(modelPath: String): Boolean
    private external fun nativeGenerate(modelPath: String, prompt: String, callback: ModelCallback): Boolean
    private external fun nativeCancel()
    private external fun nativeIsStub(): Boolean

    interface ModelCallback {
        fun onToken(token: String)
    }

    fun isAvailable(): Boolean = libLoaded

    fun isStub(): Boolean = !libLoaded || nativeIsStub()

    fun generate(modelPath: String?, prompt: String, onToken: (String) -> Unit): String? {
        if (!libLoaded) {
            return "JNI 库 libyaya_llama.so 未加载（native 库缺失）"
        }
        if (isStub()) {
            onToken(STUB_NOTICE)
            return null
        }
        val path = modelPath ?: currentModelPath
            ?: return "未指定模型路径（modelPath 为空）"

        if (!modelLoaded || path != currentModelPath) {
            if (!nativeLoadModel(path)) {
                return "模型加载失败: $path"
            }
            currentModelPath = path
            modelLoaded = true
        }

        val callback = object : ModelCallback {
            override fun onToken(token: String) = onToken(token)
        }
        return if (nativeGenerate(path, prompt, callback)) null else "推理失败（nativeGenerate 返回 false）"
    }

    fun cancel() {
        if (libLoaded && !isStub()) {
            nativeCancel()
        }
    }
}
