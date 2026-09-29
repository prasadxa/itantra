package org.itantra.tts.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadeTest {

    private val sampleRate = 24000

    @Test
    fun `fadeIn starts at (near) zero and ramps up to the original value`() {
        val input = FloatArray(1000) { 0.5f }
        val out = Fade.fadeIn(input, ms = 8, sampleRate = sampleRate)
        val n = sampleRate * 8 / 1000
        assertEquals(0f, out[0], 1e-6f)
        assertTrue(out[n / 2] in 0f..0.5f)
        // Fully ramped back to the original value past the fade window.
        assertEquals(0.5f, out[n], 1e-6f)
        assertEquals(0.5f, out[out.size - 1], 1e-6f)
    }

    @Test
    fun `fadeIn ramp is monotonically non-decreasing over the fade window`() {
        val input = FloatArray(500) { 0.8f }
        val out = Fade.fadeIn(input, ms = 8, sampleRate = sampleRate)
        val n = sampleRate * 8 / 1000
        for (i in 1 until n) {
            assertTrue("expected non-decreasing at $i", out[i] >= out[i - 1] - 1e-6f)
        }
    }

    @Test
    fun `fadeOut ends at (near) zero and starts at the original value`() {
        val input = FloatArray(1000) { 0.5f }
        val out = Fade.fadeOut(input, ms = 8, sampleRate = sampleRate)
        assertEquals(0.5f, out[0], 1e-6f)
        // Linear ramp's last sample is 1/n of the way from zero (n = fade-window sample count), not
        // exactly zero - "near zero" relative to the un-faded 0.5f amplitude.
        assertTrue("expected near zero, got ${out[out.size - 1]}", abs(out[out.size - 1]) < 0.02f)
    }

    @Test
    fun `fade on a buffer shorter than the fade window ramps over the whole buffer`() {
        val input = FloatArray(10) { 0.4f }
        val out = Fade.fadeIn(input, ms = 8, sampleRate = sampleRate) // 8ms @ 24kHz = 192 samples > 10
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(10, out.size)
        // Ends before reaching full amplitude since the whole (short) buffer is inside the ramp.
        assertTrue(out[9] < 0.4f)
    }

    @Test
    fun `zero ms or empty input is a no-op`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertEquals(input.toList(), Fade.fadeIn(input, ms = 0, sampleRate = sampleRate).toList())
        assertEquals(input.toList(), Fade.fadeOut(input, ms = 0, sampleRate = sampleRate).toList())
        assertEquals(0, Fade.fadeIn(FloatArray(0), ms = 8, sampleRate = sampleRate).size)
    }

    @Test
    fun `does not mutate the input array`() {
        val input = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        val copy = input.copyOf()
        Fade.fadeIn(input, ms = 8, sampleRate = sampleRate)
        assertEquals(copy.toList(), input.toList())
    }
}
