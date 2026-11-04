package com.localtranscribe.whisper

import com.localtranscribe.audio.Wav
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Runs whisper.cpp over a 16 kHz mono WAV in fixed-size chunks.
 *
 * Chunking keeps peak memory low on phones (each chunk becomes a FloatArray)
 * and gives the UI a progress value that actually moves on long recordings.
 */
class Transcriber(
    private val wavFile: File,
    private val modelFile: File,
    private val threads: Int = defaultThreads(),
) {

    class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    @Volatile
    private var lib: WhisperLib? = null

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        lib?.cancel()
    }

    fun transcribe(
        language: String?,
        translate: Boolean,
        onProgress: (Float) -> Unit,
        onSegment: (TranscriptSegment) -> Unit,
    ): List<TranscriptSegment> {
        val info = Wav.readInfo(wavFile)
            ?: throw TranscriptionException("WAV 파일을 읽을 수 없습니다.")
        if (info.sampleRate != Wav.TARGET_SAMPLE_RATE || info.bitsPerSample != 16 || info.channels != 1) {
            throw TranscriptionException(
                "지원하지 않는 오디오 형식입니다 (${info.sampleRate}Hz, " +
                    "${info.channels}ch, ${info.bitsPerSample}bit).",
            )
        }

        val chunkFrames = Wav.TARGET_SAMPLE_RATE * CHUNK_SECONDS
        val totalFrames = info.frameCount
        if (totalFrames <= 0) throw TranscriptionException("오디오가 비어 있습니다.")
        val chunkCount = ((totalFrames + chunkFrames - 1) / chunkFrames).toInt()

        onProgress(0f)

        val loaded = WhisperLib.load(modelFile)
            ?: throw TranscriptionException("모델을 불러오지 못했습니다. 모델 파일이 손상되었을 수 있습니다.")
        lib = loaded

        val segments = ArrayList<TranscriptSegment>(chunkCount * 8)

        try {
            RandomAccessFile(wavFile, "r").use { raf ->
                raf.seek(info.dataOffset)
                val bytes = ByteArray(chunkFrames * 2)
                var frameOffset = 0L

                for (chunk in 0 until chunkCount) {
                    if (cancelled) break

                    val framesWanted = min(chunkFrames.toLong(), totalFrames - frameOffset).toInt()
                    if (framesWanted <= 0) break

                    val bytesWanted = framesWanted * 2
                    var read = 0
                    while (read < bytesWanted) {
                        val n = raf.read(bytes, read, bytesWanted - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read <= 0) break
                    val framesRead = read / 2

                    val samples = FloatArray(framesRead)
                    for (i in 0 until framesRead) {
                        val lo = bytes[i * 2].toInt() and 0xFF
                        val hi = bytes[i * 2 + 1].toInt()
                        val value = ((hi shl 8) or lo).toShort()
                        samples[i] = value / 32768f
                    }

                    val chunkStartMs = frameOffset * 1000L / Wav.TARGET_SAMPLE_RATE
                    val chunkIndex = chunk

                    val listener = object : WhisperLib.Listener {
                        override fun onProgress(percent: Int) {
                            val overall = (chunkIndex + percent / 100f) / chunkCount
                            onProgress(overall.coerceIn(0f, 1f))
                        }

                        override fun onSegment(startMs: Long, endMs: Long, text: String) {
                            val cleaned = text.trim()
                            if (cleaned.isEmpty()) return
                            val segment = TranscriptSegment(
                                startMs = chunkStartMs + startMs,
                                endMs = chunkStartMs + endMs,
                                text = cleaned,
                            )
                            synchronized(segments) { segments.add(segment) }
                            onSegment(segment)
                        }
                    }

                    loaded.transcribe(
                        samples = samples,
                        language = language,
                        translate = translate,
                        threads = threads,
                        listener = listener,
                    )

                    frameOffset += framesRead
                    onProgress(((chunk + 1).toFloat() / chunkCount).coerceIn(0f, 1f))
                }
            }
        } finally {
            loaded.close()
            lib = null
        }

        if (cancelled) throw kotlinx.coroutines.CancellationException("transcription cancelled")
        return synchronized(segments) { segments.toList() }
    }

    companion object {
        private const val TAG = "Transcriber"

        /** 2 minutes per chunk: big enough for whisper's context, small enough for phones. */
        private const val CHUNK_SECONDS = 120

        fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
    }
}
