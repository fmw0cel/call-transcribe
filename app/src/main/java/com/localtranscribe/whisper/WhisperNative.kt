package com.localtranscribe.whisper

/**
 * Direct JNI entry points. Kept in one object so the native symbols stay
 * predictable (`Java_com_localtranscribe_whisper_WhisperNative_*`).
 */
internal object WhisperNative {

    init {
        System.loadLibrary("whisper_jni")
    }

    external fun initContext(modelPath: String): Long

    external fun freeContext(handle: Long)

    external fun cancel(handle: Long)

    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        offsetMs: Int,
        listener: Any,
    ): Boolean
}
