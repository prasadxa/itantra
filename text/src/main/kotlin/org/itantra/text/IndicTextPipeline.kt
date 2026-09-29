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
        // Additive SOS-location hook: a message carrying a GPS fix gets one more spoken segment
        // describing it, e.g. "location 12.97 north, 77.59 east, accuracy 15 metres" - built by
        // LocationSpeller and run through the same IndicNormalizer as the message body, so its
        // embedded numbers are spelled out identically to any other number. Normalized directly
        // (not via TextSegmenter.segmentPlain) because that splits on '.', which would fragment
        // the decimal points in the coordinates themselves.
        val lat = message.lat
        val lon = message.lon
        val locationSegments = if (lat != null && lon != null) {
            val phrase = LocationSpeller.phrase(lat, lon, message.accuracyM, message.lang)
            listOf(TtsSegment(text = normalizer.normalize(phrase, message.lang), emotion = Emotion.NEUTRAL))
        } else {
            emptyList()
        }
        val isAlert = message.priority == Priority.ALERT || AlertKeywords.containsAlertKeyword(message.text, message.lang)
        val styled = (segments + locationSegments).map { seg -> applyStyle(seg, message, isAlert) }
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
