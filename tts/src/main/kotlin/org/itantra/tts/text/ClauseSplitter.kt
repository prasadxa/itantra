package org.itantra.tts.text

/**
 * Splits a sentence into smaller clauses so a non-streaming TTS engine (see [org.itantra.tts.engine.VitsTtsEngine])
 * can start playback on the first clause while later ones are still synthesizing, instead of
 * waiting for the whole sentence — sherpa-onnx's offline VITS renders (and only invokes its
 * callback for) one whole utterance per `generateWithConfigAndCallback` call, so measured
 * first-audio latency was the time to render the *entire* sentence (~1.1s), not just its start.
 * Splitting into clauses and calling the engine once per clause cuts that down to roughly the time
 * to render just the first clause.
 *
 * Splits at clause-boundary punctuation (comma, semicolon, colon, and the Devanagari/Indic danda
 * `।`/`॥` some scripts use for a full stop) first; any piece still longer than [maxChars] is
 * further split at the nearest preceding whitespace so words are never cut mid-way. Adjacent pieces
 * shorter than [minChars] are merged into their neighbour so a lone short word doesn't become its
 * own (prosodically odd) utterance.
 */
object ClauseSplitter {
    private val CLAUSE_BOUNDARY = Regex("(?<=[,;:।॥])\\s+")

    fun split(text: String, maxChars: Int = 60, minChars: Int = 12): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChars) return listOf(trimmed)

        val clauses = trimmed.split(CLAUSE_BOUNDARY).map { it.trim() }.filter { it.isNotEmpty() }
        val sized = clauses.flatMap { splitToMaxChars(it, maxChars) }
        return mergeShortPieces(sized, minChars)
    }

    private fun splitToMaxChars(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= maxChars) {
                out += text.substring(start).trim()
                break
            }
            var end = start + maxChars
            val spaceIdx = text.lastIndexOf(' ', end)
            if (spaceIdx > start) end = spaceIdx else end = start + maxChars
            out += text.substring(start, end).trim()
            start = end
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun mergeShortPieces(pieces: List<String>, minChars: Int): List<String> {
        if (pieces.size <= 1) return pieces
        val out = mutableListOf<String>()
        for (piece in pieces) {
            val lastIdx = out.size - 1
            if (lastIdx >= 0 && out[lastIdx].length < minChars) {
                out[lastIdx] = "${out[lastIdx]} $piece"
            } else {
                out += piece
            }
        }
        return out
    }
}
