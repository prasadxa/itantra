package org.itantra.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Priority { NORMAL, ALERT }

/** Speaking style. Maps to Indic-Mio emotion tags / VITS style ids. URGENT = alert announcement. */
@Serializable
enum class Emotion { NEUTRAL, HAPPY, SAD, ANGRY, FEAR, SURPRISE, DISGUST, URGENT }

/**
 * One recognised sentence travelling between phones. All times are sender wall-clock epoch ms;
 * receiver corrects with the clock offset from [Frame.Ping]/[Frame.Pong].
 */
@Serializable
data class VoiceMessage(
    val id: String,
    val from: String,
    val lang: Lang,
    val text: String,
    val priority: Priority = Priority.NORMAL,
    /** null = let the receiver's style classifier decide. */
    val emotion: Emotion? = null,
    /** true when [text] is an SSML document (`<speak>…</speak>`). */
    val ssml: Boolean = false,
    val speechEndAt: Long = 0,
    val sttDoneAt: Long = 0,
    val sentAt: Long = 0,
)

/** Wire protocol. Encoded as JSON; framing is transport-specific (see transport module). */
@Serializable
sealed interface Frame {
    @Serializable @SerialName("hello")
    data class Hello(val deviceId: String, val name: String, val protocol: Int = 1) : Frame

    @Serializable @SerialName("msg")
    data class Msg(val message: VoiceMessage) : Frame

    /** Receiver → sender once playback of [id] starts; enables end-to-end latency measurement. */
    @Serializable @SerialName("ack")
    data class Ack(val id: String, val receivedAt: Long, val playStartedAt: Long = 0) : Frame

    /** Walkie-talkie floor control: peer is (not) talking. */
    @Serializable @SerialName("ptt")
    data class Ptt(val talking: Boolean) : Frame

    @Serializable @SerialName("ping")
    data class Ping(val t0: Long) : Frame

    @Serializable @SerialName("pong")
    data class Pong(val t0: Long, val t1: Long) : Frame
}
