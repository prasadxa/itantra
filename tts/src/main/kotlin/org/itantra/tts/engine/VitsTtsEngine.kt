package org.itantra.tts.engine

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import org.itantra.core.Emotion
import org.itantra.core.Lang
import org.itantra.core.ModelPaths
import org.itantra.core.TtsEngine
import org.itantra.core.TtsSegment

/**
 * sherpa-onnx offline VITS [TtsEngine] over HF `MatiasLin/sherpa-onnx-vits-rasa-13`
 * (ONNX export of `ai4bharat/vits_rasa_13`, 1024 speakers x 32 emotion/style ids, 22050 Hz),
 * used as [MioTtsEngine]'s fallback for the 6 languages it has speakers for: bn, kn, ml, mr,
 * ta, te. Requires sherpa-onnx >= 1.13.5 (the `emotion_id` extra / PR k2-fsa/sherpa-onnx#3849;
 * confirmed present in this project's pinned `v1.13.8`, see gradle/libs.versions.toml).
 *
 * Speaker ids below come from the (gated) `ai4bharat/vits_rasa_13` model card's
 * "Speaker-Style Identifier Overview" table (20 speakers, ISO 639-3 codes); female voice
 * preferred where a language has both. Re-verify against the model's own metadata once the team
 * has HF access — see `tools/tts/VOICES.md`.
 */
class VitsTtsEngine(private val paths: ModelPaths, private val numThreads: Int) : TtsEngine {

    private val modelOnnx = File(paths.vitsDir, "model.onnx")
    private val tokensTxt = File(paths.vitsDir, "tokens.txt")

    private val tts: OfflineTts? by lazy {
        if (!modelOnnx.exists() || !tokensTxt.exists()) return@lazy null
        OfflineTts(
            null,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelOnnx.absolutePath,
                        tokens = tokensTxt.absolutePath,
                    ),
                    numThreads = numThreads,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            ),
        )
    }

    override val sampleRate: Int get() = tts?.sampleRate() ?: SAMPLE_RATE

    override fun supports(lang: Lang): Boolean = lang in SPEAKER_ID_BY_LANG && tts != null

    override fun synthesize(lang: Lang, segment: TtsSegment, onChunk: (FloatArray) -> Unit) {
        val engine = tts
        require(engine != null && lang in SPEAKER_ID_BY_LANG) { "VitsTtsEngine: unsupported lang $lang" }

        // speed left at the GenerationConfig default (1.0): rate is applied uniformly to every
        // engine's output by SpeechOutput's WSOLA DSP stage, so MioTtsEngine (no native rate
        // control) and VitsTtsEngine behave the same way under FallbackTtsEngine.
        val config = GenerationConfig(
            sid = SPEAKER_ID_BY_LANG.getValue(lang),
            extra = mapOf("emotion_id" to styleId(segment.emotion).toString()),
        )
        // Must be a real class, not a lambda: sherpa-onnx's JNI looks up `invoke([F)Ljava/lang/Integer;`,
        // which Kotlin 2.x invokedynamic lambdas don't expose (NoSuchMethodError → JNI abort).
        val callback = object : (FloatArray) -> Int {
            override fun invoke(chunk: FloatArray): Int {
                onChunk(chunk)
                return 1 // non-zero = keep generating
            }
        }
        engine.generateWithConfigAndCallback(segment.text, config, callback)
    }

    override fun close() {
        tts?.free()
    }

    companion object {
        const val SAMPLE_RATE = 22050

        /** Style/emotion ids from `ai4bharat/vits_rasa_13`'s speaker-style table. */
        private fun styleId(emotion: Emotion): Int = when (emotion) {
            Emotion.ANGRY -> 1
            Emotion.NEUTRAL -> 4 // CONV
            Emotion.DISGUST -> 6
            Emotion.FEAR -> 7
            Emotion.HAPPY -> 8
            Emotion.URGENT -> 10 // NEWS
            Emotion.SAD -> 12
            Emotion.SURPRISE -> 14
        }

        /** Female voice preferred by default; see class doc for provenance. */
        private val SPEAKER_ID_BY_LANG: Map<Lang, Int> = mapOf(
            Lang.BN to 2, // BEN_F
            Lang.KN to 8, // KAN_F
            Lang.ML to 11, // MAL_F
            Lang.MR to 12, // MAR_F
            Lang.TA to 18, // TAM_F
            Lang.TE to 19, // TEL_F
        )
    }
}
