package org.itantra.text

import org.itantra.core.Emotion
import org.itantra.core.Lang
import org.itantra.core.TtsSegment

/**
 * Splits text into sentence-sized chunks so TTS can start speaking before the whole message is
 * ready, and turns those chunks into normalized [TtsSegment]s. Shared by the plain-text path
 * and by [SsmlParser]'s malformed-input fallback.
 */
internal object TextSegmenter {
    private val TERMINALS = charArrayOf('।', '॥', '.', '?', '!')

    /** Split on sentence terminators (kept on the preceding chunk) and newlines (dropped). */
    fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        for (c in text) {
            when {
                c == '\n' -> {
                    if (buf.isNotBlank()) out += buf.toString().trim()
                    buf.clear()
                }
                c in TERMINALS -> {
                    buf.append(c)
                    out += buf.toString().trim()
                    buf.clear()
                }
                else -> buf.append(c)
            }
        }
        if (buf.isNotBlank()) out += buf.toString().trim()

        // Safety net: split pathologically long delimiter-free runs by word count too.
        val maxWords = 40
        return out.flatMap { chunk ->
            val words = chunk.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size <= maxWords) listOf(chunk)
            else words.chunked(maxWords).map { it.joinToString(" ") }
        }
    }

    /** Plain-text (non-SSML) path: split + normalize each chunk into a neutral [TtsSegment]. */
    fun segmentPlain(text: String, lang: Lang, normalizer: IndicNormalizer): List<TtsSegment> =
        splitSentences(text).filter { it.isNotBlank() }.map { chunk ->
            TtsSegment(text = normalizer.normalize(chunk, lang), emotion = Emotion.NEUTRAL)
        }

    fun endsWithTerminal(text: String): Boolean {
        val t = text.trimEnd()
        return t.isNotEmpty() && t.last() in TERMINALS
    }
}
