package org.itantra.tts.engine

import java.io.File
import org.itantra.core.Emotion
import org.itantra.core.Lang
import org.itantra.core.ModelPaths
import org.itantra.core.TtsEngine
import org.itantra.core.TtsSegment
import org.itantra.tts.mio.MioNative

/**
 * Indic-Mio (mio-tts-cpp, LLM-driven) [TtsEngine]. Supports whichever of the 10 [Lang]s has a
 * `paths.mioVoice(lang)` embedding file on disk — the model itself is language-agnostic (trained
 * multilingual); only the voice embedding is per-language.
 *
 * Not thread-safe (single native handle in [MioNative]); callers must serialize onto one thread,
 * as required by [TtsEngine].
 */
class MioTtsEngine(private val paths: ModelPaths, private val numThreads: Int) : TtsEngine {

    // Prefer the Q4_0 LLM (llama.cpp repacks Q4_0 at load into ARM dotprod/i8mm-optimised
    // layouts) when present — half the size and faster matmuls than q8_0, at a small quality
    // cost; see tools/tts/quantize_q4.sh. Falls back to q8_0 so devices/builds without the q4
    // file (or an older push) keep working unmodified. Filename fixed at "indic-mio-q4.gguf" —
    // the app's LITE profile expects exactly this name.
    private val q4Gguf = File(paths.mioDir, "indic-mio-q4.gguf")
    private val q8Gguf = File(paths.mioDir, "indic-mio-q8_0.gguf")
    private val lmGguf = if (q4Gguf.exists()) q4Gguf else q8Gguf
    private val codecGguf = File(paths.mioDir, "miocodec.gguf")
    private val wavlmGguf = File(paths.mioDir, "wavlm.gguf")

    private var initialized = false
    private var loadedVoiceLang: Lang? = null

    override val sampleRate: Int get() = if (MioNative.isInitialized) MioNative.sampleRate else 24000

    override fun supports(lang: Lang): Boolean =
        lmGguf.exists() && codecGguf.exists() && wavlmGguf.exists() && paths.mioVoice(lang).exists()

    override fun synthesize(lang: Lang, segment: TtsSegment, onChunk: (FloatArray) -> Unit) {
        require(supports(lang)) { "MioTtsEngine: no voice for $lang (or model files missing)" }
        ensureInit()
        ensureVoice(lang)
        MioNative.synthesize(renderText(segment), onChunk)
    }

    override fun close() {
        if (initialized) {
            MioNative.release()
            initialized = false
            loadedVoiceLang = null
        }
    }

    private fun ensureInit() {
        if (!initialized) {
            MioNative.init(lmGguf.absolutePath, codecGguf.absolutePath, wavlmGguf.absolutePath, numThreads)
            initialized = true
        }
    }

    private fun ensureVoice(lang: Lang) {
        if (loadedVoiceLang != lang) {
            MioNative.loadVoice(paths.mioVoice(lang).absolutePath)
            loadedVoiceLang = lang
        }
    }

    /** `*word*` stress markers, then the emotion tag appended as plain text at the sentence end. */
    internal fun renderText(segment: TtsSegment): String {
        var text = segment.text
        for (word in segment.stressWords) {
            if (word.isBlank()) continue
            val pattern = Regex("(?i)\\b${Regex.escape(word)}\\b")
            text = pattern.replace(text) { "*${it.value}*" }
        }
        val tag = emotionTag(segment.emotion)
        return if (tag == null) text else "$text $tag"
    }

    private fun emotionTag(emotion: Emotion): String? = when (emotion) {
        Emotion.HAPPY -> "<happy>"
        Emotion.SAD -> "<sad>"
        Emotion.ANGRY -> "<angry>"
        Emotion.FEAR -> "<fear>"
        Emotion.SURPRISE -> "<surprise>"
        Emotion.DISGUST -> "<disgust>"
        Emotion.NEUTRAL, Emotion.URGENT -> null
    }
}
