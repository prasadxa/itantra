package org.itantra.tts.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTest {
    @Test
    fun `0 ms is empty`() {
        assertEquals(0, Silence.samples(0, 24000).size)
    }

    @Test
    fun `negative ms is empty`() {
        assertEquals(0, Silence.samples(-10, 24000).size)
    }

    @Test
    fun `100 ms at 24 kHz is 2400 samples of zero`() {
        val s = Silence.samples(100, 24000)
        assertEquals(2400, s.size)
        for (v in s) assertTrue(v == 0f)
    }
}
