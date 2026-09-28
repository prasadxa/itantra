package org.itantra.tts.dsp

import kotlin.math.floor

/**
 * Streaming linear-interpolation sample-rate converter. Carries one sample of history ("carry")
 * across [process] calls so chunk boundaries (as streamed by [org.itantra.core.TtsEngine]) don't
 * introduce a discontinuity. Used by `FallbackTtsEngine` to bring VITS's 22050 Hz output up to
 * the shared 24000 Hz stream rate.
 *
 * One instance per synthesis call (it is not thread-safe and keeps per-stream state).
 */
class Resampler(private val fromRate: Int, private val toRate: Int) {
    private val ratio: Double = fromRate.toDouble() / toRate.toDouble()
    private var absPos = 0.0
    private var chunkStartAbs = 0.0
    private var carry = 0f

    /** Resamples one chunk; call repeatedly, in order, for a whole stream. */
    fun process(input: FloatArray): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input

        val n = input.size
        val out = ArrayList<Float>((n / ratio).toInt() + 2)

        fun sampleAt(localIdx: Int): Float = if (localIdx < 0) carry else input[localIdx]

        var local = absPos - chunkStartAbs
        while (true) {
            val i0 = floor(local).toInt()
            if (i0 + 1 >= n) break
            val frac = (local - i0).toFloat()
            val s0 = sampleAt(i0)
            val s1 = sampleAt(i0 + 1)
            out.add(s0 + (s1 - s0) * frac)
            absPos += ratio
            local = absPos - chunkStartAbs
        }

        carry = input[n - 1]
        chunkStartAbs += n
        return out.toFloatArray()
    }
}
