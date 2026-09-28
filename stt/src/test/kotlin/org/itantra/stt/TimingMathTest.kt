package org.itantra.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class TimingMathTest {

    @Test
    fun `samplesToMs converts one second at 16kHz`() {
        assertEquals(1000L, TimingMath.samplesToMs(16000L, 16000))
    }

    @Test
    fun `samplesToMs converts half second`() {
        assertEquals(500L, TimingMath.samplesToMs(8000L, 16000))
    }

    @Test
    fun `samplesToMs of zero samples is zero`() {
        assertEquals(0L, TimingMath.samplesToMs(0L, 16000))
    }

    @Test
    fun `epochMsAt offsets the anchor by the sample duration`() {
        val anchor = 1_700_000_000_000L
        val result = TimingMath.epochMsAt(anchor, 16000L, 16000)
        assertEquals(anchor + 1000L, result)
    }

    @Test
    fun `epochMsAt with zero samples returns the anchor`() {
        val anchor = 1_700_000_000_000L
        assertEquals(anchor, TimingMath.epochMsAt(anchor, 0L, 16000))
    }

    @Test
    fun `audioSeconds converts sample count to seconds`() {
        assertEquals(1.0f, TimingMath.audioSeconds(16000, 16000), 1e-6f)
        assertEquals(0.25f, TimingMath.audioSeconds(4000, 16000), 1e-6f)
    }

    @Test
    fun `speech segment start and end round-trip through sample math`() {
        // A 1.5s segment starting 2.0s into the stream.
        val anchor = 1_700_000_000_000L
        val startSamples = 32000L // 2.0s
        val lengthSamples = 24000 // 1.5s

        val speechStartAt = TimingMath.epochMsAt(anchor, startSamples, 16000)
        val speechEndAt = TimingMath.epochMsAt(anchor, startSamples + lengthSamples, 16000)

        assertEquals(anchor + 2000L, speechStartAt)
        assertEquals(anchor + 3500L, speechEndAt)
        assertEquals(1.5f, TimingMath.audioSeconds(lengthSamples, 16000), 1e-6f)
    }
}
