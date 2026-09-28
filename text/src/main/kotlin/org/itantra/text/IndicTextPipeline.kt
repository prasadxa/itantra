package org.itantra.text

import org.itantra.core.Emotion
import org.itantra.core.Priority
import org.itantra.core.SynthesisPlan
import org.itantra.core.TextPipeline
import org.itantra.core.TtsSegment
import org.itantra.core.VoiceMessage

/**
 * Default [TextPipeline]: SSML (when [VoiceMessage.ssml]) or plain text -> normalized,
 * sentence-split [TtsSegment]s with an automatically chosen [Emotion] style.
 *
 * Emotion precedence: explicit [VoiceMessage.emotion] always wins; otherwise a segment's own
 * SSML `<emotion>` is kept; otherwise ALERT priority or an [AlertKeywords] match makes the
 * segment URGENT (rate >= 1.1, +6 dB, per docs/design.md); otherwise NEUTRAL.
 */
class IndicTextPipeline : TextPipeline {
    private val normalizer = IndicNormalizer()
    private val ssmlParser = SsmlParser(normalizer)

    override fun plan(message: VoiceMessage): SynthesisPlan {
        val segments = if (message.ssml) {
            ssmlParser.parse(message.text, message.lang)
        } else {
            TextSegmenter.segmentPlain(message.text, message.lang, normalizer)
        }
        val isAlert = message.priority == Priority.ALERT || AlertKeywords.containsAlertKeyword(message.text, message.lang)
        val styled = segments.map { seg -> applyStyle(seg, message, isAlert) }
        return SynthesisPlan(message.lang, message.priority, styled)
    }

    private fun applyStyle(seg: TtsSegment, message: VoiceMessage, isAlert: Boolean): TtsSegment {
        val explicit = message.emotion
        return when {
            explicit != null -> urgentBoostIfNeeded(seg.copy(emotion = explicit), explicit)
            seg.emotion != Emotion.NEUTRAL -> seg // SSML already set a style for this segment
            isAlert -> urgentBoostIfNeeded(seg.copy(emotion = Emotion.URGENT), Emotion.URGENT)
            else -> seg
        }
    }

    private fun urgentBoostIfNeeded(seg: TtsSegment, emotion: Emotion): TtsSegment =
        if (emotion == Emotion.URGENT) {
            seg.copy(rate = maxOf(seg.rate, 1.1f), volumeDb = maxOf(seg.volumeDb, 6f))
        } else {
            seg
        }
}
