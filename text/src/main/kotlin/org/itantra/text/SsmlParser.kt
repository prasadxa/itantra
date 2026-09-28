package org.itantra.text

import org.itantra.core.Emotion
import org.itantra.core.Lang
import org.itantra.core.TtsSegment

/**
 * Tolerant hand-written parser for the SSML subset in docs/design.md: `<speak>`, `<break
 * time="500ms|1s" strength=...>`, `<prosody rate|pitch|volume>` (nested prosody composes),
 * `<emphasis level>`, `<say-as interpret-as=...>`, `<s>`, `<p>`, and the custom `<emotion
 * name>`. No XML library is used (hand-written tag/text scanner). Malformed input never
 * throws: any failure falls back to stripping tags and treating the rest as plain text.
 */
class SsmlParser(private val normalizer: IndicNormalizer = IndicNormalizer()) {

    fun parse(ssml: String, lang: Lang): List<TtsSegment> = try {
        val segs = Parser(lang, normalizer).run(ssml)
        segs.ifEmpty { fallbackPlain(ssml, lang) }
    } catch (e: Exception) {
        fallbackPlain(ssml, lang)
    }

    private fun fallbackPlain(ssml: String, lang: Lang): List<TtsSegment> {
        val stripped = unescapeXml(TAG_RE.replace(ssml, ""))
        return TextSegmenter.segmentPlain(stripped, lang, normalizer)
    }

    // ---- parse session (fresh mutable state per call) ----

    private class Parser(private val lang: Lang, private val normalizer: IndicNormalizer) {
        private data class Frame(
            val name: String,
            val rate: Float = 1f,
            val pitch: Float = 0f,
            val volume: Float = 0f,
            val emotion: Emotion = Emotion.NEUTRAL,
            val special: String? = null, // "emphasis" | "sayas:<kind>"
        )

        private val stack = ArrayDeque<Frame>().apply { addLast(Frame("speak")) }
        private val segments = mutableListOf<TtsSegment>()
        private var pendingPauseMs = 0
        private var forceBoundary = false

        fun run(ssml: String): List<TtsSegment> {
            for (tok in tokenize(ssml)) {
                when (tok) {
                    is Token.Text -> handleText(tok.text)
                    is Token.Tag -> handleTag(tok)
                }
            }
            return segments
        }

        private fun handleText(raw: String) {
            val text = unescapeXml(raw)
            if (text.isBlank()) return
            val top = stack.last()
            when {
                top.special == "emphasis" -> {
                    val stress = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    emit(normalizer.normalize(text, lang), top, stress = stress, volumeBump = 2f)
                }
                top.special?.startsWith("sayas:") == true -> {
                    val kind = top.special.removePrefix("sayas:")
                    val converted = try {
                        convertSayAs(kind, text, lang, normalizer)
                    } catch (e: Exception) {
                        normalizer.normalize(text, lang)
                    }
                    emit(converted, top)
                }
                else -> {
                    for (chunk in TextSegmenter.splitSentences(text)) {
                        if (chunk.isBlank()) continue
                        emit(normalizer.normalize(chunk, lang), top)
                    }
                }
            }
        }

        private fun handleTag(tok: Token.Tag) {
            if (tok.closing) {
                val idx = stack.indexOfLast { it.name == tok.name }
                if (idx > 0) while (stack.size > idx) stack.removeLast()
                return
            }
            val top = stack.last()
            when (tok.name) {
                "break" -> pendingPauseMs += parseBreakMs(tok.attrs)
                "p" -> {
                    if (segments.isNotEmpty()) pendingPauseMs += 300
                    if (!tok.selfClosing) stack.addLast(top.copy(name = "p"))
                }
                "s" -> {
                    forceBoundary = true
                    if (!tok.selfClosing) stack.addLast(top.copy(name = "s"))
                }
                "prosody" -> {
                    val rate = top.rate * (tok.attrs["rate"]?.let(::parseRate) ?: 1f)
                    val pitch = top.pitch + (tok.attrs["pitch"]?.let(::parsePitch) ?: 0f)
                    val volume = top.volume + (tok.attrs["volume"]?.let(::parseVolume) ?: 0f)
                    if (!tok.selfClosing) stack.addLast(top.copy(name = "prosody", rate = rate, pitch = pitch, volume = volume))
                }
                "emphasis" -> if (!tok.selfClosing) stack.addLast(top.copy(name = "emphasis", special = "emphasis"))
                "say-as" -> {
                    val kind = tok.attrs["interpret-as"]?.lowercase() ?: "cardinal"
                    if (!tok.selfClosing) stack.addLast(top.copy(name = "say-as", special = "sayas:$kind"))
                }
                "emotion" -> {
                    val em = mapEmotionName(tok.attrs["name"]?.lowercase()) ?: top.emotion
                    if (!tok.selfClosing) stack.addLast(top.copy(name = "emotion", emotion = em))
                }
                "speak" -> if (!tok.selfClosing) stack.addLast(top.copy(name = "speak"))
                else -> if (!tok.selfClosing) stack.addLast(top.copy(name = tok.name))
            }
        }

        private fun emit(text: String, frame: Frame, stress: List<String> = emptyList(), volumeBump: Float = 0f) {
            if (text.isBlank()) return
            val last = segments.lastOrNull()
            val mustStartNew = forceBoundary || pendingPauseMs > 0 || last == null || TextSegmenter.endsWithTerminal(last.text)
            if (!mustStartNew && last != null) {
                segments[segments.lastIndex] = last.copy(
                    text = "${last.text} $text",
                    stressWords = (last.stressWords + stress).distinct(),
                    volumeDb = last.volumeDb + volumeBump,
                )
            } else {
                segments += TtsSegment(
                    text = text,
                    emotion = frame.emotion,
                    rate = frame.rate,
                    volumeDb = frame.volume + volumeBump,
                    pitchSemitones = frame.pitch,
                    pauseBeforeMs = pendingPauseMs,
                    stressWords = stress,
                )
                pendingPauseMs = 0
                forceBoundary = false
            }
        }
    }

