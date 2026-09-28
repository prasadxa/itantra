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
) : TtsEngine {
    private val appContext = context.applicationContext
    private val mio = MioTtsEngine(paths, numThreads)
    private val vits = VitsTtsEngine(paths, numThreads)

    override val sampleRate: Int = TARGET_SAMPLE_RATE

    override fun supports(lang: Lang): Boolean = mio.supports(lang) || vits.supports(lang)

    override fun synthesize(lang: Lang, segment: TtsSegment, onChunk: (FloatArray) -> Unit) {
        if (mio.supports(lang)) {
            try {
                synthesizeResampled(mio, lang, segment, onChunk)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Mio synthesis failed for $lang, falling back to VITS", e)
            }
        }
        if (vits.supports(lang)) {
            synthesizeResampled(vits, lang, segment, onChunk)
            return
        }
        error("FallbackTtsEngine: no engine (Mio or VITS) supports $lang")
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
