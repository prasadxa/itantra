package org.itantra.tts.dsp

/** [org.itantra.core.TtsSegment.pauseBeforeMs] -> a block of zero samples. */
object Silence {
    fun samples(ms: Int, sampleRate: Int): FloatArray {
        if (ms <= 0) return FloatArray(0)
        val n = (sampleRate.toLong() * ms / 1000L).toInt()
        return FloatArray(n.coerceAtLeast(0))
    }
}
