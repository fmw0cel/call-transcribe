package com.localtranscribe.whisper

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A loaded whisper model. One instance holds one native context; create it,
 * transcribe one or more chunks, then [close].
 */
class WhisperLib private constructor(private val handle: Long) : AutoCloseable {

    interface Listener {
        /** 0..100 for the current chunk. */
        fun onProgress(percent: Int)

        /** Called as soon as a segment is decoded, so the UI can stream text. */
        fun onSegment(startMs: Long, endMs: Long, text: String)
    }

    private val closed = AtomicBoolean(false)

    /**
     * @param language ISO code ("ko", "en", ...) or null/"" for auto detection.
     */
    fun transcribe(
        samples: FloatArray,
        language: String?,
        translate: Boolean,
        threads: Int,
        listener: Listener,
    ): Boolean {
        check(!closed.get()) { "WhisperLib is closed" }
        return WhisperNative.transcribe(
            handle = handle,
            samples = samples,
            language = language.orEmpty(),
            translate = translate,
            threads = threads,
            offsetMs = 0,
            listener = listener,
        )
    }

    fun cancel() {
        if (!closed.get()) WhisperNative.cancel(handle)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            WhisperNative.freeContext(handle)
        }
    }

    companion object {
        /** Loads a ggml model. Returns null when the file is not a valid model. */
        fun load(model: File): WhisperLib? {
            if (!model.isFile || model.length() < 1_000_000L) return null
            val handle = WhisperNative.initContext(model.absolutePath)
            return if (handle == 0L) null else WhisperLib(handle)
        }
    }
}
