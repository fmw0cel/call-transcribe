package com.localtranscribe.audio

import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `header roundtrip reports duration`() {
        val file = temp.newFile("tone.wav")
        FileOutputStream(file).use { out ->
            Wav.writeHeader(out, 32_000L * 2)
            out.write(ByteArray(32_000 * 2))
        }

        val info = Wav.readInfo(file)
        assertNotNull(info)
        assertEquals(16_000, info!!.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(2_000L, info.durationMs)
    }

    @Test
    fun `patch header fixes streamed sizes`() {
        val file = temp.newFile("patched.wav")
        FileOutputStream(file).use { out ->
            Wav.writeHeader(out, 0)
            out.write(ByteArray(16_000))
        }
        Wav.patchHeader(file, 16_000)
        assertEquals(500L, Wav.readInfo(file)!!.durationMs)
    }

    @Test
    fun `non wav data is rejected`() {
        val file = temp.newFile("not.wav")
        file.writeText("this is not audio at all")
        assertNull(Wav.readInfo(file))
    }
}
