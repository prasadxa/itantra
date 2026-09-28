package org.itantra.tts.dsp

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/** dB gain with a soft (tanh-kneed) limiter so a hot [TtsSegment.volumeDb] can't clip. */
object Gain {
    /** Samples at/under this magnitude (post-gain) pass through unchanged; above it, soft-knee. */
    const val LIMITER_THRESHOLD = 0.8f

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    /** Applies [db] gain to [samples] (in [-1, 1]); output never exceeds [-1, 1]. */
    fun applyDb(samples: FloatArray, db: Float): FloatArray {
        if (db == 0f) return samples
        val linear = dbToLinear(db)
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = softLimit(samples[i] * linear)
        }
        return out
    }

    /**
     * Identity below [LIMITER_THRESHOLD]; above it, compresses smoothly towards +/-1 with a tanh
     * knee so gain never produces a hard clip.
     */
    fun softLimit(x: Float, threshold: Float = LIMITER_THRESHOLD): Float {
        val ax = abs(x)
        if (ax <= threshold) return x
        val sign = if (x < 0f) -1f else 1f
        val headroom = 1f - threshold
        val excess = ax - threshold
        val compressed = threshold + headroom * tanh(excess / headroom)
        return sign * compressed
    }
}
