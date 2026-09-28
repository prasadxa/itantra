package org.itantra.tts.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainTest {
    @Test
    fun `0 dB is a no-op`() {
        val input = floatArrayOf(0.1f, -0.2f, 0.05f)
        val out = Gain.applyDb(input, 0f)
        assertEquals(input.toList(), out.toList())
    }

    @Test
    fun `positive gain increases amplitude for quiet samples`() {
        val input = floatArrayOf(0.1f, -0.1f)
        val out = Gain.applyDb(input, 6f)
        assertTrue(abs(out[0]) > abs(input[0]))
        assertTrue(abs(out[1]) > abs(input[1]))
    }

    @Test
    fun `never exceeds unity even with large positive gain`() {
        val input = FloatArray(200) { i -> if (i % 2 == 0) 0.9f else -0.9f }
        val out = Gain.applyDb(input, 24f)
        for (s in out) assertTrue("sample $s exceeds [-1,1]", abs(s) <= 1.0001f)
    }

    @Test
    fun `negative gain reduces amplitude`() {
        val input = floatArrayOf(0.5f, -0.5f)
        val out = Gain.applyDb(input, -6f)
        assertTrue(abs(out[0]) < abs(input[0]))
    }

    @Test
    fun `soft limiter passes through samples under the threshold unchanged`() {
        val x = 0.5f
        assertEquals(x, Gain.softLimit(x), 1e-6f)
        assertEquals(-x, Gain.softLimit(-x), 1e-6f)
    }

    @Test
    fun `soft limiter compresses but never inverts sign`() {
        val out = Gain.softLimit(3.0f)
        assertTrue(out in Gain.LIMITER_THRESHOLD..1f)
    }

    @Test
    fun `dbToLinear is unity at 0 dB`() {
        assertEquals(1f, Gain.dbToLinear(0f), 1e-6f)
    }
}
