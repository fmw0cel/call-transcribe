package com.localtranscribe.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming mono resampler for speech.
 *
 * Downsampling runs through a 4th-order Butterworth low-pass (two cascaded
 * biquads, cutoff 0.45 * output rate) and then linear interpolation. That is
 * plenty for whisper's 16 kHz mel input and keeps the CPU cost negligible on
 * a phone.
 */
class MonoResampler(
    private val inRate: Int,
    private val outRate: Int,
) {
    private val step = inRate.toDouble() / outRate

    private val filters: Array<Biquad> =
        if (inRate > outRate) {
            val cutoff = 0.45f * outRate
            arrayOf(
                Biquad.lowPass(inRate.toFloat(), cutoff, 0.5411961f),
                Biquad.lowPass(inRate.toFloat(), cutoff, 1.3065630f),
            )
        } else {
            emptyArray()
        }

    private var prev = 0f
    private var t = 0.0
    private var started = false

    /** Resamples [count] mono samples from [input] and appends PCM16 to [out]. */
    fun process(input: FloatArray, count: Int, out: ShortSink) {
        if (count <= 0) return

        if (filters.isNotEmpty()) {
            for (f in filters) {
                for (i in 0 until count) input[i] = f.process(input[i])
            }
        }

        if (!started) {
            // Start at the first real sample so the output does not begin with a
            // bogus zero (which would add a click at the start of every file).
            prev = input[0]
            t = step
            started = true
        }

        var i = t.toInt()
        var frac = (t - i).toFloat()
        while (i + 1 <= count - 1) {
            val s0 = if (i == 0) prev else input[i - 1]
            val s1 = input[i]
            val value = s0 + (s1 - s0) * frac
            out.write(clamp16(value))
            t += step
            i = t.toInt()
            frac = (t - i).toFloat()
        }

        // Keep one sample of overlap so the next block continues seamlessly.
        if (count >= 2) {
            prev = input[count - 2]
            t -= (count - 1)
            if (t < 0) t = 0.0
        }
    }

    private fun clamp16(value: Float): Short {
        val v = (value * 32767f).toInt()
        return v.coerceIn(-32768, 32767).toShort()
    }

    private class Biquad(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float,
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        companion object {
            /** RBJ cookbook low-pass. */
            fun lowPass(sampleRate: Float, cutoff: Float, q: Float): Biquad {
                val w0 = 2.0 * PI * (min(cutoff, sampleRate * 0.49f) / sampleRate).toDouble()
                val cosW = cos(w0)
                val alpha = sin(w0) / (2.0 * q.toDouble())
                val a0 = 1.0 + alpha

                val b0 = ((1.0 - cosW) / 2.0) / a0
                val b1 = (1.0 - cosW) / a0
                val b2 = b0
                val a1 = (-2.0 * cosW) / a0
                val a2 = (1.0 - alpha) / a0
                return Biquad(
                    b0 = b0.toFloat(),
                    b1 = b1.toFloat(),
                    b2 = b2.toFloat(),
                    a1 = a1.toFloat(),
                    a2 = a2.toFloat(),
                )
            }
        }
    }
}

/** Tiny growable PCM16 sink so the resampler does not allocate per block. */
class ShortSink(initialCapacity: Int = 1 shl 16) {
    private var buffer = ShortArray(initialCapacity)
    var size: Int = 0
        private set

    fun reset() {
        size = 0
    }

    fun write(value: Short) {
        if (size == buffer.size) {
            buffer = buffer.copyOf(buffer.size * 2)
        }
        buffer[size++] = value
    }

    fun array(): ShortArray = buffer

    fun isEmpty(): Boolean = size == 0
}
