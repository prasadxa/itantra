package org.itantra.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.io.File
import org.itantra.core.ModelPaths
import org.itantra.core.SttEngine
import org.itantra.core.TextPipeline
import org.itantra.core.Transport
import org.itantra.stt.MicCapture
import org.itantra.stt.SherpaSttEngine
import org.itantra.text.IndicTextPipeline
import org.itantra.transport.TransportManager
import org.itantra.tts.engine.FallbackTtsEngine
import org.itantra.tts.output.SpeechOutput

/**
 * The ONLY file that constructs concrete engines from :stt, :tts, :text, :transport.
 * Every engine is allowed to fail to load (missing model files) without crashing the app:
 * failures are captured in [EngineStatus] so the UI can show what is unavailable while the
 * rest of the app (e.g. typed-text chat) keeps working.
 */
object EngineFactory {

    fun create(context: Context, deviceId: String, deviceName: String, scope: CoroutineScope): EngineBundle {
        val root = File(context.getExternalFilesDir(null), "models")
        root.mkdirs()
        val paths = ModelPaths(root)
        val profile = ProfileManager.resolve(context)
        val numThreads = if (profile == Profile.LITE) 2 else 4

        var sttError: String? = null
        val sttLoadStart = System.currentTimeMillis()
        val stt: SttEngine? = try {
            SherpaSttEngine(paths, numThreads = numThreads)
        } catch (t: Throwable) {
            sttError = t.message ?: t.toString(); null
        }
        val sttLoadMs = System.currentTimeMillis() - sttLoadStart

        var ttsError: String? = null
        val ttsLoadStart = System.currentTimeMillis()
        // Idle-unload timeout for FallbackTtsEngine's per-sub-engine (Mio / VITS) unload timer:
        // 60s on LITE, 5 min on FULL (see docs/design.md "Profiles").
        val idleUnloadMs = if (profile == Profile.LITE) 60_000L else 300_000L
        val ttsEngine = try {
            // preferFast (VITS-first where it has a voice) already matches both profiles; LITE
            // additionally halves numThreads and prefers the q4/f16 model files (see
            // MioTtsEngine/VitsTtsEngine). warmUp() only initializes the engine actually needed for
            // the user's currently-selected language (AppRepository.language, default HI) instead
            // of unconditionally warming both Mio and VITS — see docs/design.md "lazy, per-need TTS".
            FallbackTtsEngine(context, paths, numThreads = numThreads, preferFast = true, idleUnloadMs = idleUnloadMs)
                // LITE keeps idle RAM low: nothing is preloaded; the first message in a language
                // pays the load once, and the idle unload returns the memory afterwards.
                .also { if (profile == Profile.FULL) it.warmUp(listOf(AppRepository.language.value)) }
        } catch (t: Throwable) {
            ttsError = t.message ?: t.toString(); null
        }
        val speechOutput: SpeechOutputPort? = ttsEngine?.let { engine ->
            try {
                SpeechOutputAdapter(SpeechOutput(context, engine))
            } catch (t: Throwable) {
                ttsError = t.message ?: t.toString(); null
            }
        }
        val ttsLoadMs = System.currentTimeMillis() - ttsLoadStart

        var textError: String? = null
        val textLoadStart = System.currentTimeMillis()
        val textPipeline: TextPipeline? = try {
            IndicTextPipeline()
        } catch (t: Throwable) {
            textError = t.message ?: t.toString(); null
        }
        val textLoadMs = System.currentTimeMillis() - textLoadStart

        var transportError: String? = null
        val transportLoadStart = System.currentTimeMillis()
        val transport: Transport? = try {
            TransportManager(context, deviceId, deviceName, scope)
        } catch (t: Throwable) {
            transportError = t.message ?: t.toString(); null
        }
        val transportLoadMs = System.currentTimeMillis() - transportLoadStart

        val micCapture: MicCapture? = try {
            MicCapture(context)
        } catch (t: Throwable) {
            null
        }

        val status = EngineStatus(
            sttReady = stt != null, sttError = sttError,
            ttsReady = speechOutput != null, ttsError = ttsError,
            textReady = textPipeline != null, textError = textError,
            transportReady = transport != null, transportError = transportError,
        )

        if (Metrics.isDebuggable(context)) {
            Metrics.log(
                "event" to "engine_load",
                "profile" to profile.name, "numThreads" to numThreads,
                "sttReady" to (stt != null), "sttLoadMs" to sttLoadMs, "sttError" to sttError,
                "ttsReady" to (speechOutput != null), "ttsLoadMs" to ttsLoadMs, "ttsError" to ttsError,
                "textReady" to (textPipeline != null), "textLoadMs" to textLoadMs, "textError" to textError,
                "transportReady" to (transport != null), "transportLoadMs" to transportLoadMs, "transportError" to transportError,
                "pssKb" to android.os.Debug.getPss(),
            )
        }

        return EngineBundle(
            paths = paths,
            profile = profile,
            stt = stt,
            ttsEngine = ttsEngine,
            speechOutput = speechOutput,
            textPipeline = textPipeline,
            transport = transport,
            micCapture = micCapture,
            status = status,
        )
    }
}

data class EngineStatus(
    val sttReady: Boolean,
    val sttError: String?,
    val ttsReady: Boolean,
    val ttsError: String?,
    val textReady: Boolean,
    val textError: String?,
    val transportReady: Boolean,
    val transportError: String?,
)

class EngineBundle(
    val paths: ModelPaths,
    val profile: Profile,
    val stt: SttEngine?,
    /** Raw :tts engine (Mio+VITS fallback), kept alongside [speechOutput] for debug tooling. */
    val ttsEngine: org.itantra.core.TtsEngine?,
    val speechOutput: SpeechOutputPort?,
    val textPipeline: TextPipeline?,
    val transport: Transport?,
    val micCapture: MicCapture?,
    val status: EngineStatus,
)

/** Adapts the concrete :tts `SpeechOutput` to the small [SpeechOutputPort] contract used by [Orchestrator]. */
private class SpeechOutputAdapter(private val delegate: SpeechOutput) : SpeechOutputPort {
    override fun enqueue(
        id: String,
        plan: org.itantra.core.SynthesisPlan,
        phoneMode: Boolean,
        onPlayStarted: (id: String, epochMs: Long) -> Unit,
    ) = delegate.enqueue(id, plan, phoneMode, onPlayStarted)

    override fun stopNormal() = delegate.stopNormal()
    override fun close() = delegate.close()
    override val isPlaying: kotlinx.coroutines.flow.StateFlow<Boolean> get() = delegate.isPlaying
    override fun replay(id: String, onPlayStarted: (id: String, epochMs: Long) -> Unit) = delegate.replay(id, onPlayStarted)
    override fun durationSeconds(id: String): Float? = delegate.durationSeconds(id)
    override var onVoiceNoteStored: ((id: String, durationSeconds: Float) -> Unit)?
        get() = delegate.onVoiceNoteStored
        set(value) { delegate.onVoiceNoteStored = value }
}
