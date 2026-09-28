package org.itantra.stt

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import org.itantra.core.Lang
import org.itantra.core.ModelPaths
import org.itantra.core.RecognizedSentence
import org.itantra.core.SttEngine

/**
 * sherpa-onnx backed [SttEngine]: Silero VAD cuts the incoming PCM stream into speech segments,
 * each segment is decoded offline by a NeMo TDT transducer (SraVaani-1.0, int8).
 *
 * Not thread-safe: [accept] must be called from a single (audio) thread, matching the contract.
 * Model files are loaded from disk ([ModelPaths]); `AssetManager` is never used.
 */
class SherpaSttEngine(
    private val paths: ModelPaths,
    private val numThreads: Int = 4,
) : SttEngine {

    override var language: Lang = Lang.HI
    override var listener: ((RecognizedSentence) -> Unit)? = null

    override val isSpeechActive: Boolean
        get() = vad.isSpeechDetected()

    private var hotwords: List<String> = emptyList()

    private val vad = Vad(
        assetManager = null,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = paths.vad.absolutePath,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    // Lazily built: the beam-search (hotwords) recognizer needs bpe.vocab, which we don't
    // want to require unless hotwords are actually used.
    private var greedyRecognizer: OfflineRecognizer? = null
    private var beamRecognizer: OfflineRecognizer? = null

    // Wall-clock anchor: epoch ms corresponding to the first sample ever fed to [accept],
    // i.e. sample index 0 of the VAD's internal stream. Segment offsets (in samples) are
    // converted to epoch ms relative to this anchor.
    private var anchorEpochMs: Long = 0L
    private var anchorSet: Boolean = false

    override fun setHotwords(words: List<String>) {
        hotwords = words.filter { it.isNotBlank() }
    }

    override fun accept(samples: FloatArray) {
        if (samples.isEmpty()) return
        if (!anchorSet) {
            anchorEpochMs = System.currentTimeMillis()
            anchorSet = true
        }
        vad.acceptWaveform(samples)
        drainSegments()
    }

    override fun flush() {
        vad.flush()
        drainSegments()
    }

    override fun reset() {
        vad.reset()
        anchorSet = false
        anchorEpochMs = 0L
    }

    override fun close() {
        vad.release()
        greedyRecognizer?.release()
        beamRecognizer?.release()
        greedyRecognizer = null
        beamRecognizer = null
    }

    private fun drainSegments() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            decodeSegment(segment)
        }
    }

    private fun decodeSegment(segment: SpeechSegment) {
        if (segment.samples.isEmpty()) return

        val speechStartAt = TimingMath.epochMsAt(anchorEpochMs, segment.start.toLong(), SAMPLE_RATE)
        val speechEndAt = TimingMath.epochMsAt(
            anchorEpochMs,
            segment.start.toLong() + segment.samples.size,
            SAMPLE_RATE,
        )

        val useHotwords = hotwords.isNotEmpty()
        val recognizer = if (useHotwords) beamRecognizerOrBuild() else greedyRecognizerOrBuild()
        val stream: OfflineStream =
            if (useHotwords) recognizer.createStream(hotwords.joinToString("\n")) else recognizer.createStream()

        val decodeStartMs = System.currentTimeMillis()
        try {
            stream.acceptWaveform(segment.samples, SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val decodeMs = System.currentTimeMillis() - decodeStartMs
            val text = result.text.trim()
            if (text.isEmpty()) return

            listener?.invoke(
                RecognizedSentence(
                    text = text,
                    lang = language,
                    speechStartAt = speechStartAt,
                    speechEndAt = speechEndAt,
                    sttDoneAt = System.currentTimeMillis(),
                    audioSeconds = TimingMath.audioSeconds(segment.samples.size, SAMPLE_RATE),
                    decodeMs = decodeMs,
                ),
            )
        } finally {
            stream.release()
        }
    }

    private fun greedyRecognizerOrBuild(): OfflineRecognizer =
        greedyRecognizer ?: buildRecognizer(useHotwords = false).also { greedyRecognizer = it }

    private fun beamRecognizerOrBuild(): OfflineRecognizer =
        beamRecognizer ?: buildRecognizer(useHotwords = true).also { beamRecognizer = it }

    private fun buildRecognizer(useHotwords: Boolean): OfflineRecognizer {
        val sttDir = paths.sttDir
        val modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(sttDir, "encoder.int8.onnx").absolutePath,
                decoder = File(sttDir, "decoder.int8.onnx").absolutePath,
                joiner = File(sttDir, "joiner.int8.onnx").absolutePath,
            ),
            numThreads = numThreads,
            provider = "cpu",
            modelType = "nemo_transducer",
            tokens = File(sttDir, "tokens.txt").absolutePath,
            modelingUnit = if (useHotwords) "bpe" else "",
            bpeVocab = if (useHotwords) File(sttDir, "bpe.vocab").absolutePath else "",
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            decodingMethod = if (useHotwords) "modified_beam_search" else "greedy_search",
            hotwordsScore = 1.5f,
        )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 128
    }
}
