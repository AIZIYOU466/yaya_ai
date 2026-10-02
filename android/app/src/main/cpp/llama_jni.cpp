#include <jni.h>
#include <string>
#include <android/log.h>

#define TAG "yaya_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef LLAMA_STUB

// Stub 实现：无 llama.cpp 时返回假结果
JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeLoadModel(
    JNIEnv* env, jobject thiz, jstring modelPath) {
    LOGI("STUB: nativeLoadModel (llama.cpp not linked)");
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeGenerate(
    JNIEnv* env, jobject thiz, jstring modelPath, jstring prompt,
    jobject callback) {
    LOGI("STUB: nativeGenerate (llama.cpp not linked)");

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jstring msg = env->NewStringUTF("[llama.cpp 未编译，推理不可用]");
    env->CallVoidMethod(callback, onToken, msg);
    env->DeleteLocalRef(msg);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yaya_ai_ModelBridge_nativeCancel(JNIEnv*, jobject) {
    LOGI("STUB: nativeCancel");
}

#else

// 完整实现：链接 llama.cpp
#include "llama.h"

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static bool g_cancelled = false;

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeLoadModel(
    JNIEnv* env, jobject thiz, jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    llama_backend_init(false);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;
    g_model = llama_load_model_from_file(path, model_params);

    env->ReleaseStringUTFChars(modelPath, path);
    if (g_model == nullptr) {
        LOGE("Failed to load model: %s", path);
        return JNI_FALSE;
    }
    LOGI("Model loaded: %s", path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_yaya_ai_ModelBridge_nativeGenerate(
    JNIEnv* env, jobject thiz, jstring modelPath, jstring prompt,
    jobject callback) {

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_threads = 4;
    g_ctx = llama_new_context_with_model(g_model, ctx_params);

    std::vector<llama_token> tokens = llama_tokenize(g_ctx, std::string(promptStr), true);

    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
    }
    batch.n_tokens = tokens.size();

    g_cancelled = false;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");

    int n_generated = 0;
    for (int i = 0; i < 512 && !g_cancelled; i++) {
        llama_decode(g_ctx, batch);

        llama_token newToken = llama_sampler_sample(g_ctx, batch, -1);
        if (llama_token_is_eog(g_model, newToken)) break;

        std::string tokenStr = llama_token_to_piece(g_ctx, newToken);
        jstring jToken = env->NewStringUTF(tokenStr.c_str());
        env->CallVoidMethod(callback, onToken, jToken);
        env->DeleteLocalRef(jToken);

        batch.token[0] = newToken;
        batch.pos[0] = tokens.size() + i;
        batch.n_tokens = 1;
        n_generated++;
    }

    llama_batch_free(batch);
    llama_free(g_ctx);
    g_ctx = nullptr;
    env->ReleaseStringUTFChars(prompt, promptStr);
    LOGI("Generated %d tokens", n_generated);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_yaya_ai_ModelBridge_nativeCancel(JNIEnv*, jobject) {
    g_cancelled = true;
}

#endif  // LLAMA_STUB
