package org.itantra.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.UUID
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.TtsEngine
import org.itantra.core.VoiceMessage

private const val TAG = "DebugCommandReceiver"
private const val ACTION_DEBUG_STT_WAV = "org.itantra.DEBUG_STT_WAV"
private const val ACTION_DEBUG_SPEAK = "org.itantra.DEBUG_SPEAK"

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

    /** Runs the receive path locally: TextPipeline -> TTS -> SpeechOutput, as if a Frame.Msg arrived. */
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
        speechOutput.enqueue(message.id, plan, phoneMode = false) { id, playStartedEpochMs ->
            Metrics.log(
                "event" to "debug_speak", "id" to id, "text" to text, "lang" to lang.code,
                "alert" to alert, "ssml" to ssml, "segments" to plan.segments.size,
                "engine" to engineUsed, "synthesisRtf" to rtf, "synthMs" to synthMs,
                "audioSeconds" to audioSeconds, "timeToFirstAudioMs" to (playStartedEpochMs - t0),
            )
        }
    }

    private fun probeEngineName(ttsEngine: TtsEngine, lang: Lang): String =
        (ttsEngine as? org.itantra.tts.engine.FallbackTtsEngine)?.engineNameFor(lang) ?: "unknown"
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
