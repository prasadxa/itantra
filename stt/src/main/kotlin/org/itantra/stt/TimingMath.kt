package org.itantra.stt

/**
 * Pure sample-count <-> wall-clock math shared by [SherpaSttEngine]. Kept free of any
 * sherpa-onnx / Android types so it can be exercised by plain JVM unit tests.
 */
internal object TimingMath {
    /** Converts a sample count at [sampleRate] Hz to milliseconds. */
    fun samplesToMs(samples: Long, sampleRate: Int): Long = samples * 1000L / sampleRate

    /** Epoch ms of a VAD segment boundary, [samples] after [anchorEpochMs]. */
    fun epochMsAt(anchorEpochMs: Long, samples: Long, sampleRate: Int): Long =
        anchorEpochMs + samplesToMs(samples, sampleRate)

    /** Segment length in seconds, for [org.itantra.core.RecognizedSentence.audioSeconds]. */
    fun audioSeconds(sampleCount: Int, sampleRate: Int): Float = sampleCount / sampleRate.toFloat()
}
