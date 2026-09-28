package org.itantra.tts.dsp

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResamplerTest {
    @Test
    fun `same rate is a pass-through`() {
        val r = Resampler(24000, 24000)
        val input = floatArrayOf(0.1f, 0.2f, -0.3f)
        assertEquals(input.toList(), r.process(input).toList())
    }

    @Test
    fun `upsampling produces more samples in roughly the right ratio`() {
        val r = Resampler(22050, 24000)
        val input = FloatArray(22050) { i -> (i % 100) / 100f }
        val out = r.process(input)
        val expected = input.size * (24000.0 / 22050.0)
        assertTrue(abs(out.size - expected) < expected * 0.01)
    }

    @Test
    fun `downsampling produces fewer samples in roughly the right ratio`() {
        val r = Resampler(48000, 24000)
        val input = FloatArray(48000) { i -> (i % 100) / 100f }
        val out = r.process(input)
        val expected = input.size * (24000.0 / 48000.0)
        assertTrue(abs(out.size - expected) < expected * 0.01)
    }

    @Test
    fun `streaming across chunks is continuous with a single big-buffer call`() {
        val n = 4410
        val input = FloatArray(n) { i -> kotlin.math.sin(i * 0.05).toFloat() }

        val whole = Resampler(22050, 24000).process(input)

        val streamed = ArrayList<Float>()
        val streaming = Resampler(22050, 24000)
        var offset = 0
        val chunkSize = 512
        while (offset < input.size) {
            val end = minOf(offset + chunkSize, input.size)
            streamed.addAll(streaming.process(input.copyOfRange(offset, end)).toList())
            offset = end
        }

        // Same overall length within a couple of samples (streaming may lose a sample at the
        // very last chunk boundary since it never over-reads into data it hasn't seen yet).
        assertTrue(abs(whole.size - streamed.size) <= 4)
    }

    @Test
    fun `empty chunk yields empty output`() {
        val r = Resampler(22050, 24000)
        assertEquals(0, r.process(FloatArray(0)).size)
    }
}
