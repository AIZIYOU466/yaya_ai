#include <jni.h>
#include <string>
#include <vector>
#include <atomic>
#include <android/log.h>

#define TAG "yaya_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef LLAMA_STUB

// STUB 构建：默认路径。Kotlin 侧 ModelBridge.isStub() 会读到 true，
// Rust Core 的路由（core/src/agent/router.rs）据此避开端侧，绝不伪装成真实推理结果。

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeIsStub(JNIEnv*, jobject) {
    LOGW("STUB: nativeIsStub=true（llama.cpp 未编译，推理不可用）");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeLoadModel(
    JNIEnv* env, jobject thiz, jstring modelPath) {
    LOGW("STUB: nativeLoadModel 拒绝执行（llama.cpp 未编译）");
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeGenerate(
    JNIEnv* env, jobject thiz, jstring modelPath, jstring prompt,
    jobject callback) {
    LOGW("STUB: nativeGenerate（llama.cpp 未编译）");
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jstring msg = env->NewStringUTF("[STUB] 当前为桩实现，推理结果不可用");
    env->CallVoidMethod(callback, onToken, msg);
    env->DeleteLocalRef(msg);
    env->DeleteLocalRef(cbClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yaya_ai_ModelBridge_nativeCancel(JNIEnv*, jobject) {
    LOGI("STUB: nativeCancel");
}

#else

// 全量构建（-PenableLlamaCpp=true，需 android/app/src/main/cpp/llama_cpp/ 源码）
// API 版本目标：llama.cpp b4100 前后（llama_sampler + llama_new_context_with_model
// 与 model 版 llama_tokenize/llama_token_to_piece 并存的窗口）。UNVERIFIED：本容器
// 无 NDK 与 llama.cpp 源码，首次启用以实际编译为准，通过后在 AGENTS.md 登记版本。
#include "llama.h"

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_sampler = nullptr;
static std::atomic<bool> g_cancelled{false};
static bool g_backend_inited = false;

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeIsStub(JNIEnv*, jobject) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeLoadModel(
    JNIEnv* env, jobject thiz, jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    if (!g_backend_inited) {
        llama_backend_init(false);
        g_backend_inited = true;
    }
    if (g_model != nullptr) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_load_model_from_file(path, model_params);

    if (g_model == nullptr) {
        LOGE("Failed to load model: %s", path);
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
    LOGI("Model loaded: %s", path);
    env->ReleaseStringUTFChars(modelPath, path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeGenerate(
    JNIEnv* env, jobject thiz, jstring modelPath, jstring prompt,
    jobject callback) {

    if (g_model == nullptr) {
        LOGE("nativeGenerate: model not loaded");
        return JNI_FALSE;
    }

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;
    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (g_ctx == nullptr) {
        LOGE("Failed to create context");
        env->ReleaseStringUTFChars(prompt, promptStr);
        return JNI_FALSE;
    }
    g_sampler = llama_sampler_init_greedy();

    const int promptLen = static_cast<int>(strlen(promptStr));
    const int n_prompt = llama_tokenize(g_model, promptStr, promptLen, nullptr, 0, true, false);
    if (n_prompt <= 0) {
        LOGE("tokenize failed (%d)", n_prompt);
        llama_sampler_free(g_sampler);
        llama_free(g_ctx);
        g_sampler = nullptr;
        g_ctx = nullptr;
        env->ReleaseStringUTFChars(prompt, promptStr);
        return JNI_FALSE;
    }
    std::vector<llama_token> tokens(n_prompt);
    llama_tokenize(g_model, promptStr, promptLen, tokens.data(), n_prompt, true, false);
    env->ReleaseStringUTFChars(prompt, promptStr);

    const int kMaxNew = 512;
    llama_batch batch = llama_batch_init(n_prompt + kMaxNew, 0, 1);
    for (int i = 0; i < n_prompt; i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n_prompt - 1);
    }
    batch.n_tokens = n_prompt;

    g_cancelled.store(false);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    int n_generated = 0;
    int idx = n_prompt - 1;
    for (int i = 0; i < kMaxNew && !g_cancelled; i++) {
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed at step %d", i);
            break;
        }

        llama_token newToken = llama_sampler_sample(g_sampler, g_ctx, idx);
        llama_sampler_accept(g_sampler, newToken);
        if (llama_token_is_eog(g_model, newToken)) break;

        char buf[256];
        int n = llama_token_to_piece(g_model, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) {
            jstring jToken = env->NewStringUTF(std::string(buf, n).c_str());
            env->CallVoidMethod(callback, onToken, jToken);
            env->DeleteLocalRef(jToken);
            // Kotlin 回调抛异常时立刻清理并中止生成，避免挂起异常破坏后续 JNI 调用。
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                break;
            }
        }

        idx = n_prompt + i;
        batch.n_tokens = 1;
        batch.token[0] = newToken;
        batch.pos[0] = n_prompt + i;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = true;
        n_generated++;
    }

    llama_batch_free(batch);
    llama_sampler_free(g_sampler);
    llama_free(g_ctx);
    g_sampler = nullptr;
    g_ctx = nullptr;
    LOGI("Generated %d tokens", n_generated);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_yaya_ai_ModelBridge_nativeCancel(JNIEnv*, jobject) {
    g_cancelled.store(true);
}

#endif  // LLAMA_STUB
