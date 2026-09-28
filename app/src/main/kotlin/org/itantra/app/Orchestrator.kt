package org.itantra.app

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.itantra.core.Frame
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.RecognizedSentence
import org.itantra.core.SttEngine
import org.itantra.core.TextPipeline
import org.itantra.core.Transport
import org.itantra.core.VoiceMessage

enum class Mode { PTT, PHONE }
enum class Direction { SENT, RECEIVED }

data class LogEntry(
    val id: String,
    val direction: Direction,
    val text: String,
    val lang: Lang,
    val alert: Boolean,
    val ssml: Boolean,
    val timestamp: Long,
)

/** Live in-progress transcript, shown as a shimmering bubble until the matching [LogEntry] arrives. */
data class PartialEntry(
    val id: String,
    val direction: Direction,
    val text: String,
    val lang: Lang,
    val timestamp: Long,
)

data class MessageMetrics(
    val id: String,
    val direction: Direction,
    val lang: Lang,
    val priority: Priority,
    val sttLatencyMs: Long? = null,
    val rtf: Float? = null,
    val networkMs: Long? = null,
    val ttsStartMs: Long? = null,
    val endToEndMs: Long? = null,
    val timestamp: Long,
)

/**
 * Small port matching the shape of :tts `SpeechOutput` so [Orchestrator] can be unit-tested
 * against a fake without depending on the concrete Android class.
 */
interface SpeechOutputPort {
    fun enqueue(
        id: String,
        plan: org.itantra.core.SynthesisPlan,
        phoneMode: Boolean,
        onPlayStarted: (id: String, epochMs: Long) -> Unit,
    )
    fun stopNormal()
    fun close()
    /** True while any audio is being written to a track (NORMAL or ALERT). Used to half-duplex-gate
     * the mic so the device never transcribes its own TTS output. */
    val isPlaying: kotlinx.coroutines.flow.StateFlow<Boolean>
}

/**
 * Plain, Android-free orchestration of the talk flow: mic samples -> STT -> [VoiceMessage] -> transport,
 * and incoming [Frame]s -> TTS playback + acks + floor control + clock sync. Unit-testable with fakes of
 * the :core interfaces (see app/src/test).
 */
