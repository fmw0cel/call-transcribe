package com.localtranscribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records 16 kHz mono PCM16 straight into a WAV file, which is whisper's native
 * input format. No re-encoding, no MediaRecorder, no temporary files.
 */
class WavRecorder(private val target: File) {

    private val _amplitude = MutableStateFlow(0f)

    /** Smoothed RMS level in 0..1, for the UI meter. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var failure: String? = null

    var durationMs: Long = 0
        private set

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): String? {
        if (running) return null

        val minBuffer = AudioRecord.getMinBufferSize(
            Wav.TARGET_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return "이 기기에서 녹음 버퍼를 만들 수 없습니다."

        val bufferSize = minBuffer * 4
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                Wav.TARGET_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        } catch (t: Throwable) {
            return t.message ?: "AudioRecord 생성 실패"
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return "마이크를 열 수 없습니다. 권한과 다른 앱의 사용 여부를 확인하세요."
        }

        target.parentFile?.mkdirs()
        val out = FileOutputStream(target)
        Wav.writeHeader(out, 0)

        running = true
        failure = null
        record = recorder

        thread = Thread({
            val chunk = ShortArray(bufferSize / 2)
            val bytes = ByteArray(chunk.size * 2)
            val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val shortView = byteBuffer.asShortBuffer()
            var totalBytes = 0L

            try {
                recorder.startRecording()
                while (running) {
                    val read = recorder.read(chunk, 0, chunk.size)
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                            read == AudioRecord.ERROR_BAD_VALUE ||
                            read == AudioRecord.ERROR_DEAD_OBJECT
                        ) {
                            failure = "오디오 장치를 읽을 수 없습니다."
                            break
                        }
                        continue
                    }

                    shortView.clear()
                    shortView.put(chunk, 0, read)
                    out.write(bytes, 0, read * 2)
                    totalBytes += read * 2L

                    var sum = 0.0
                    for (i in 0 until read) {
                        val v = chunk[i] / 32768f
                        sum += (v * v).toDouble()
                    }
                    val rms = sqrt(sum / read).toFloat()
                    val level = (rms * 6f).coerceIn(0f, 1f)
                    _amplitude.value = _amplitude.value * 0.7f + level * 0.3f
                }
            } catch (t: Throwable) {
                failure = t.message ?: "녹음 중 오류"
            } finally {
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Throwable) {
                }
                recorder.release()
                out.flush()
                out.close()
                Wav.patchHeader(target, totalBytes)
                durationMs = totalBytes / 32 // 32000 bytes per second
                running = false
                record = null
                _amplitude.value = 0f
            }
        }, "wav-recorder").apply { start() }

        if (failure != null) {
            running = false
            return failure
        }
        return null
    }

    /** Stops recording and finalises the file. Returns the WAV file. */
    fun stop(): File? {
        if (record == null && thread == null) return null
        running = false
        thread?.join(3_000)
        thread = null
        return if (failure == null) target else null
    }

    /** Stops and deletes the partial recording. */
    fun cancel() {
        running = false
        thread?.join(2_000)
        thread = null
        target.delete()
        durationMs = 0
    }

    fun error(): String? = failure
}
