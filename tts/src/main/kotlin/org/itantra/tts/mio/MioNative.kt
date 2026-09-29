package org.itantra.tts.mio

/**
 * Thin JNI bridge to mio-tts-cpp (`tts/src/main/cpp/native_bridge.cpp`, built on llama.cpp).
 * One process-wide native handle: [init] loads the LLM + MioCodec pair, [loadVoice] swaps the
 * active voice embedding, [synthesize] streams PCM chunks for the current voice, [release] frees
 * everything. Not thread-safe — callers (see `MioTtsEngine`) must serialize calls onto one worker
 * thread, matching `TtsEngine`'s documented contract.
 *
 * WavLM (`wavlm.gguf`) is deliberately never loaded: mio-tts-cpp only uses it to extract a speaker
 * embedding from raw reference audio at runtime (voice cloning), which this app never does — voice
 * embeddings are precomputed offline into `<lang>.emb.gguf` (see [loadVoice]) — so skipping it saves
 * ~90 MB of resident memory for no loss of functionality this app actually uses.
 */
object MioNative {
    init {
        System.loadLibrary("miotts")
    }

    @Volatile private var handle: Long = 0
    @Volatile private var cachedSampleRate: Int = 24000

    val isInitialized: Boolean get() = handle != 0L
    val sampleRate: Int get() = cachedSampleRate

    /** Loads `indic-mio-q8_0.gguf` (or the q4 LITE weights) as the LLM and `miocodec.gguf`
     * (or the f16-shrunk variant) as the vocoder; see the class doc for why `wavlm.gguf` is
     * never loaded. */
    fun init(lmGguf: String, codecGguf: String, threads: Int) {
        check(handle == 0L) { "MioNative already initialized; call release() first" }
        val h = nativeInit(lmGguf, codecGguf, threads)
        if (h == 0L) error("mio-tts-cpp init failed: ${nativeLastError(0)}")
        handle = h
        cachedSampleRate = nativeSampleRate(h)
    }

    /** Loads a `<lang>.emb.gguf` voice embedding and makes it the active voice for [synthesize]. */
    fun loadVoice(embGguf: String) {
        val h = handle
        check(h != 0L) { "MioNative not initialized" }
        if (!nativeLoadVoice(h, embGguf)) error("mio-tts-cpp loadVoice failed: ${nativeLastError(h)}")
    }

    /**
     * Synthesizes [text] (already wrapped with the mio prompt template / emotion tag / stress
     * markers by the caller) with the currently loaded voice. Blocking; invokes [chunkCallback]
     * with float PCM in [-1, 1] at [sampleRate] Hz as ~50-token audio chunks are decoded.
     */
    fun synthesize(text: String, chunkCallback: (FloatArray) -> Unit) {
        val h = handle
        check(h != 0L) { "MioNative not initialized" }
        if (!nativeSynthesize(h, text, chunkCallback)) {
            error("mio-tts-cpp synthesize failed: ${nativeLastError(h)}")
        }
    }

    fun release() {
        val h = handle
        if (h != 0L) {
            nativeRelease(h)
            handle = 0
        }
    }

    private external fun nativeInit(lmGguf: String, codecGguf: String, threads: Int): Long
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeLoadVoice(handle: Long, embGguf: String): Boolean
    private external fun nativeSynthesize(handle: Long, text: String, chunkCallback: (FloatArray) -> Unit): Boolean
    private external fun nativeLastError(handle: Long): String
    private external fun nativeRelease(handle: Long)
}
