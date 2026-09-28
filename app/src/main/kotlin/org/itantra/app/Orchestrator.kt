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
    private val onMetrics: (MessageMetrics) -> Unit = {},
    private val onPeerTalking: (Boolean) -> Unit = {},
    private val onRtt: (rttMs: Long, offsetMs: Long) -> Unit = { _, _ -> },
    private val alertProvider: () -> Boolean = { false },
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

    init {
        stt?.listener = { sentence -> onRecognized(sentence) }
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

    private fun onRecognized(sentence: RecognizedSentence) {
        val now = clock()
        val id = UUID.randomUUID().toString()
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
            is Frame.Msg -> onMessageReceived(frame.message)
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
        if (plan != null && speechOutput != null) {
            speechOutput.enqueue(message.id, plan, mode == Mode.PHONE) { id, playedAt ->
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
