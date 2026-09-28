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

        var sttError: String? = null
        val stt: SttEngine? = try {
            SherpaSttEngine(paths)
        } catch (t: Throwable) {
            sttError = t.message ?: t.toString(); null
        }

        var ttsError: String? = null
        val ttsEngine = try {
            FallbackTtsEngine(context, paths)
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

        var textError: String? = null
        val textPipeline: TextPipeline? = try {
            IndicTextPipeline()
        } catch (t: Throwable) {
            textError = t.message ?: t.toString(); null
        }

        var transportError: String? = null
        val transport: Transport? = try {
            TransportManager(context, deviceId, deviceName, scope)
        } catch (t: Throwable) {
            transportError = t.message ?: t.toString(); null
        }

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

        return EngineBundle(
            paths = paths,
            stt = stt,
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
    val stt: SttEngine?,
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
}
