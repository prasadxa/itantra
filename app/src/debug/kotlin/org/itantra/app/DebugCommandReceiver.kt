package org.itantra.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.itantra.app.location.LocationProvider
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.TtsEngine
import org.itantra.core.VoiceMessage

private const val TAG = "DebugCommandReceiver"
private const val ACTION_DEBUG_STT_WAV = "org.itantra.DEBUG_STT_WAV"
private const val ACTION_DEBUG_SPEAK = "org.itantra.DEBUG_SPEAK"
private const val ACTION_DEBUG_STT_BATCH = "org.itantra.DEBUG_STT_BATCH"
private const val ACTION_DEBUG_TTS_SAVE = "org.itantra.DEBUG_TTS_SAVE"
private const val ACTION_DEBUG_SOS = "org.itantra.DEBUG_SOS"

/**
 * Debug-build-only hooks for automated on-device testing (this whole `app/src/debug` source set
 * is excluded from release builds; see `app/src/debug/AndroidManifest.xml`). Requires
 * [TalkService] to already be running. Every result — success, missing extras, or an engine
 * unavailable because models aren't installed yet — is logged as one JSON line on logcat tag
 * [Metrics.TAG] ("ITANTRA_METRIC") so a host-side harness can just filter/parse that tag.
 *
 * Usage (adb):
 * ```
 * adb shell am broadcast -a org.itantra.DEBUG_STT_WAV -n org.itantra.app/.DebugCommandReceiver \
 *     --es path /sdcard/test.wav --es lang hi
 * adb shell am broadcast -a org.itantra.DEBUG_SPEAK -n org.itantra.app/.DebugCommandReceiver \
 *     --es text "namaste, kaise ho" --es lang hi --ez alert false --ez ssml false
 * adb shell am broadcast -a org.itantra.DEBUG_SOS -n org.itantra.app/.DebugCommandReceiver \
 *     --es text "Need rescue" --es lang en --ez safe false \
 *     --es lat 12.9716 --es lon 77.5946 --es accuracyM 15
 * ```
 */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val service = TalkService.instance
        if (service == null) {
            Metrics.log("event" to "debug_hook_error", "action" to intent.action, "reason" to "TalkService not running")
            return
        }
        when (intent.action) {
            ACTION_DEBUG_STT_WAV -> {
                val path = intent.getStringExtra("path")
                if (path == null) {
                    Metrics.log("event" to "debug_hook_error", "action" to intent.action, "reason" to "missing 'path' extra")
                    return
                }
                handleSttWav(service, path, intent.getStringExtra("lang"))
            }
            ACTION_DEBUG_SPEAK -> {
                val text = intent.getStringExtra("text")
                if (text == null) {
                    Metrics.log("event" to "debug_hook_error", "action" to intent.action, "reason" to "missing 'text' extra")
                    return
                }
                handleSpeak(
                    service = service,
                    text = text,
                    langCode = intent.getStringExtra("lang"),
                    alert = intent.getBooleanExtra("alert", false),
                    ssml = intent.getBooleanExtra("ssml", false),
                    play = intent.getBooleanExtra("play", true),
                )
            }
            ACTION_DEBUG_STT_BATCH -> {
                val manifest = intent.getStringExtra("manifest")
                if (manifest == null) {
                    Metrics.log("event" to "debug_hook_error", "action" to intent.action, "reason" to "missing 'manifest' extra")
                    return
                }
                // A batch of hundreds of clips run sequentially can easily take tens of seconds,
                // far past Android's BroadcastReceiver.onReceive() ANR watchdog (~10s) - this
                // genuinely happened during eval harness runs (ANR on DEBUG_STT_BATCH). goAsync()
                // + a background thread lets onReceive() return immediately while the batch keeps
                // running; pendingResult.finish() only after every event is logged.
                val pending = goAsync()
                Thread {
                    try {
                        handleSttBatch(service, manifest)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            ACTION_DEBUG_SOS -> {
                handleSos(
                    service = service,
                    text = intent.getStringExtra("text") ?: "Need rescue",
                    langCode = intent.getStringExtra("lang"),
                    safe = intent.getBooleanExtra("safe", false),
                    lat = intent.getStringExtra("lat")?.toDoubleOrNull(),
                    lon = intent.getStringExtra("lon")?.toDoubleOrNull(),
                    accuracyM = intent.getStringExtra("accuracyM")?.toFloatOrNull(),
                )
            }
            ACTION_DEBUG_TTS_SAVE -> {
                val text = intent.getStringExtra("text")
                val out = intent.getStringExtra("out")
                if (text == null || out == null) {
                    Metrics.log("event" to "debug_hook_error", "action" to intent.action, "reason" to "missing 'text' or 'out' extra")
                    return
                }
                // Same ANR-safety reasoning as DEBUG_STT_BATCH above: a long alert sentence at a
                // slow synthesis RTF can approach the onReceive() watchdog even for one call.
                val pending = goAsync()
                Thread {
                    try {
                        handleTtsSave(service, text, intent.getStringExtra("lang"), out)
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
        }
    }

    /** Feeds a 16 kHz mono WAV through the same STT -> send pipeline as the mic, as if spoken. */
    private fun handleSttWav(service: TalkService, path: String, langCode: String?) {
        val sttEngine = service.engines.stt
        if (sttEngine == null) {
            Metrics.log("event" to "debug_stt_wav", "path" to path, "error" to "stt_unavailable (models missing)")
            return
        }
        val wav = try {
            readWavMonoFloat(File(path))
        } catch (t: Throwable) {
            Metrics.log("event" to "debug_stt_wav", "path" to path, "error" to "wav_read_failed: ${t.message}")
            return
        }
        if (wav == null) {
            Metrics.log("event" to "debug_stt_wav", "path" to path, "error" to "invalid_wav")
            return
        }
        if (wav.sampleRateHz != 16000 || wav.channels != 1) {
            Log.w(TAG, "$path is ${wav.sampleRateHz}Hz/${wav.channels}ch, not 16kHz mono; feeding as-is")
        }

        val lang = langCode?.let { runCatching { Lang.of(it) }.getOrNull() } ?: service.orchestrator.language
        val prevLang = sttEngine.language
        val prevListener = sttEngine.listener
        val t0 = System.currentTimeMillis()
        // Collect every sentence the VAD cuts from the file, then log one combined result and
        // forward each sentence to the normal pipeline. The mic is paused so its samples can't
        // interleave with the file in the VAD stream.
        val collected = mutableListOf<org.itantra.core.RecognizedSentence>()
        sttEngine.listener = { collected += it }
        sttEngine.language = lang
        val micWasOn = service.engines.micCapture?.isRunning == true
        service.engines.micCapture?.stop()
        try {
            sttEngine.reset()
            sttEngine.accept(wav.samples)
            sttEngine.flush()
        } finally {
            sttEngine.listener = prevListener
            sttEngine.language = prevLang
            if (micWasOn) service.resumeMicAfterDebug()
        }
        val decodeMs = collected.sumOf { it.decodeMs }
        val audioSeconds = wav.samples.size / 16000f
        Metrics.log(
            "event" to "debug_stt_wav",
            "path" to path,
            "lang" to lang.code,
            "text" to collected.joinToString(" ") { it.text },
            "segments" to collected.size,
            "audioSeconds" to audioSeconds,
            "decodeMs" to decodeMs,
            "sttLatencyMs" to collected.lastOrNull()?.let { it.sttDoneAt - it.speechEndAt },
            "rtf" to if (audioSeconds > 0f) decodeMs / (audioSeconds * 1000f) else null,
            "totalMs" to (System.currentTimeMillis() - t0),
        )
        collected.forEach { prevListener?.invoke(it) }
    }

    /**
     * Runs every clip in a manifest.tsv (as written by tools/eval/fetch_fleurs.py: header
     * `filename<TAB>lang<TAB>transcript<TAB>duration_sec<TAB>source_id`, `filename` relative to
     * the manifest's own directory, e.g. `hi/clip_000.wav`) through the same STT path as
     * [handleSttWav], sequentially, with the mic paused for the whole batch. Logs one
     * `debug_stt_batch_clip` event per clip (consumed by tools/eval/score_stt.py) plus one
     * `debug_stt_batch_done` summary event.
     */
    private fun handleSttBatch(service: TalkService, manifestPath: String) {
        val sttEngine = service.engines.stt
        if (sttEngine == null) {
            Metrics.log("event" to "debug_stt_batch_done", "manifest" to manifestPath, "error" to "stt_unavailable (models missing)")
            return
        }
        val manifestFile = File(manifestPath)
        val lines = try {
            manifestFile.readLines()
        } catch (t: Throwable) {
            Metrics.log("event" to "debug_stt_batch_done", "manifest" to manifestPath, "error" to "manifest_read_failed: ${t.message}")
            return
        }
        val rows = lines
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() && !it.startsWith("filename\t") }
            .mapNotNull { line ->
                val cols = line.split("\t")
                if (cols.size < 3) null else Triple(cols[0], cols[1], cols[2])
            }
        if (rows.isEmpty()) {
            Metrics.log("event" to "debug_stt_batch_done", "manifest" to manifestPath, "error" to "manifest_empty_or_unparseable")
            return
        }

        val prevListener = sttEngine.listener
        val prevLang = sttEngine.language
        val micWasOn = service.engines.micCapture?.isRunning == true
        service.engines.micCapture?.stop()
        val batchStart = System.currentTimeMillis()
        var ok = 0
        var failed = 0
        try {
            for ((relPath, langCode, _transcript) in rows) {
                val lang = runCatching { Lang.of(langCode) }.getOrNull()
                if (lang == null) {
                    Metrics.log("event" to "debug_stt_batch_clip", "filename" to relPath, "lang" to langCode, "error" to "unknown_lang")
                    failed++
                    continue
                }
                val wavFile = File(manifestFile.parentFile, relPath)
                val wav = try {
                    readWavMonoFloat(wavFile)
                } catch (t: Throwable) {
                    Metrics.log("event" to "debug_stt_batch_clip", "filename" to relPath, "lang" to lang.code, "error" to "wav_read_failed: ${t.message}")
                    failed++
                    continue
                }
                if (wav == null) {
                    Metrics.log("event" to "debug_stt_batch_clip", "filename" to relPath, "lang" to lang.code, "error" to "invalid_wav")
                    failed++
                    continue
                }
                val collected = mutableListOf<org.itantra.core.RecognizedSentence>()
                sttEngine.listener = { collected += it }
                sttEngine.language = lang
                sttEngine.reset()
                sttEngine.accept(wav.samples)
                sttEngine.flush()
                val decodeMs = collected.sumOf { it.decodeMs }
                val audioSeconds = wav.samples.size / 16000f
                Metrics.log(
                    "event" to "debug_stt_batch_clip",
                    "filename" to relPath,
                    "lang" to lang.code,
                    "text" to collected.joinToString(" ") { it.text },
                    "segments" to collected.size,
                    "audioSeconds" to audioSeconds,
                    "decodeMs" to decodeMs,
                    "sttLatencyMs" to collected.lastOrNull()?.let { it.sttDoneAt - it.speechEndAt },
                    "rtf" to if (audioSeconds > 0f) decodeMs / (audioSeconds * 1000f) else null,
                )
                ok++
            }
        } finally {
            sttEngine.listener = prevListener
            sttEngine.language = prevLang
            if (micWasOn) service.resumeMicAfterDebug()
        }
        Metrics.log(
            "event" to "debug_stt_batch_done", "manifest" to manifestPath,
            "clips" to rows.size, "ok" to ok, "failed" to failed,
            "totalMs" to (System.currentTimeMillis() - batchStart),
        )
    }

    /**
     * Synthesizes [text] through the same TextPipeline -> TTS path as [handleSpeak] but never
     * plays it: collects every PCM chunk, resamples to 16 kHz mono if the engine's native rate
     * differs, and writes a WAV to [outPath] (must be under the app's own external files dir,
     * e.g. under `/sdcard/Android/data/org.itantra.app/files/...`, so no extra storage
     * permission is needed). Used by tools/eval/tts_intelligibility.py.
     */
    private fun handleTtsSave(service: TalkService, text: String, langCode: String?, outPath: String) {
        val lang = langCode?.let { runCatching { Lang.of(it) }.getOrNull() } ?: service.orchestrator.language
        val textPipeline = service.engines.textPipeline
        val ttsEngine = service.engines.ttsEngine
        if (textPipeline == null || ttsEngine == null) {
            Metrics.log("event" to "debug_tts_save", "text" to text, "lang" to lang.code, "out" to outPath, "error" to "engine_unavailable (models missing)")
            return
        }
        if (!ttsEngine.supports(lang)) {
            Metrics.log("event" to "debug_tts_save", "text" to text, "lang" to lang.code, "out" to outPath, "error" to "tts_unavailable (no Mio/VITS voice for ${lang.code})")
            return
        }

        val now = System.currentTimeMillis()
        val message = VoiceMessage(
            id = UUID.randomUUID().toString(), from = service.deviceId, lang = lang, text = text,
            priority = Priority.NORMAL, emotion = null, ssml = false,
            speechEndAt = now, sttDoneAt = now, sentAt = now,
        )
        val plan = textPipeline.plan(message)
        val engineUsed = probeEngineName(ttsEngine, plan.lang)

        val chunks = mutableListOf<FloatArray>()
        var firstChunkMs: Long? = null
        var synthMs = 0L
        val start = System.currentTimeMillis()
        try {
            for (segment in plan.segments) {
                val segStart = System.currentTimeMillis()
                ttsEngine.synthesize(plan.lang, segment) { chunk ->
                    if (firstChunkMs == null) firstChunkMs = System.currentTimeMillis() - start
                    chunks += chunk
                }
                synthMs += System.currentTimeMillis() - segStart
            }
        } catch (t: Throwable) {
            Metrics.log("event" to "debug_tts_save", "text" to text, "lang" to lang.code, "out" to outPath, "engine" to engineUsed, "error" to "synth_failed: ${t.message}")
            return
        }

        val nativeRate = ttsEngine.sampleRate
        var samples = concatFloatArrays(chunks)
        val audioSeconds = samples.size / nativeRate.toFloat()
        if (nativeRate != 16000) {
            samples = resampleLinear(samples, nativeRate, 16000)
        }

        try {
            File(outPath).also { it.parentFile?.mkdirs() }.let { writeWavMono16(it, samples, 16000) }
        } catch (t: Throwable) {
            Metrics.log("event" to "debug_tts_save", "text" to text, "lang" to lang.code, "out" to outPath, "engine" to engineUsed, "error" to "wav_write_failed: ${t.message}")
            return
        }

        val rtf = if (audioSeconds > 0f) synthMs / (audioSeconds * 1000f) else null
        Metrics.log(
            "event" to "debug_tts_save", "text" to text, "lang" to lang.code, "out" to outPath,
            "segments" to plan.segments.size, "engine" to engineUsed, "nativeSampleRate" to nativeRate,
            "audioSeconds" to audioSeconds, "synthMs" to synthMs, "rtf" to rtf, "firstChunkMs" to firstChunkMs,
        )
    }

    /** Runs the receive path locally: TextPipeline -> TTS -> SpeechOutput, as if a Frame.Msg arrived. */
    /**
     * One-tap SOS via the same [TalkService.onSendSos] path `SosSheet` uses. If `lat`/`lon`
     * aren't given, fetches a real fix via [LocationProvider] (falls back to a location-less SOS
     * on timeout/no permission) - exercises the actual on-device GPS path, not just a hardcoded
     * coordinate, so this also serves as the "debug path" for end-to-end gateway verification.
     */
    private fun handleSos(service: TalkService, text: String, langCode: String?, safe: Boolean, lat: Double?, lon: Double?, accuracyM: Float?) {
        val lang = langCode?.let { runCatching { Lang.of(it) }.getOrNull() } ?: service.orchestrator.language
        if (lat != null && lon != null) {
            val id = service.onSendSos(text, lang, lat, lon, accuracyM, safe)
            Metrics.log("event" to "debug_sos", "id" to id, "lang" to lang.code, "lat" to lat, "lon" to lon, "accuracyM" to accuracyM, "source" to "extras")
            return
        }
        val pending = goAsync()
        Thread {
            try {
                val provider = LocationProvider(service.applicationContext)
                val fix = runBlocking { provider.getFix(8_000L) }
                val id = service.onSendSos(text, lang, fix?.lat, fix?.lon, fix?.accuracyOrNull, safe)
                Metrics.log(
                    "event" to "debug_sos", "id" to id, "lang" to lang.code,
                    "lat" to fix?.lat, "lon" to fix?.lon, "accuracyM" to fix?.accuracyOrNull,
                    "constellations" to fix?.constellations?.joinToString(","),
                    "source" to "gps",
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handleSpeak(service: TalkService, text: String, langCode: String?, alert: Boolean, ssml: Boolean, play: Boolean = true) {
        val lang = langCode?.let { runCatching { Lang.of(it) }.getOrNull() } ?: service.orchestrator.language
        val textPipeline = service.engines.textPipeline
        if (textPipeline == null) {
            Metrics.log("event" to "debug_speak", "text" to text, "lang" to lang.code, "error" to "text_pipeline_unavailable")
            return
        }

        val now = System.currentTimeMillis()
        val message = VoiceMessage(
            id = UUID.randomUUID().toString(), from = service.deviceId, lang = lang, text = text,
            priority = if (alert) Priority.ALERT else Priority.NORMAL, emotion = null, ssml = ssml,
            speechEndAt = now, sttDoneAt = now, sentAt = now,
        )
        val plan = textPipeline.plan(message)

        val ttsEngine = service.engines.ttsEngine
        val speechOutput = service.engines.speechOutput
        if (ttsEngine == null || speechOutput == null) {
            Metrics.log(
                "event" to "debug_speak", "id" to message.id, "text" to text, "lang" to lang.code,
                "alert" to alert, "ssml" to ssml, "segments" to plan.segments.size,
                "error" to "tts_unavailable (models missing)",
            )
            return
        }
        if (!ttsEngine.supports(plan.lang)) {
            // Fail fast and report: if we enqueued anyway, SpeechOutput's synthesize() would
            // throw with no chunk ever written, so onPlayStarted (below) would simply never
            // fire and this hook would hang silently instead of reporting the failure.
            Metrics.log(
                "event" to "debug_speak", "id" to message.id, "text" to text, "lang" to lang.code,
                "alert" to alert, "ssml" to ssml, "segments" to plan.segments.size,
                "error" to "tts_unavailable (no Mio/VITS voice for ${plan.lang.code}; models missing?)",
            )
            return
        }

        // Best-effort standalone synthesis pass, purely to measure synthesis RTF and which
        // concrete engine FallbackTtsEngine picked - neither is exposed by :tts's public
        // TtsEngine API, so this mirrors FallbackTtsEngine's own "mio.supports(lang) ? mio :
        // vits" choice via reflection on its private fields (falls back to "unknown" if that
        // internal shape ever changes). The actual audible playback below goes through the real
        // TextPipeline -> TTS -> SpeechOutput pipeline, unmodified.
        var synthMs = 0L
        var audioSeconds = 0f
        var firstChunkMs: Long? = null
        val probeStart = System.currentTimeMillis()
        for (segment in plan.segments) {
            var samples = 0
            val segStart = System.currentTimeMillis()
            try {
                ttsEngine.synthesize(plan.lang, segment) { chunk ->
                    if (firstChunkMs == null) firstChunkMs = System.currentTimeMillis() - probeStart
                    samples += chunk.size
                }
            } catch (t: Throwable) {
                Log.w(TAG, "debug RTF probe synth failed", t)
            }
            synthMs += System.currentTimeMillis() - segStart
            audioSeconds += samples / ttsEngine.sampleRate.toFloat()
        }
        val rtf = if (audioSeconds > 0f) synthMs / (audioSeconds * 1000f) else null
        val engineUsed = probeEngineName(ttsEngine, plan.lang)

        if (!play) {
            Metrics.log(
                "event" to "debug_speak", "id" to message.id, "text" to text, "lang" to lang.code,
                "segments" to plan.segments.size, "engine" to engineUsed, "synthesisRtf" to rtf,
                "synthMs" to synthMs, "audioSeconds" to audioSeconds, "firstChunkMs" to firstChunkMs,
            )
            return
        }
        val t0 = System.currentTimeMillis()
        var timeToFirstAudioMs: Long? = null
        // SpeechOutput's own playback metrics (underrun/pre-roll/rebuffer counters - see item 4 of
        // the producer/consumer rework) aren't part of the small SpeechOutputPort contract this
        // hook otherwise talks through, so they're collected via SpeechOutput's own id-keyed
        // registry instead (see SpeechOutput.observeMetricsOnce's doc); must be registered before
        // enqueue() so it can't miss a fast-finishing (e.g. very short/cached) message.
        org.itantra.tts.output.SpeechOutput.observeMetricsOnce(message.id) { playbackMetrics ->
            Metrics.log(
                "event" to "debug_speak_playback", "id" to playbackMetrics.id, "text" to text, "lang" to lang.code,
                "engine" to engineUsed, "timeToFirstAudioMs" to timeToFirstAudioMs,
                "underrunCountDelta" to playbackMetrics.underrunCountDelta,
                "preRollMs" to playbackMetrics.preRollMs,
                "rebufferPauses" to playbackMetrics.rebufferPauses,
            )
        }
        speechOutput.enqueue(message.id, plan, phoneMode = false) { id, playStartedEpochMs ->
            timeToFirstAudioMs = playStartedEpochMs - t0
            Metrics.log(
                "event" to "debug_speak", "id" to id, "text" to text, "lang" to lang.code,
                "alert" to alert, "ssml" to ssml, "segments" to plan.segments.size,
                "engine" to engineUsed, "synthesisRtf" to rtf, "synthMs" to synthMs,
                "audioSeconds" to audioSeconds, "timeToFirstAudioMs" to timeToFirstAudioMs,
            )
        }
    }

    private fun probeEngineName(ttsEngine: TtsEngine, lang: Lang): String =
        (ttsEngine as? org.itantra.tts.engine.FallbackTtsEngine)?.engineNameFor(lang) ?: "unknown"
}

private fun concatFloatArrays(chunks: List<FloatArray>): FloatArray {
    val total = chunks.sumOf { it.size }
    val out = FloatArray(total)
    var pos = 0
    for (c in chunks) {
        System.arraycopy(c, 0, out, pos, c.size)
        pos += c.size
    }
    return out
}

/** Simple linear-interpolation resampler - good enough for ASR-intelligibility eval, not for playback quality. */
private fun resampleLinear(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
    if (fromRate == toRate || samples.isEmpty()) return samples
    val outLen = ((samples.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
    val out = FloatArray(outLen)
    val ratio = (samples.size - 1).toFloat() / (outLen - 1).coerceAtLeast(1)
    for (i in 0 until outLen) {
        val srcPos = i * ratio
        val i0 = srcPos.toInt().coerceIn(0, samples.size - 1)
        val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
        val frac = srcPos - i0
        out[i] = samples[i0] * (1 - frac) + samples[i1] * frac
    }
    return out
}

/** Writes 16 kHz(-or-whatever-[sampleRateHz]) mono PCM16 WAV - the mirror of [readWavMonoFloat]. */
private fun writeWavMono16(file: File, samples: FloatArray, sampleRateHz: Int) {
    val dataSize = samples.size * 2
    val byteRate = sampleRateHz * 2
    val out = java.io.BufferedOutputStream(java.io.FileOutputStream(file))
    out.use { os ->
        fun u32le(v: Int) { os.write(v and 0xFF); os.write((v ushr 8) and 0xFF); os.write((v ushr 16) and 0xFF); os.write((v ushr 24) and 0xFF) }
        fun u16le(v: Int) { os.write(v and 0xFF); os.write((v ushr 8) and 0xFF) }
        os.write("RIFF".toByteArray(Charsets.US_ASCII)); u32le(36 + dataSize)
        os.write("WAVE".toByteArray(Charsets.US_ASCII))
        os.write("fmt ".toByteArray(Charsets.US_ASCII)); u32le(16)
        u16le(1) // PCM
        u16le(1) // mono
        u32le(sampleRateHz)
        u32le(byteRate)
        u16le(2) // block align
        u16le(16) // bits per sample
        os.write("data".toByteArray(Charsets.US_ASCII)); u32le(dataSize)
        val buf = ByteArray(dataSize)
        var pos = 0
        for (s in samples) {
            val clamped = (s.coerceIn(-1f, 1f) * 32767f).toInt()
            buf[pos] = (clamped and 0xFF).toByte()
            buf[pos + 1] = ((clamped ushr 8) and 0xFF).toByte()
            pos += 2
        }
        os.write(buf)
    }
}

private class WavPcm(val samples: FloatArray, val sampleRateHz: Int, val channels: Int)

/** Minimal RIFF/WAVE parser: PCM16 only, mono or interleaved multi-channel (downmixed to mono). */
private fun readWavMonoFloat(file: File): WavPcm? {
    val bytes = file.readBytes()
    if (bytes.size < 44) return null
    fun u32(off: Int): Int = (bytes[off].toInt() and 0xFF) or
        ((bytes[off + 1].toInt() and 0xFF) shl 8) or
        ((bytes[off + 2].toInt() and 0xFF) shl 16) or
        ((bytes[off + 3].toInt() and 0xFF) shl 24)
    fun u16(off: Int): Int = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null

    var pos = 12
    var channels = 1
    var sampleRate = 16000
    var bitsPerSample = 16
    var dataOffset = -1
    var dataSize = 0
    while (pos + 8 <= bytes.size) {
        val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
        val chunkSize = u32(pos + 4)
        val chunkStart = pos + 8
        when (chunkId) {
            "fmt " -> {
                channels = u16(chunkStart + 2)
                sampleRate = u32(chunkStart + 4)
                bitsPerSample = u16(chunkStart + 14)
            }
            "data" -> {
                dataOffset = chunkStart
                dataSize = chunkSize
            }
        }
        pos = chunkStart + chunkSize + (chunkSize and 1) // chunks are word-aligned
    }
    if (dataOffset < 0 || bitsPerSample != 16 || channels < 1) return null
    val safeSize = minOf(dataSize, bytes.size - dataOffset)
    val frameCount = safeSize / (2 * channels)
    val mono = FloatArray(frameCount)
    var srcIdx = dataOffset
    for (i in 0 until frameCount) {
        var sum = 0
        repeat(channels) {
            val s = ((bytes[srcIdx + 1].toInt() shl 8) or (bytes[srcIdx].toInt() and 0xFF)).toShort()
            sum += s
            srcIdx += 2
        }
        mono[i] = (sum / channels) / 32768f
    }
    return WavPcm(mono, sampleRate, channels)
}
