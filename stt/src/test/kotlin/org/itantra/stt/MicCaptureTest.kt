package org.itantra.stt

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicCaptureTest {

    @Test
    fun `pcm16ToFloat maps extremes`() {
        val pcm = shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE)
        val floats = MicCapture.pcm16ToFloat(pcm)

        assertEquals(-1.0f, floats[0], 1e-6f)
        assertEquals(0.0f, floats[1], 1e-6f)
        assertTrue(abs(floats[2] - 0.99997f) < 1e-3f)
    }

    @Test
    fun `pcm16ToFloat respects count shorter than array`() {
        val pcm = shortArrayOf(16384, 16384, 16384, 16384)
        val floats = MicCapture.pcm16ToFloat(pcm, count = 2)

        assertEquals(2, floats.size)
        assertEquals(0.5f, floats[0], 1e-6f)
    }

    @Test
    fun `pcm16ToFloat stays within unit range`() {
        val pcm = ShortArray(1000) { ((it * 137) % 65536 - 32768).toShort() }
        val floats = MicCapture.pcm16ToFloat(pcm)

        for (f in floats) {
            assertTrue(f in -1.0f..1.0f)
        }
    }

    @Test
    fun `chunk size is ~20ms at 16kHz`() {
        assertEquals(SAMPLE_RATE_16K, MicCapture.SAMPLE_RATE)
        assertEquals(320, MicCapture.CHUNK_SAMPLES)
        // 320 samples / 16000 Hz = 20ms
        assertEquals(20.0, MicCapture.CHUNK_SAMPLES * 1000.0 / MicCapture.SAMPLE_RATE, 1e-9)
    }

    companion object {
        private const val SAMPLE_RATE_16K = 16000
    }
}
