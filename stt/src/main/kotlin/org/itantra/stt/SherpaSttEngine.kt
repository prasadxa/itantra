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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
    override var partialListener: ((id: String, text: String) -> Unit)? = null

    override val isSpeechActive: Boolean
        get() = vad.isSpeechDetected()

    private var hotwords: List<String> = emptyList()

    // Live-partial state: while VAD reports speech active we mirror the samples into a growable
    // buffer and, every ~500ms, decode a snapshot of it on a single background worker (never
    // overlapping) so accept() itself stays fast. Utterance ids are assigned FIFO on speech start
    // and matched to the VAD's finalised segments in the same order in drainSegments/decodeSegment,
    // since sherpa's Vad processes one utterance at a time.
    private val partialBuffer = ArrayList<Float>()
    private val pendingIds = ArrayDeque<String>()
    private var currentUtteranceId: String? = null
    private var lastPartialEmitMs: Long = 0L
    private val partialExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "stt-partial").apply { isDaemon = true } }
    private val partialDecoding = AtomicBoolean(false)
    // Guards native decode calls shared between the caller thread (final segments) and the
    // partial-decode worker thread, so they never call into the same recognizer concurrently.
    private val decodeLock = Any()

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

    // Sample clock: VAD sample index -> epoch ms, anchored to the most recent chunk so gaps
    // (mic gating during playback) and faster-than-real-time feeding (WAV tests) stay correct.
    private var fedSamples: Long = 0L
    private var lastChunkEpochMs: Long = 0L
    private fun sampleToEpochMs(index: Long): Long =
        lastChunkEpochMs - (fedSamples - index) * 1000L / SAMPLE_RATE

    /** Guards the VAD and recognizers: mic thread, debug hooks and UI may all call in. */
    private val lock = Any()

    init {
        // Fail fast so EngineFactory reports STT as unavailable instead of crashing on first decode.
        val missing = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            .filterNot { java.io.File(paths.sttDir, it).isFile }
        require(missing.isEmpty()) { "Missing STT model files in ${paths.sttDir}: $missing" }
        greedyRecognizerOrBuild()
    }

    override fun setHotwords(words: List<String>) {
        synchronized(lock) {
            hotwords = words.filter { it.isNotBlank() }
        }
    }

    override fun accept(samples: FloatArray) {
        // sherpa-onnx's VAD only segments correctly when fed roughly window-sized chunks; a single
        // multi-second buffer yields just its first ~0.3 s. Split large inputs (e.g. WAV injection).
        if (samples.size <= VAD_WINDOW) return acceptChunk(samples)
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + VAD_WINDOW, samples.size)
            acceptChunk(samples.copyOfRange(i, end))
            i = end
        }
    }

    private fun acceptChunk(samples: FloatArray) {
        synchronized(lock) {
            if (samples.isEmpty()) return
            fedSamples += samples.size
            lastChunkEpochMs = System.currentTimeMillis()
            if (!anchorSet) {
                anchorEpochMs = System.currentTimeMillis()
                anchorSet = true
            }
            val wasActive = vad.isSpeechDetected()
            vad.acceptWaveform(samples)
            val isActive = vad.isSpeechDetected()
            if (isActive) {
                if (!wasActive) {
                    val id = UUID.randomUUID().toString()
                    currentUtteranceId = id
                    pendingIds.addLast(id)
                    partialBuffer.clear()
                    lastPartialEmitMs = 0L
                }
                for (s in samples) partialBuffer.add(s)
                maybeEmitPartial()
            }
            drainSegments()
        }
    }

    override fun flush() {
        synchronized(lock) {
            vad.flush()
            drainSegments()
        }
    }

    override fun reset() {
        synchronized(lock) {
            vad.reset()
            anchorSet = false
            anchorEpochMs = 0L
            fedSamples = 0L
            partialBuffer.clear()
            pendingIds.clear()
            currentUtteranceId = null
        }
    }

    override fun close() {
        synchronized(lock) {
            vad.release()
            greedyRecognizer?.release()
            beamRecognizer?.release()
            greedyRecognizer = null
            beamRecognizer = null
            partialExecutor.shutdownNow()
        }
    }

    /** Decodes a snapshot of the in-progress utterance on the background worker, at most 2x/s.
     * No-op with no listener attached (Profile.LITE turns partials off — see
     * app/.../Orchestrator.onLocalPartial) so LITE phones skip this decode entirely, not just the
     * callback. */
    private fun maybeEmitPartial() {
        if (partialListener == null) return
        val id = currentUtteranceId ?: return
        val now = System.currentTimeMillis()
        if (now - lastPartialEmitMs < 500) return
        if (partialBuffer.size < SAMPLE_RATE / 4) return // need >=250ms of audio to bother
        if (!partialDecoding.compareAndSet(false, true)) return
        lastPartialEmitMs = now
        val snapshot = partialBuffer.toFloatArray()
        partialExecutor.execute {
            try {
                val recognizer = greedyRecognizerOrBuild()
                val stream = recognizer.createStream()
                try {
                    synchronized(decodeLock) {
                        stream.acceptWaveform(snapshot, SAMPLE_RATE)
                        recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text.trim()
                        if (text.isNotEmpty()) partialListener?.invoke(id, text)
                    }
                } finally {
                    stream.release()
                }
            } catch (e: Exception) {
                android.util.Log.e("SherpaSttEngine", "partial decode failed", e)
            } finally {
                partialDecoding.set(false)
            }
        }
    }

    private fun drainSegments() {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            try {
                decodeSegment(segment)
            } catch (e: Exception) {
                android.util.Log.e("SherpaSttEngine", "decode failed; dropping segment", e)
            }
        }
    }

    private fun decodeSegment(segment: SpeechSegment) {
        val id = pendingIds.pollFirst() ?: UUID.randomUUID().toString()
        if (segment.samples.isEmpty()) return

        val speechStartAt = sampleToEpochMs(segment.start.toLong())
        val speechEndAt = sampleToEpochMs(segment.start.toLong() + segment.samples.size)

        val useHotwords = hotwords.isNotEmpty()
        val recognizer = if (useHotwords) beamRecognizerOrBuild() else greedyRecognizerOrBuild()
        val stream: OfflineStream =
            if (useHotwords) recognizer.createStream(hotwords.joinToString("\n")) else recognizer.createStream()

        val decodeStartMs = System.currentTimeMillis()
        try {
            val text: String
            val decodeMs: Long
            synchronized(decodeLock) {
                stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                decodeMs = System.currentTimeMillis() - decodeStartMs
                text = result.text.trim()
            }
            if (text.isEmpty()) return

            listener?.invoke(
                RecognizedSentence(
                    id = id,
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
        private const val VAD_WINDOW = 512
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 128
    }
}