class Orchestrator(
    private val deviceId: String,
    private val stt: SttEngine?,
    private val textPipeline: TextPipeline?,
    private val speechOutput: SpeechOutputPort?,
    private val transport: Transport,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onLog: (LogEntry) -> Unit = {},
    private val onPartial: (PartialEntry) -> Unit = {},
    private val onMetrics: (MessageMetrics) -> Unit = {},
    private val onPeerTalking: (Boolean) -> Unit = {},
    /** Fired with the message id when TTS playback of a received message starts (for a "speaking…" UI badge). */
    private val onSpeaking: (id: String) -> Unit = {},
    private val onRtt: (rttMs: Long, offsetMs: Long) -> Unit = { _, _ -> },
    private val alertProvider: () -> Boolean = { false },
    /**
     * Whether the TTS engine can actually synthesize [Lang] right now (voice/model present).
     * [SpeechOutputPort.enqueue] has no failure callback - only [onPlayStarted] on success - so
     * without this check a message in an unsupported language would be silently enqueued, never
     * play, and never get Acked, leaving the sender waiting forever. Defaults to always-true so
     * existing fakes/tests (which don't model per-language support) are unaffected.
     */
    private val ttsSupports: (Lang) -> Boolean = { true },
) {
    var mode: Mode = Mode.PTT

    var language: Lang = Lang.HI
        set(value) {
            field = value
            stt?.language = value
        }

    @Volatile
    var clockOffsetMs: Long = 0L
        private set

    @Volatile
    var peerHoldsFloor: Boolean = false
        private set

    private val pendingSent = mutableMapOf<String, Pair<MessageMetrics, Long>>() // metrics, speechEndAt
    private var pingJob: Job? = null
    private var incomingJob: Job? = null

    @Volatile
    private var lastPartialSentAt: Long = 0L

    init {
        stt?.listener = { sentence -> onRecognized(sentence) }
        stt?.partialListener = { id, text -> onLocalPartial(id, text) }
    }

    fun start() {
        incomingJob = scope.launch {
            transport.incoming.collect { frame -> handleFrame(frame) }
        }
        pingJob = scope.launch {
            while (isActive) {
                delay(5000)
                transport.send(Frame.Ping(clock()))
            }
        }
    }

    fun stop() {
        incomingJob?.cancel()
        pingJob?.cancel()
    }

    /** Called with 16 kHz mono PCM captured from the mic while it is running. */
    fun onAudioSamples(samples: FloatArray) {
        if (mode == Mode.PTT && peerHoldsFloor) return
        stt?.accept(samples)
    }

    fun onPttPressed() {
        scope.launch { transport.send(Frame.Ptt(true)) }
    }

    fun onPttReleased() {
        stt?.flush()
        scope.launch { transport.send(Frame.Ptt(false)) }
    }

    /** Typed text (or SSML) message, bypassing STT entirely — also used to test TTS without speaking. */
    fun sendText(text: String, lang: Lang, ssml: Boolean) {
        val now = clock()
        val id = UUID.randomUUID().toString()
        val message = VoiceMessage(
            id = id, from = deviceId, lang = lang, text = text,
            priority = priority(), emotion = null, ssml = ssml,
            speechEndAt = now, sttDoneAt = now, sentAt = now,
        )
        recordSentAndSend(message, sttLatencyMs = null, rtf = null)
    }

    private fun priority(): Priority = if (alertProvider()) Priority.ALERT else Priority.NORMAL

    /** Fired by [SttEngine.partialListener] every ~500ms while the local mic is mid-utterance. */
    private fun onLocalPartial(id: String, text: String) {
        onPartial(PartialEntry(id, Direction.SENT, text, language, clock()))
        val now = clock()
        if (now - lastPartialSentAt >= 500) {
            lastPartialSentAt = now
            scope.launch { transport.send(Frame.Partial(id, deviceId, language, text)) }
        }
    }

    private fun onRecognized(sentence: RecognizedSentence) {
        val now = clock()
        val id = sentence.id
        val message = VoiceMessage(
            id = id, from = deviceId, lang = sentence.lang, text = sentence.text,
            priority = priority(), emotion = null, ssml = false,
            speechEndAt = sentence.speechEndAt, sttDoneAt = sentence.sttDoneAt, sentAt = now,
        )
        val sttLatency = sentence.sttDoneAt - sentence.speechEndAt
        val rtf = if (sentence.audioSeconds > 0f) sentence.decodeMs / (sentence.audioSeconds * 1000f) else null
        recordSentAndSend(message, sttLatency, rtf)
    }

    private fun recordSentAndSend(message: VoiceMessage, sttLatencyMs: Long?, rtf: Float?) {
        val metrics = MessageMetrics(
            id = message.id, direction = Direction.SENT, lang = message.lang, priority = message.priority,
            sttLatencyMs = sttLatencyMs, rtf = rtf, timestamp = message.sentAt,
        )
        pendingSent[message.id] = metrics to message.speechEndAt
        onMetrics(metrics)
        onLog(LogEntry(message.id, Direction.SENT, message.text, message.lang, message.priority == Priority.ALERT, message.ssml, message.sentAt))
        scope.launch { transport.send(Frame.Msg(message)) }
    }

    private fun handleFrame(frame: Frame) {
        when (frame) {
            // Defence-in-depth loopback guard: a self-connected transport (bug, or a stray NSD
            // resolution) must never make us transcribe-and-reply to our own voice.
            is Frame.Msg -> if (frame.message.from != deviceId) onMessageReceived(frame.message)
            is Frame.Partial -> if (frame.from != deviceId) {
                onPartial(PartialEntry(frame.id, Direction.RECEIVED, frame.text, frame.lang, clock()))
            }
            is Frame.Ack -> onAckReceived(frame)
            is Frame.Ptt -> {
                peerHoldsFloor = frame.talking
                onPeerTalking(frame.talking)
            }
            is Frame.Ping -> scope.launch { transport.send(Frame.Pong(frame.t0, clock())) }
            is Frame.Pong -> onPongReceived(frame)
            is Frame.Hello -> Unit
        }
    }

    private fun onMessageReceived(message: VoiceMessage) {
        val receivedAt = clock()
        val networkMs = receivedAt - message.sentAt + clockOffsetMs
        onLog(LogEntry(message.id, Direction.RECEIVED, message.text, message.lang, message.priority == Priority.ALERT, message.ssml, receivedAt))

        val plan = textPipeline?.plan(message)
        if (plan != null && speechOutput != null && ttsSupports(message.lang)) {
            speechOutput.enqueue(message.id, plan, mode == Mode.PHONE) { id, playedAt ->
                onSpeaking(id)
                val ttsStartMs = playedAt - receivedAt
                val endToEndMs = playedAt - message.speechEndAt
                onMetrics(
                    MessageMetrics(
                        id = id, direction = Direction.RECEIVED, lang = message.lang, priority = message.priority,
                        networkMs = networkMs, ttsStartMs = ttsStartMs, endToEndMs = endToEndMs, timestamp = receivedAt,
                    ),
                )
                scope.launch { transport.send(Frame.Ack(id, receivedAt, playedAt)) }
            }
        } else {
            onMetrics(
                MessageMetrics(
                    id = message.id, direction = Direction.RECEIVED, lang = message.lang, priority = message.priority,
                    networkMs = networkMs, timestamp = receivedAt,
                ),
            )
            scope.launch { transport.send(Frame.Ack(message.id, receivedAt, 0)) }
        }
    }

    private fun onAckReceived(ack: Frame.Ack) {
        val (metrics, speechEndAt) = pendingSent.remove(ack.id) ?: return
        if (ack.playStartedAt <= 0) {
            onMetrics(metrics)
            return
        }
        val playedLocal = ack.playStartedAt - clockOffsetMs
        onMetrics(metrics.copy(endToEndMs = playedLocal - speechEndAt))
    }

    private fun onPongReceived(pong: Frame.Pong) {
        val t0 = pong.t0
        val t1 = pong.t1
        val t3 = clock()
        val offset = ((t1 - t0) + (t1 - t3)) / 2
        clockOffsetMs = offset
        onRtt(t3 - t0, offset)
    }
}
