package org.itantra.transport

import org.itantra.core.Lang
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Per-script static-dictionary text compression for [BinaryFrameCodec]'s "Text" field: raw DEFLATE
 * (`nowrap = true`, no zlib header/trailer) primed with a small preset dictionary of the most
 * common words for [Lang] (built by `transport/tools/build_dicts.py` from FLEURS transcripts +
 * emergency vocabulary into `src/main/resources/dict/<lang>.dict`, see transport/README.md).
 *
 * A preset dictionary must be set identically before the first `deflate()`/`inflate()` call on
 * both ends (there is no in-band zlib header to negotiate it, since we skip that header to save
 * bytes) — [dictFor] loads and caches the exact same resource both sides ship.
 */
object TextDictionaryCodec {
    const val SCHEME_RAW = 0
    const val SCHEME_DEFLATE_DICT = 1

    private val dictCache = ConcurrentHashMap<Lang, ByteArray?>()

    private fun dictFor(lang: Lang): ByteArray? = dictCache.getOrPut(lang) {
        javaClass.classLoader
            ?.getResourceAsStream("dict/${lang.code}.dict")
            ?.use { it.readBytes() }
    }

    /** Compresses [text] for [lang] if a dictionary is available and it actually shrinks the payload. */
    fun compress(text: String, lang: Lang): Pair<Int, ByteArray> {
        val raw = text.toByteArray(StandardCharsets.UTF_8)
        val dict = dictFor(lang) ?: return SCHEME_RAW to raw
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            deflater.setDictionary(dict)
            deflater.setInput(raw)
            deflater.finish()
            val buf = ByteArray(maxOf(64, raw.size + 32))
            val out = ByteArrayOutputStream(buf.size)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            val compressed = out.toByteArray()
            return if (compressed.size < raw.size) SCHEME_DEFLATE_DICT to compressed else SCHEME_RAW to raw
        } finally {
            deflater.end()
        }
    }

    fun decompress(scheme: Int, bytes: ByteArray, lang: Lang): String {
        if (scheme == SCHEME_RAW) return String(bytes, StandardCharsets.UTF_8)
        require(scheme == SCHEME_DEFLATE_DICT) { "unknown text compression scheme: $scheme" }
        val dict = dictFor(lang)
            ?: throw IllegalStateException("no dictionary shipped for $lang; cannot decompress text")
        val inflater = Inflater(true)
        try {
            // Raw (nowrap) deflate streams carry no FDICT flag, so the dictionary must be primed
            // up front rather than reactively on Inflater.needsDictionary().
            inflater.setDictionary(dict)
            inflater.setInput(bytes)
            val buf = ByteArray(512)
            val out = ByteArrayOutputStream(bytes.size * 3 + 32)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    throw IllegalStateException("corrupt dictionary-compressed text", e)
                }
                if (n == 0 && inflater.needsInput()) break
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), StandardCharsets.UTF_8)
        } finally {
            inflater.end()
        }
    }
}
