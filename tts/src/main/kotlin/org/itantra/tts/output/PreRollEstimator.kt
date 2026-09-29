package org.itantra.tts.output

import java.util.concurrent.ConcurrentHashMap

/**
 * Adaptive pre-roll math: how much audio [SpeechOutput] should buffer before starting `AudioTrack`
 * playback so that synthesis (running concurrently on the producer thread, see [AudioChunkQueue])
 * has enough of a head start that the writer never catches up to an empty queue while synthesis is
 * still slower than real time (RTF > 1) — see `SpeechOutput`'s class doc and docs/design.md's
 * Indic-Mio RTF numbers (1.0-1.46 on Snapdragon 870, the underrun root cause this addresses).
 *
 * Two EMAs are tracked per (engine, lang), refined online via [recordSample] as real messages are
 * synthesized:
 *  - synthesis RTF (synth-time / audio-duration), seeded per docs/design.md: Mio 1.3, VITS 0.7.
 *  - chars-per-second (audio-duration / input-text-length), seeded at [DEFAULT_CHARS_PER_SECOND]
 *    (12-15 is typical for Indic speech), used to estimate an utterance's audio duration from its
 *    text length before any of it has been synthesized.
 *
 * Pure Kotlin, no Android dependency — see `PreRollEstimatorTest`.
 */
class PreRollEstimator(private val alpha: Float = 0.3f) {

    private data class Key(val engine: String, val lang: String)

    private val rtfEma = ConcurrentHashMap<Key, Float>()
    private val charsPerSecondEma = ConcurrentHashMap<Key, Float>()

    /** Current RTF estimate for (engine, lang); the seed value until at least one [recordSample]. */
    fun rtf(engine: String, lang: String): Float = rtfEma[Key(engine, lang)] ?: seedRtf(engine)

    /** Current chars/second estimate for (engine, lang); [DEFAULT_CHARS_PER_SECOND] until learned. */
    fun charsPerSecond(engine: String, lang: String): Float =
        charsPerSecondEma[Key(engine, lang)] ?: DEFAULT_CHARS_PER_SECOND

    /** Folds one completed segment/utterance's measurements into both EMAs. */
    fun recordSample(engine: String, lang: String, textChars: Int, audioSeconds: Float, synthSeconds: Float) {
        if (audioSeconds <= 0f) return
        val key = Key(engine, lang)
        if (synthSeconds > 0f) {
            val measuredRtf = synthSeconds / audioSeconds
            rtfEma[key] = ema(rtfEma[key] ?: seedRtf(engine), measuredRtf)
        }
        if (textChars > 0) {
            val measuredCps = textChars / audioSeconds
            charsPerSecondEma[key] = ema(charsPerSecondEma[key] ?: DEFAULT_CHARS_PER_SECOND, measuredCps)
        }
    }

    private fun ema(prev: Float, sample: Float): Float = prev + alpha * (sample - prev)

    private fun seedRtf(engine: String): Float = when (engine.lowercase()) {
        "vits" -> SEED_RTF_VITS
        else -> SEED_RTF_MIO // "mio" and any unrecognised engine name both default to the slower seed
    }

    /** Estimated utterance audio duration (ms) from its text length, before synthesis starts. */
    fun estimateDurationMs(engine: String, lang: String, textChars: Int): Long {
        val cps = charsPerSecond(engine, lang).coerceAtLeast(1f)
        return ((textChars / cps) * 1000f).toLong().coerceAtLeast(0L)
    }

    /**
     * Target pre-roll (buffered-audio-before-play) in ms:
     * `max(MIN_PREROLL_MS, estDurationMs * max(0, 1 - 1/rtf) + MARGIN_MS)`.
     * At rtf <= 1 (synthesis keeps up with or beats real time) the deficit factor is 0, so this
     * collapses to just [MARGIN_MS] above the floor — no need to over-buffer when synthesis can't
     * fall behind. As rtf grows past 1, the estimated deficit (and so the pre-roll) grows with it.
     */
    fun targetPreRollMs(engine: String, lang: String, textChars: Int): Long {
        val rtfNow = rtf(engine, lang)
        val estDurationMs = estimateDurationMs(engine, lang, textChars)
        val deficitFactor = (1f - 1f / rtfNow).coerceAtLeast(0f)
        val computed = (estDurationMs * deficitFactor).toLong() + MARGIN_MS
        return maxOf(MIN_PREROLL_MS, computed)
    }

    companion object {
        const val SEED_RTF_MIO = 1.3f
        const val SEED_RTF_VITS = 0.7f
        const val DEFAULT_CHARS_PER_SECOND = 13f
        const val MIN_PREROLL_MS = 250L
        const val MARGIN_MS = 200L
    }
}
