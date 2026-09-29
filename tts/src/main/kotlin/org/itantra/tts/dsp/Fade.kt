package org.itantra.tts.dsp

/**
 * Short linear fade applied where two independently-synthesized PCM buffers meet — clause joins
 * (see `VitsTtsEngine`, which splits long sentences into clauses via `ClauseSplitter` and renders
 * each independently), `TtsSegment` boundaries and after a rebuffer pause (see `SpeechOutput`).
 * Each such join can otherwise start at a non-zero sample / arbitrary phase and click. This isn't a
 * true crossfade-with-overlap (chunks are written to `AudioTrack` sequentially, never mixed), but a
 * 5-10ms linear ramp is perceptually equivalent for smoothing a discontinuity this short.
 */
object Fade {
    /** Linear ramp 0 -> 1 over the first [ms] milliseconds of [samples] (whole buffer if shorter).
     * Returns [samples] unchanged if [ms] <= 0 or [samples] is empty. */
    fun fadeIn(samples: FloatArray, ms: Int, sampleRate: Int): FloatArray {
        if (samples.isEmpty() || ms <= 0) return samples
        val n = minOf(samples.size, sampleRate * ms / 1000)
        if (n <= 0) return samples
        val out = samples.copyOf()
        for (i in 0 until n) {
            out[i] = out[i] * (i.toFloat() / n)
        }
        return out
    }

    /** Linear ramp 1 -> 0 over the last [ms] milliseconds of [samples] (whole buffer if shorter). */
    fun fadeOut(samples: FloatArray, ms: Int, sampleRate: Int): FloatArray {
        if (samples.isEmpty() || ms <= 0) return samples
        val n = minOf(samples.size, sampleRate * ms / 1000)
        if (n <= 0) return samples
        val out = samples.copyOf()
        val start = out.size - n
        for (i in 0 until n) {
            out[start + i] = out[start + i] * (1f - i.toFloat() / n)
        }
        return out
    }
}
