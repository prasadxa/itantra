package org.itantra.tts.dsp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Time-scale modification (speed up / slow down) without changing pitch, via a simplified WSOLA
 * (Waveform-Similarity Overlap-Add): fixed-size analysis frames are pulled from the input at a
 * rate-scaled hop, nudged by up to [SEARCH_RADIUS_MS] to the offset that best cross-correlates
 * with the tail already written to the output, then triangular-window overlap-added at a
 * constant synthesis hop. This is what makes [org.itantra.core.TtsSegment.rate] work uniformly
 * across engines (neither Mio nor VITS is asked to render "faster" itself; see `SpeechOutput`).
 */
object Wsola {
    private const val FRAME_MS = 20
    private const val OVERLAP_MS = 10
    private const val SEARCH_RADIUS_MS = 4

    /** [rate] 1.0 = unchanged; 1.2 = 20% faster (shorter output); 0.8 = 20% slower. */
    fun changeRate(input: FloatArray, rate: Float, sampleRate: Int): FloatArray {
        if (input.isEmpty() || rate == 1f || rate <= 0f) return input

        val frameSize = max(32, sampleRate * FRAME_MS / 1000)
        if (input.size <= frameSize) return input

        val synthesisHop = max(1, sampleRate * (FRAME_MS - OVERLAP_MS) / 1000)
        val overlapSize = min(frameSize, synthesisHop)
        val searchRadius = max(0, sampleRate * SEARCH_RADIUS_MS / 1000)
        val analysisHopIdeal = max(1, (synthesisHop * rate).roundToInt())

        // Generous slack over the ideal 1/rate output length; grown further if ever needed.
        val slack = 4 * frameSize
        var out = FloatArray((input.size / rate).roundToInt() + slack)

        copyRange(input, 0, frameSize, out, 0)
        var written = frameSize
        // Deterministic schedule for the *ideal* (unadjusted) input read position: it always
        // advances by analysisHopIdeal, independent of the search below. This is what makes the
        // output length track input.size/rate; the search only nudges *which* samples are read
        // for phase alignment; it must never be allowed to redefine the schedule itself (letting
        // the next ideal position follow the previous search result lets small per-frame biases
        // compound over many frames, e.g. on periodic/tonal input where many offsets correlate
        // similarly well, and the output length stops tracking rate at all).
        var idealIn = analysisHopIdeal.toDouble()

        while (idealIn.roundToInt() + frameSize <= input.size) {
            val idealInt = idealIn.roundToInt()
            val searchStart = max(0, idealInt - searchRadius)
            val searchEnd = min(input.size - frameSize, idealInt + searchRadius)
            val tailStart = written - overlapSize

            var bestOffset = idealInt.coerceIn(searchStart, searchEnd)
            if (searchEnd > searchStart) {
                var bestScore = Float.NEGATIVE_INFINITY
                for (candidate in searchStart..searchEnd) {
                    val score = crossCorrelation(out, tailStart, input, candidate, overlapSize)
                    if (score > bestScore) {
                        bestScore = score
                        bestOffset = candidate
                    }
                }
            }

            val needed = tailStart + frameSize
            if (needed > out.size) out = out.copyOf(needed + slack)
            overlapAdd(input, bestOffset, frameSize, overlapSize, out, tailStart)
            written = tailStart + frameSize

            idealIn += analysisHopIdeal
        }

        return out.copyOf(written)
    }

    private fun copyRange(src: FloatArray, srcPos: Int, len: Int, dst: FloatArray, dstPos: Int) {
        System.arraycopy(src, srcPos, dst, dstPos, min(len, src.size - srcPos))
    }

    private fun crossCorrelation(a: FloatArray, aStart: Int, b: FloatArray, bStart: Int, len: Int): Float {
        var sum = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until len) {
            val av = a[aStart + i]
            val bv = b[bStart + i]
            sum += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA * normB)
        return if (denom < 1e-9f) sum else sum / denom
    }

    /** Triangular-window crossfade of `input[offset, offset+frameSize)` into `out` at `outPos`. */
    private fun overlapAdd(
        input: FloatArray,
        offset: Int,
        frameSize: Int,
        overlapSize: Int,
        out: FloatArray,
        outPos: Int,
    ) {
        for (i in 0 until overlapSize) {
            val fadeIn = (i + 1f) / (overlapSize + 1f)
            val existing = out[outPos + i]
            out[outPos + i] = existing * (1f - fadeIn) + input[offset + i] * fadeIn
        }
        for (i in overlapSize until frameSize) {
            out[outPos + i] = input[offset + i]
        }
    }
}
