package com.yaya.ai

object ModelBridge {
    private var modelLoaded = false
    private var currentModelPath: String? = null

    init {
        try {
            System.loadLibrary("yaya_llama")
        } catch (e: UnsatisfiedLinkError) {
            // JNI 库未编译，推理功能不可用
        }
    }

    private external fun nativeLoadModel(modelPath: String): Boolean
    private external fun nativeGenerate(modelPath: String, prompt: String, callback: ModelCallback): Boolean
    private external fun nativeCancel()

    interface ModelCallback {
        fun onToken(token: String)
    }

    fun generate(modelPath: String?, prompt: String, onToken: (String) -> Unit) {
        val path = modelPath ?: currentModelPath ?: return

        if (!modelLoaded || path != currentModelPath) {
            if (!nativeLoadModel(path)) return
            currentModelPath = path
            modelLoaded = true
        }

        val callback = object : ModelCallback {
            override fun onToken(token: String) = onToken(token)
        }
        nativeGenerate(path, prompt, callback)
    }

    fun cancel() {
        nativeCancel()
    }
}
