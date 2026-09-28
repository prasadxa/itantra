package org.itantra.tts.engine

import android.content.Context
import android.util.Log
import org.itantra.core.Lang
import org.itantra.core.ModelPaths
import org.itantra.core.TtsEngine
import org.itantra.core.TtsSegment
import org.itantra.tts.dsp.Resampler

private const val TAG = "FallbackTtsEngine"

/**
 * The [TtsEngine] the app actually uses: Indic-Mio ([MioTtsEngine]) first, sherpa-onnx VITS
 * ([VitsTtsEngine]) if Mio has no voice for the language or its native synthesis throws.
 * Both engines' output is resampled (see [Resampler]) to one shared [sampleRate] (24000 Hz —
 * Mio's native rate; VITS is 22050 Hz) so `SpeechOutput` never has to reconfigure its
 * `AudioTrack` mid-stream.
 */
class FallbackTtsEngine(
    context: Context,
    paths: ModelPaths,
    private val numThreads: Int = 4,
    /**
     * Prefer VITS where it has a voice (bn, kn, ml, mr, ta, te): measured on a Snapdragon 8 Gen 3 it
     * runs at RTF ~0.5 vs Indic-Mio's ~1.4-2.5, i.e. the difference between real time and not.
     * Mio remains the engine for hi/gu/or/en and the fallback if VITS fails.
     */
    private val preferFast: Boolean = true,
) : TtsEngine {
    private val appContext = context.applicationContext
    private val mio = MioTtsEngine(paths, numThreads)
    private val vits = VitsTtsEngine(paths, numThreads)

    override val sampleRate: Int = TARGET_SAMPLE_RATE

    override fun supports(lang: Lang): Boolean = mio.supports(lang) || vits.supports(lang)

    /** Which engine [synthesize] will try first for [lang]: "vits", "mio" or "none". */
    fun engineNameFor(lang: Lang): String {
        val order = if (preferFast) listOf("vits" to vits, "mio" to mio) else listOf("mio" to mio, "vits" to vits)
        return order.firstOrNull { it.second.supports(lang) }?.first ?: "none"
    }

    /**
     * Both engines load their weights lazily on first use (~1 GB for Mio), which would otherwise
     * land on the first received message. Synthesize a throwaway word on a background thread.
     */
    fun warmUp() {
        Thread({
            for ((engine, lang) in listOf(mio to Lang.HI, vits to Lang.TA)) {
                if (!engine.supports(lang)) continue
                runCatching { engine.synthesize(lang, TtsSegment(".")) { } }
                    .onFailure { Log.w(TAG, "warm-up failed for ${engine.javaClass.simpleName}", it) }
            }
        }, "tts-warmup").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    override fun synthesize(lang: Lang, segment: TtsSegment, onChunk: (FloatArray) -> Unit) {
        val order = if (preferFast) listOf(vits, mio) else listOf(mio, vits)
        var lastError: Exception? = null
        for (engine in order) {
            if (!engine.supports(lang)) continue
            try {
                synthesizeResampled(engine, lang, segment, onChunk)
                return
            } catch (e: Exception) {
                Log.w(TAG, "${engine.javaClass.simpleName} failed for $lang, trying next engine", e)
                lastError = e
            }
        }
        throw IllegalStateException("FallbackTtsEngine: no engine could synthesize $lang", lastError)
    }

    private fun synthesizeResampled(
        engine: TtsEngine,
        lang: Lang,
        segment: TtsSegment,
        onChunk: (FloatArray) -> Unit,
    ) {
        // Created lazily on the first chunk so engine.sampleRate reflects the engine's actual
        // native rate (only known for certain once it has finished lazy-initializing).
        var resampler: Resampler? = null
        engine.synthesize(lang, segment) { chunk ->
            val r = resampler ?: Resampler(engine.sampleRate, TARGET_SAMPLE_RATE).also { resampler = it }
            onChunk(r.process(chunk))
        }
    }

    override fun close() {
        mio.close()
        vits.close()
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 24000
    }
}
