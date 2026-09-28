package org.itantra.tts.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PitchTest {
    @Test
    fun `documented no-op returns input unchanged`() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = Pitch.shiftSemitones(input, 3f, 24000)
        assertArrayEquals(input, out, 0f)
    }
}
