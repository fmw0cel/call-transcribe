package com.localtranscribe.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException

/**
 * Turns any user-picked audio file into a 16 kHz mono WAV, which is what the
 * transcriber consumes. WAV files are parsed directly; everything else goes
 * through MediaExtractor + MediaCodec (so m4a, mp3, ogg, opus, flac, webm, ...).
 */
object AudioDecoder {

    private const val TAG = "AudioDecoder"

    data class Result(val file: File, val durationMs: Long)

    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun decodeTo16kWav(
        context: Context,
        uri: Uri,
        target: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Result {
        target.parentFile?.mkdirs()
        target.delete()

        val durationMs = if (isWav(context, uri)) {
            decodeWav(context, uri, target, onProgress, isCancelled)
        } else {
            decodeWithMediaCodec(context, uri, target, onProgress, isCancelled)
        }

        if (target.length() <= 44) {
            target.delete()
            throw DecodeException("오디오 데이터를 찾을 수 없습니다.")
        }
        return Result(target, durationMs)
    }

    // ------------------------------------------------------- WAV sniffing ---

    private fun isWav(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = readUpTo(input, 12)
            head.size == 12 &&
                String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
        } ?: false
    } catch (t: Throwable) {
        false
    }

    private fun readUpTo(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n <= 0) break
            read += n
        }
        return if (read == count) out else out.copyOf(read)
    }

    // ---------------------------------------------------------------- WAV ---

    private fun decodeWav(
        context: Context,
        uri: Uri,
        target: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val pcmOut = PCM16OutputStream(target)
        var audioFormat = 1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataBytes = -1L
        var outputFrames = 0L

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                if (readUpTo(input, 12).size != 12) throw DecodeException("WAV 헤더가 짧습니다.")

                var foundData = false
                while (!foundData) {
                    val chunk = readUpTo(input, 8)
                    if (chunk.size < 8) throw DecodeException("WAV data 청크를 찾지 못했습니다.")
                    val id = String(chunk, 0, 4, Charsets.US_ASCII)
                    val size = leInt(chunk, 4).toLong() and 0xFFFFFFFFL

                    when (id) {
                        "fmt " -> {
                            val body = readUpTo(input, size.toInt().coerceAtMost(64))
                            if (body.size < 16) throw DecodeException("WAV 포맷 정보가 잘못되었습니다.")
                            audioFormat = leShort(body, 0)
                            channels = leShort(body, 2)
                            sampleRate = leInt(body, 4)
                            bits = leShort(body, 14)
                            if (size > body.size) skipFully(input, size - body.size)
                            if (size % 2L == 1L) skipFully(input, 1)
                        }
                        "data" -> {
                            foundData = true
                            // 0 / 0xFFFFFFFF mean "streamed, size unknown".
                            dataBytes = if (size <= 0 || size == 0xFFFFFFFFL) -1L else size
                        }
                        else -> {
                            skipFully(input, size)
                            if (size % 2L == 1L) skipFully(input, 1)
                        }
                    }
                }

                if (channels <= 0 || sampleRate <= 0 || bits <= 0) {
                    throw DecodeException("WAV 포맷 정보를 읽지 못했습니다.")
                }
                val bytesPerSample = bits / 8
                if (bytesPerSample <= 0) throw DecodeException("지원하지 않는 비트 깊이입니다.")
                if (audioFormat != 1 && audioFormat != 3) {
                    throw DecodeException("지원하지 않는 WAV 인코딩입니다 (format $audioFormat).")
                }
                if (audioFormat == 3 && bits != 32) {
                    throw DecodeException("지원하지 않는 WAV float 형식입니다.")
                }

                val frameBytes = bytesPerSample * channels
                val resampler = MonoResampler(sampleRate, Wav.TARGET_SAMPLE_RATE)
                val sink = ShortSink()
                val buffer = ByteArray(frameBytes * 2048)
                var remaining = if (dataBytes <= 0) Long.MAX_VALUE else dataBytes
                var consumed = 0L

                while (remaining > 0) {
                    if (isCancelled()) throw CancellationException("decoding cancelled")
                    val want = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read <= 0) break
                    remaining -= read
                    consumed += read

                    val frames = read / frameBytes
                    if (frames <= 0) continue
                    val mono = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += readSample(
                                buffer,
                                (f * channels + c) * bytesPerSample,
                                audioFormat,
                                bits,
                            )
                        }
                        mono[f] = sum / channels
                    }

                    sink.reset()
                    resampler.process(mono, frames, sink)
                    pcmOut.write(sink.array(), sink.size)
                    outputFrames += sink.size
                    if (dataBytes > 0) {
                        onProgress((consumed.toFloat() / dataBytes.toFloat()).coerceIn(0f, 1f))
                    }
                }
            } ?: throw DecodeException("파일을 열 수 없습니다.")

            return pcmOut.close()
        } catch (t: Throwable) {
            pcmOut.abort()
            throw t
        }
    }

    private fun readSample(buffer: ByteArray, offset: Int, format: Int, bits: Int): Float {
        return when {
            format == 3 -> {
                val v = (buffer[offset].toInt() and 0xFF) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                    (buffer[offset + 3].toInt() shl 24)
                Float.fromBits(v)
            }
            bits == 8 -> ((buffer[offset].toInt() and 0xFF) - 128) / 128f
            bits == 16 -> {
                val v = (buffer[offset].toInt() and 0xFF) or (buffer[offset + 1].toInt() shl 8)
                v.toShort() / 32768f
            }
            bits == 24 -> {
                val v = (buffer[offset].toInt() and 0xFF) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                    (buffer[offset + 2].toInt() shl 16)
                v / 8388608f
            }
            bits == 32 -> {
                val v = (buffer[offset].toInt() and 0xFF) or
                    ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
                    (buffer[offset + 3].toInt() shl 24)
                v / 2147483648f
            }
            else -> throw DecodeException("지원하지 않는 비트 깊이: $bits")
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        val scratch = ByteArray(8192)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                val read = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                if (read <= 0) return
                left -= read
            }
        }
    }

    // --------------------------------------------------------- MediaCodec ---

    private fun decodeWithMediaCodec(
        context: Context,
        uri: Uri,
        target: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val pcmOut = PCM16OutputStream(target)

        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw DecodeException("이 파일에서 오디오 트랙을 찾지 못했습니다.")
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw DecodeException("오디오 형식을 알 수 없습니다.")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(-1L)
            } else {
                -1L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var resampler = MonoResampler(sampleRate, Wav.TARGET_SAMPLE_RATE)
            val sink = ShortSink()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idlePolls = 0

            while (!outputDone) {
                if (isCancelled()) throw CancellationException("decoding cancelled")

                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex) ?: ByteBuffer.allocate(0)
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idlePolls++
                        // A broken stream can keep the decoder spinning forever.
                        if (inputDone && idlePolls > 300) {
                            Log.w(TAG, "decoder stalled without EOS, stopping")
                            outputDone = true
                        }
                    }

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = codec.outputFormat
                        sampleRate = outFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        resampler = MonoResampler(sampleRate, Wav.TARGET_SAMPLE_RATE)
                        Log.i(TAG, "decoder output: $sampleRate Hz, $channels ch")
                    }

                    else -> if (outIndex >= 0) {
                        idlePolls = 0
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                val frames = shorts.remaining() / channels
                                val mono = FloatArray(frames)
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until channels) {
                                        sum += shorts.get(f * channels + c) / 32768f
                                    }
                                    mono[f] = sum / channels
                                }
                                sink.reset()
                                resampler.process(mono, frames, sink)
                                pcmOut.write(sink.array(), sink.size)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)

                        if (durationUs > 0) {
                            onProgress(
                                (bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat())
                                    .coerceIn(0f, 1f),
                            )
                        }

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            return pcmOut.close()
        } catch (t: Throwable) {
            pcmOut.abort()
            when (t) {
                is CancellationException, is DecodeException -> throw t
                else -> throw DecodeException(t.message ?: "오디오 디코딩 실패", t)
            }
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            extractor.release()
        }
    }

    private fun MediaFormat.intOrDefault(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    /** Writes raw PCM16 samples and patches the WAV header when closed. */
    private class PCM16OutputStream(private val file: File) {
        private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        private val scratch = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
        private var dataBytes = 0L

        init {
            Wav.writeHeader(out, 0)
        }

        fun write(shorts: ShortArray, count: Int) {
            if (count <= 0) return
            var offset = 0
            while (offset < count) {
                val n = minOf(count - offset, scratch.capacity() / 2)
                scratch.clear()
                scratch.asShortBuffer().put(shorts, offset, n)
                out.write(scratch.array(), 0, n * 2)
                offset += n
                dataBytes += n * 2L
            }
        }

        fun close(): Long {
            out.flush()
            out.close()
            Wav.patchHeader(file, dataBytes)
            return dataBytes / 32
        }

        fun abort() {
            try {
                out.close()
            } catch (_: Throwable) {
            }
            file.delete()
        }
    }

    private fun leShort(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun leInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
