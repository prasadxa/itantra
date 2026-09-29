package org.itantra.tts.engine

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    /**
     * Idle timeout before an unused sub-engine (Mio's LLM+MioCodec, or VITS) is unloaded to free
     * RAM - see docs/design.md "Profiles" (60s on `Profile.LITE`, 5 min on `Profile.FULL`; both
     * wired from `EngineFactory`). A later [synthesize] call for that engine transparently reloads
     * it (see [MioTtsEngine.unload] / `VitsTtsEngine.unload`), at the cost of that one message's
     * latency - the same trade-off as never having warmed it up in the first place.
     */
    private val idleUnloadMs: Long = 300_000L,
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

    @Volatile private var mioLastUsedMs: Long = 0L
    @Volatile private var vitsLastUsedMs: Long = 0L

    // Single daemon thread; wakes at most every ~idleUnloadMs/4 (clamped) just to compare two
    // timestamps - negligible CPU, not "spinning" (see docs/design.md "true idle CPU").
    private val idleExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tts-idle-unload").apply { isDaemon = true }
    }
    private val idleCheckMs = (idleUnloadMs / 4).coerceIn(5_000L, 30_000L)
    private val idleFuture = idleExecutor.scheduleWithFixedDelay(
        { runCatching { checkIdle() }.onFailure { Log.w(TAG, "idle-unload check failed", it) } },
        idleCheckMs, idleCheckMs, TimeUnit.MILLISECONDS,
    )

    private fun checkIdle() {
        val now = System.currentTimeMillis()
        val mioAt = mioLastUsedMs
        if (mioAt != 0L && now - mioAt >= idleUnloadMs) {
            mio.unload()
            mioLastUsedMs = 0L
        }
        val vitsAt = vitsLastUsedMs
        if (vitsAt != 0L && now - vitsAt >= idleUnloadMs) {
            vits.unload()
            vitsLastUsedMs = 0L
        }
    }

    private fun markUsed(engine: TtsEngine) {
        val now = System.currentTimeMillis()
        when (engine) {
            mio -> mioLastUsedMs = now
            vits -> vitsLastUsedMs = now
        }
    }

    /**
     * Both engines load their weights lazily on first use (~1 GB for Mio), which would otherwise
     * land on the first received message. Warms only the engine(s) actually needed for [langs]
     * (typically just the user's currently-selected language - the peer's language isn't known
     * this early in the connection) instead of unconditionally warming both Mio and VITS regardless
     * of which languages are ever used - see docs/design.md "lazy, per-need TTS": don't load Mio
     * unless a hi/gu/or/en message arrives.
     */
    fun warmUp(langs: List<Lang> = listOf(Lang.HI)) {
        Thread({
            val order = if (preferFast) listOf(vits, mio) else listOf(mio, vits)
            val toWarm = linkedMapOf<TtsEngine, Lang>()
            for (lang in langs.distinct()) {
                val engine = order.firstOrNull { it.supports(lang) } ?: continue
                toWarm.putIfAbsent(engine, lang)
            }
            for ((engine, lang) in toWarm) {
                markUsed(engine)
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
                markUsed(engine)
                synthesizeResampled(engine, lang, segment, onChunk)
                markUsed(engine)
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
        idleFuture.cancel(false)
        idleExecutor.shutdownNow()
        mio.close()
        vits.close()
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 24000
    }
}
