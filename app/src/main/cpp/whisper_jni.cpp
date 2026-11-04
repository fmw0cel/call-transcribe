// JNI bridge between Kotlin (com.localtranscribe.whisper.WhisperLib) and whisper.cpp.
//
// The Kotlin side owns the chunking / WAV handling; this file only exposes the
// bare minimum: create a context, run whisper_full() on a float buffer, stream
// segments back through a listener, and allow cancellation.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct WhisperHandle {
    whisper_context* ctx = nullptr;
    std::atomic<bool> cancel{false};
};

struct CallbackCtx {
    JNIEnv* env = nullptr;
    jobject listener = nullptr;
    jmethodID onProgress = nullptr;
    jmethodID onSegment = nullptr;
    int lastProgress = -1;
};

std::string jstringToStd(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void emitProgress(CallbackCtx* cb, int percent) {
    if (cb == nullptr || cb->onProgress == nullptr || cb->env == nullptr) return;
    if (percent == cb->lastProgress) return;
    cb->lastProgress = percent;
    cb->env->CallVoidMethod(cb->listener, cb->onProgress, static_cast<jint>(percent));
    if (cb->env->ExceptionCheck()) cb->env->ExceptionClear();
}

void emitSegment(CallbackCtx* cb, jlong startMs, jlong endMs, const char* text) {
    if (cb == nullptr || cb->onSegment == nullptr || cb->env == nullptr) return;
    jstring jtext = cb->env->NewStringUTF(text != nullptr ? text : "");
    cb->env->CallVoidMethod(cb->listener, cb->onSegment, startMs, endMs, jtext);
    cb->env->DeleteLocalRef(jtext);
    if (cb->env->ExceptionCheck()) cb->env->ExceptionClear();
}

void progressCallback(whisper_context*, whisper_state*, int progress, void* userData) {
    emitProgress(static_cast<CallbackCtx*>(userData), progress);
}

void newSegmentCallback(whisper_context* ctx, whisper_state*, int nNew, void* userData) {
    auto* cb = static_cast<CallbackCtx*>(userData);
    const int total = whisper_full_n_segments(ctx);
    const int from = total - nNew < 0 ? 0 : total - nNew;
    for (int i = from; i < total; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        const jlong t0 = static_cast<jlong>(whisper_full_get_segment_t0(ctx, i)) * 10LL;
        const jlong t1 = static_cast<jlong>(whisper_full_get_segment_t1(ctx, i)) * 10LL;
        emitSegment(cb, t0, t1, text);
    }
}

bool abortCallback(void* userData) {
    auto* handle = static_cast<WhisperHandle*>(userData);
    return handle == nullptr || handle->cancel.load();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localtranscribe_whisper_WhisperNative_nativeInit(JNIEnv* env, jobject, jstring modelPath) {
    const std::string path = jstringToStd(env, modelPath);
    if (path.empty()) return 0;

    auto* handle = new WhisperHandle();
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    params.flash_attn = true;

    handle->ctx = whisper_init_from_file_with_params(path.c_str(), params);
    if (handle->ctx == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        delete handle;
        return 0;
    }

    LOGI("model loaded: %s", path.c_str());
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localtranscribe_whisper_WhisperNative_nativeFree(JNIEnv*, jobject, jlong ptr) {
    auto* handle = reinterpret_cast<WhisperHandle*>(ptr);
    if (handle == nullptr) return;
    if (handle->ctx != nullptr) whisper_free(handle->ctx);
    delete handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localtranscribe_whisper_WhisperNative_nativeCancel(JNIEnv*, jobject, jlong ptr) {
    auto* handle = reinterpret_cast<WhisperHandle*>(ptr);
    if (handle != nullptr) handle->cancel.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localtranscribe_whisper_WhisperNative_nativeTranscribe(
        JNIEnv* env, jobject, jlong ptr, jfloatArray samples, jstring language, jboolean translate,
        jint threads, jint offsetMs, jobject listener) {
    auto* handle = reinterpret_cast<WhisperHandle*>(ptr);
    if (handle == nullptr || handle->ctx == nullptr || samples == nullptr) return JNI_FALSE;

    handle->cancel.store(false);

    const jsize nSamples = env->GetArrayLength(samples);
    if (nSamples <= 0) return JNI_FALSE;

    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return JNI_FALSE;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.translate = translate == JNI_TRUE;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.n_threads = threads > 0 ? threads : 4;

    const std::string lang = jstringToStd(env, language);
    // Empty language => whisper auto-detects and then continues transcribing.
    // (detect_language=true would make whisper_full return right after the
    // detection pass, producing no text at all.)
    params.language = lang.empty() ? nullptr : lang.c_str();
    params.detect_language = false;

    CallbackCtx callback;
    callback.env = env;
    callback.listener = listener;

    if (listener != nullptr) {
        jclass cls = env->GetObjectClass(listener);
        callback.onProgress = env->GetMethodID(cls, "onProgress", "(I)V");
        callback.onSegment = env->GetMethodID(cls, "onSegment", "(JJLjava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (env->ExceptionCheck()) env->ExceptionClear();

        params.progress_callback = progressCallback;
        params.progress_callback_user_data = &callback;
        params.new_segment_callback = newSegmentCallback;
        params.new_segment_callback_user_data = &callback;
    }

    params.abort_callback = abortCallback;
    params.abort_callback_user_data = handle;

    const int rc = whisper_full(handle->ctx, params, data, nSamples);

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    if (rc != 0) {
        LOGE("whisper_full failed with %d", rc);
        return JNI_FALSE;
    }
    return handle->cancel.load() ? JNI_FALSE : JNI_TRUE;
}
