package com.localtranscribe.audio

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal 16-bit PCM WAV helper. Everything the app produces (recordings and
 * decoded imports) is 16 kHz mono 16-bit PCM, which is exactly what whisper
 * wants, so we never need a general purpose container parser here.
 */
object Wav {

    const val TARGET_SAMPLE_RATE = 16000

    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val audioFormat: Int,
        val dataOffset: Long,
        val dataLength: Long,
    ) {
        val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
        val frameCount: Long
            get() = if (bytesPerFrame == 0) 0 else dataLength / bytesPerFrame
        val durationMs: Long
            get() = if (sampleRate == 0) 0 else frameCount * 1000 / sampleRate
    }

    private const val HEADER_BYTES = 44

    /** Reads the RIFF header. Returns null when [file] is not a parseable WAV. */
    fun readInfo(file: File): Info? = try {
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            val header = ByteArray(minOf(HEADER_BYTES.toLong(), fileLength).toInt())
            raf.readFully(header)

            fun fourCC(offset: Int): String =
                String(header, offset, 4, Charsets.US_ASCII)

            if (header.size < 12 || fourCC(0) != "RIFF" || fourCC(8) != "WAVE") {
                null
            } else {
                var pos = 12
                var sampleRate = 0
                var channels = 0
                var bits = 0
                var format = 1
                var dataOffset = -1L
                var dataLength = 0L

                while (pos + 8 <= header.size) {
                    val id = fourCC(pos)
                    val size = leInt(header, pos + 4).toLong() and 0xFFFFFFFFL
                    val body = pos + 8

                    if (id == "fmt " && body + 16 <= header.size) {
                        format = leShort(header, body).toInt()
                        channels = leShort(header, body + 2).toInt()
                        sampleRate = leInt(header, body + 4)
                        bits = leShort(header, body + 14).toInt()
                    } else if (id == "data") {
                        dataOffset = body.toLong()
                        dataLength = if (size == 0L || size == 0xFFFFFFFFL) {
                            (fileLength - body).coerceAtLeast(0)
                        } else {
                            minOf(size, fileLength - body)
                        }
                        break
                    }
                    pos = body + size.toInt() + (size.toInt() and 1)
                }

                if (dataOffset < 0 || channels <= 0 || sampleRate <= 0) {
                    null
                } else {
                    Info(sampleRate, channels, bits, format, dataOffset, dataLength)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Writes a 44-byte header for [dataBytes] of PCM data. */
    fun writeHeader(out: OutputStream, dataBytes: Long, sampleRate: Int = TARGET_SAMPLE_RATE) {
        val byteRate = sampleRate * 2
        val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((dataBytes + 36).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(1) // mono
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2) // block align
        header.putShort(16) // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        out.write(header.array())
    }

    /** Rewrites the RIFF/data sizes of a WAV we are streaming into a file. */
    fun patchHeader(file: File, dataBytes: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            if (raf.length() < HEADER_BYTES) return
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            raf.readFully(header.array())
            header.position(4)
            header.putInt((dataBytes + 36).toInt())
            header.position(40)
            header.putInt(dataBytes.toInt())
            raf.seek(0)
            raf.write(header.array())
        }
    }

    /** Sample rate of a WAV file, or null. */
    fun sampleRateOf(file: File): Int? = readInfo(file)?.sampleRate

    private fun leShort(data: ByteArray, offset: Int): Short =
        (data[offset].toInt() and 0xFF or (data[offset + 1].toInt() and 0xFF shl 8)).toShort()

    private fun leInt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
