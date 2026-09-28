package org.itantra.tts.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsolaTest {
    private val sampleRate = 24000

    private fun tone(seconds: Double, freqHz: Double = 220.0): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { i -> sin(2.0 * PI * freqHz * i / sampleRate).toFloat() }
    }

    private fun rms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size).toFloat()
    }

    @Test
    fun `rate 1 is a no-op`() {
        val input = tone(0.5)
        val out = Wsola.changeRate(input, 1f, sampleRate)
        assertEquals(input.toList(), out.toList())
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(0, Wsola.changeRate(FloatArray(0), 1.2f, sampleRate).size)
    }

    @Test
    fun `very short input below one frame is returned unchanged`() {
        val input = FloatArray(8) { 0.1f }
        val out = Wsola.changeRate(input, 1.5f, sampleRate)
        assertEquals(input.toList(), out.toList())
    }

    @Test
    fun `speeding up shortens the signal roughly proportionally`() {
        val input = tone(1.0)
        val out = Wsola.changeRate(input, 1.2f, sampleRate)
        val expected = input.size / 1.2
        assertTrue(
            "out=${out.size} expected~=$expected",
            abs(out.size - expected) < expected * 0.15,
        )
    }

    @Test
    fun `slowing down lengthens the signal roughly proportionally`() {
        val input = tone(1.0)
        val out = Wsola.changeRate(input, 0.8f, sampleRate)
        val expected = input.size / 0.8
        assertTrue(
            "out=${out.size} expected~=$expected",
            abs(out.size - expected) < expected * 0.15,
        )
    }

    @Test
    fun `energy is roughly preserved (no silent or blown-out output)`() {
        val input = tone(1.0)
        val out = Wsola.changeRate(input, 1.3f, sampleRate)
        val inRms = rms(input)
        val outRms = rms(out)
        assertTrue("outRms=$outRms inRms=$inRms", outRms > inRms * 0.5f && outRms < inRms * 1.5f)
    }

    @Test
    fun `output stays within unity range for a well-formed tone`() {
        val input = tone(1.0)
        val out = Wsola.changeRate(input, 1.4f, sampleRate)
        for (s in out) assertTrue(abs(s) <= 1.05f)
    }
}
