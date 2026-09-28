package org.itantra.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Output of the STT module: one sentence, cut by VAD at a pause. Times are epoch ms. */
data class RecognizedSentence(
    /** Utterance id, shared with the [SttEngine.partialListener] calls for the same utterance. */
    val id: String,
    val text: String,
    val lang: Lang,
    val speechStartAt: Long,
    val speechEndAt: Long,
    val sttDoneAt: Long,
    val audioSeconds: Float,
    val decodeMs: Long,
)

/** VAD-segmented offline STT (sherpa-onnx Silero VAD + SraVaani). Not thread-safe; call from one thread. */
interface SttEngine : AutoCloseable {
    var language: Lang
    /** Domain words to boost (alerts, place names). Applied to subsequent sentences. */
    fun setHotwords(words: List<String>)
    /** Feed 16 kHz mono PCM in [-1, 1]. Invokes [listener] when a sentence ends. */
    fun accept(samples: FloatArray)
    /** Force-finalise the current utterance (PTT released). */
    fun flush()
    fun reset()
    val isSpeechActive: Boolean
    var listener: ((RecognizedSentence) -> Unit)?
    /** Live in-progress transcript for the utterance currently being spoken, fired periodically. */
    var partialListener: ((id: String, text: String) -> Unit)?
}

/** One piece of speech after SSML / style processing. */
data class TtsSegment(
    val text: String,
    val emotion: Emotion = Emotion.NEUTRAL,
    /** 1.0 = normal; 1.2 = 20% faster. */
    val rate: Float = 1f,
    val volumeDb: Float = 0f,
    val pitchSemitones: Float = 0f,
    val pauseBeforeMs: Int = 0,
    /** Words to stress (Indic-Mio `*word*`). */
    val stressWords: List<String> = emptyList(),
)

data class SynthesisPlan(val lang: Lang, val priority: Priority, val segments: List<TtsSegment>)

/** Text → plan: Indic number/date normalisation, SSML subset parsing, automatic style selection. */
interface TextPipeline {
    fun plan(message: VoiceMessage): SynthesisPlan
}

interface TtsEngine : AutoCloseable {
    val sampleRate: Int
    fun supports(lang: Lang): Boolean
    /** Blocking; streams PCM float chunks in [-1, 1] as they are produced. */
    fun synthesize(lang: Lang, segment: TtsSegment, onChunk: (FloatArray) -> Unit)
}

enum class LinkKind { WIFI, BLE }

sealed interface LinkState {
    data object Idle : LinkState
    data object Searching : LinkState
    data class Connected(val kind: LinkKind, val peerName: String) : LinkState
    data class Failed(val reason: String) : LinkState
}

interface Transport : AutoCloseable {
    val kind: LinkKind
    val state: StateFlow<LinkState>
    val incoming: SharedFlow<Frame>
    /** Advertise + discover + connect to one peer running the app. */
    suspend fun start()
    suspend fun stop()
    /** Returns false if not connected or the write failed. */
    suspend fun send(frame: Frame): Boolean
}
