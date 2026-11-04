package com.localtranscribe.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MonoResamplerTest {

    @Test
    fun `downsamples 48k to 16k`() {
        val resampler = MonoResampler(48_000, 16_000)
        val sink = ShortSink()
        val frames = 48_000
        val input = FloatArray(frames) { sin(2.0 * PI * 440.0 * it / 48_000.0).toFloat() * 0.5f }

        resampler.process(input, frames, sink)

        val expected = 16_000
        assertTrue("got ${sink.size} samples", kotlin.math.abs(sink.size - expected) <= 2)
    }

    @Test
    fun `keeps frequency through a 48k to 16k conversion`() {
        val resampler = MonoResampler(48_000, 16_000)
        val sink = ShortSink()
        val frames = 48_000
        val input = FloatArray(frames) { sin(2.0 * PI * 440.0 * it / 48_000.0).toFloat() * 0.7f }
        resampler.process(input, frames, sink)

        // Count zero crossings of the 440 Hz tone in the output (16 kHz).
        var crossings = 0
        var prev = sink.array()[1]
        for (i in 2 until sink.size) {
            val current = sink.array()[i]
            if ((prev < 0 && current >= 0) || (prev >= 0 && current < 0)) crossings++
            prev = current
        }
        // 440 Hz over ~1 s gives ~880 crossings.
        assertTrue("crossings=$crossings", crossings in 850..910)
    }

    @Test
    fun `passthrough at the same rate keeps sample values`() {
        val resampler = MonoResampler(16_000, 16_000)
        val sink = ShortSink()
        val input = floatArrayOf(0.5f, -0.5f, 0.25f, 0f, 0.9f)
        resampler.process(input, input.size, sink)

        // Linear interpolation needs the next sample, so the block is flushed
        // with the following block (one sample of latency).
        assertEquals(3, sink.size)
        assertEquals(16383, sink.array()[0].toInt())
        assertEquals(-16383, sink.array()[1].toInt())
        assertEquals(8191, sink.array()[2].toInt())
    }
}