    private sealed interface Token {
        data class Tag(val name: String, val closing: Boolean, val selfClosing: Boolean, val attrs: Map<String, String>) : Token
        data class Text(val text: String) : Token
    }

    companion object {
        private val TAG_TOKEN_RE =
            Regex("<(/)?([A-Za-z][\\w-]*)((?:\\s+[\\w-]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*)\\s*(/?)>")
        private val ATTR_RE = Regex("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
        private val TAG_RE = Regex("<[^>]*>")

        private fun tokenize(s: String): List<Token> {
            val tokens = mutableListOf<Token>()
            var last = 0
            for (m in TAG_TOKEN_RE.findAll(s)) {
                if (m.range.first > last) tokens += Token.Text(s.substring(last, m.range.first))
                val closing = m.groupValues[1] == "/"
                val name = m.groupValues[2].lowercase()
                val selfClosing = m.groupValues[4] == "/"
                val attrs = ATTR_RE.findAll(m.groupValues[3]).associate { am ->
                    val value = am.groupValues[2].ifEmpty { am.groupValues[3] }
                    am.groupValues[1].lowercase() to value
                }
                tokens += Token.Tag(name, closing, selfClosing, attrs)
                last = m.range.last + 1
            }
            if (last < s.length) tokens += Token.Text(s.substring(last))
            return tokens
        }

        private fun parseRate(v: String): Float = when (v.lowercase()) {
            "x-slow" -> 0.6f
            "slow" -> 0.8f
            "medium" -> 1f
            "fast" -> 1.2f
            "x-fast" -> 1.4f
            else -> {
                val low = v.lowercase().trim()
                if (low.endsWith("%")) (low.dropLast(1).toFloatOrNull() ?: 100f) / 100f
                else low.toFloatOrNull() ?: 1f
            }
        }

        private fun parsePitch(v: String): Float {
            val low = v.lowercase().trim()
            return when (low) {
                "x-low" -> -6f
                "low" -> -3f
                "medium" -> 0f
                "high" -> 3f
                "x-high" -> 6f
                else -> when {
                    low.endsWith("st") -> low.removeSuffix("st").toFloatOrNull() ?: 0f
                    low.endsWith("%") -> 0f
                    else -> low.toFloatOrNull() ?: 0f
                }
            }
        }

        private fun parseVolume(v: String): Float {
            val low = v.lowercase().trim()
            return when (low) {
                "silent" -> -60f
                "x-soft" -> -10f
                "soft" -> -5f
                "medium" -> 0f
                "loud" -> 5f
                "x-loud" -> 10f
                else -> when {
                    low.endsWith("db") -> low.removeSuffix("db").toFloatOrNull() ?: 0f
                    low.endsWith("%") -> 0f
                    else -> low.toFloatOrNull() ?: 0f
                }
            }
        }

        private fun parseBreakMs(attrs: Map<String, String>): Int {
            attrs["time"]?.let { raw ->
                val low = raw.lowercase().trim()
                return when {
                    low.endsWith("ms") -> low.removeSuffix("ms").toFloatOrNull()?.toInt() ?: 0
                    low.endsWith("s") -> ((low.removeSuffix("s").toFloatOrNull() ?: 0f) * 1000).toInt()
                    else -> low.toFloatOrNull()?.toInt() ?: 0
                }
            }
            return when (attrs["strength"]?.lowercase()) {
                "x-strong" -> 1000
                "strong" -> 750
                "medium" -> 500
                "weak" -> 250
                "x-weak", "none" -> 0
                else -> 500
            }
        }

        private fun mapEmotionName(name: String?): Emotion? = when (name) {
            "happy" -> Emotion.HAPPY
            "sad" -> Emotion.SAD
            "angry" -> Emotion.ANGRY
            "fear" -> Emotion.FEAR
            "surprise" -> Emotion.SURPRISE
            "disgust" -> Emotion.DISGUST
            "urgent" -> Emotion.URGENT
            "neutral" -> Emotion.NEUTRAL
            else -> null
        }

        private fun convertSayAs(kind: String, raw: String, lang: Lang, normalizer: IndicNormalizer): String {
            val text = raw.trim()
            return when (kind) {
                "cardinal", "number" -> normalizer.spellCardinalText(text, lang)
                "digits" -> normalizer.spellDigitsText(text, lang)
                "characters", "spell-out" -> normalizer.spellCharactersText(text, lang)
                "date" -> normalizer.spellDateText(text, lang)
                "time" -> normalizer.spellTimeText(text, lang)
                "telephone" -> normalizer.spellTelephoneText(text, lang)
                else -> normalizer.normalize(text, lang)
            }
        }

        private fun unescapeXml(s: String): String {
            var out = s
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
            out = Regex("&#x([0-9a-fA-F]+);").replace(out) { mr ->
                runCatching { mr.groupValues[1].toInt(16).toChar().toString() }.getOrDefault(mr.value)
            }
            out = Regex("&#([0-9]+);").replace(out) { mr ->
                runCatching { mr.groupValues[1].toInt().toChar().toString() }.getOrDefault(mr.value)
            }
            return out.replace("&amp;", "&")
        }
    }
}
