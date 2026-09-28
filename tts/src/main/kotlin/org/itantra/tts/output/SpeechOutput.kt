package org.itantra.tts.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.itantra.core.Priority
import org.itantra.core.SynthesisPlan
import org.itantra.core.TtsEngine
import org.itantra.core.TtsSegment
import org.itantra.tts.dsp.Gain
import org.itantra.tts.dsp.Pitch
import org.itantra.tts.dsp.Silence
import org.itantra.tts.dsp.Wsola

private const val TAG = "SpeechOutput"

/** Voice-note replay cache bounds — see the class doc and [replay]. */
private const val MAX_VOICE_NOTES = 20
private const val MAX_VOICE_NOTES_BYTES = 20L * 1024 * 1024

/**
 * Drives [tts] on a worker thread and streams the resulting PCM to an [AudioTrack]
 * (`MODE_STREAM`, float PCM, [tts]'s sample rate).
 *
 * NORMAL messages are queued FIFO and played with `USAGE_MEDIA` (`USAGE_VOICE_COMMUNICATION`
 * when `phoneMode`). ALERT messages queue-jump: as soon as one is enqueued, the current NORMAL
 * track is paused, the ALERT plays to completion at `USAGE_ALARM` (`STREAM_ALARM` volume raised
 * to max for its duration, restored after) with `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` requested
 * and focus loss ignored, then NORMAL playback resumes. [stopNormal] never touches an in-flight
 * or queued ALERT.
 *
 * Per-segment [TtsSegment.rate]/[TtsSegment.volumeDb] DSP ([Wsola]/[Gain]) runs on each streamed
 * PCM chunk as it arrives from [tts], not buffered for the whole segment — this keeps first-chunk
 * latency low (the metric that matters, see `docs/design.md`) at the cost of WSOLA's
 * cross-correlation search only ever seeing one ~50-token chunk of context at a time.
 *
 * Also retains the post-DSP PCM of the last [MAX_VOICE_NOTES] received messages (bounded to
 * [MAX_VOICE_NOTES_BYTES], oldest evicted first) so [replay] can re-play a message — NORMAL or
 * ALERT — without re-running TTS.
 */
class SpeechOutput(private val context: Context, private val tts: TtsEngine) {

    private sealed interface Job {
        val id: String
        val priority: Priority
        val onPlayStarted: (id: String, epochMs: Long) -> Unit

        /** Fresh synthesis via [tts]; its PCM is cached afterwards as a [VoiceNote] for [replay]. */
        data class Synthesize(
            override val id: String,
            val plan: SynthesisPlan,
            val phoneMode: Boolean,
            override val onPlayStarted: (id: String, epochMs: Long) -> Unit,
        ) : Job {
            override val priority get() = plan.priority
        }

        /** Re-plays already-synthesized PCM from [voiceNotes]; no [tts] call. */
        data class Replay(
            override val id: String,
            val note: VoiceNote,
            override val onPlayStarted: (id: String, epochMs: Long) -> Unit,
        ) : Job {
            override val priority get() = note.priority
        }
    }

    /** Retained synthesized audio for a received message, so [replay] doesn't need to re-run TTS. */
    private class VoiceNote(val samples: FloatArray, val sampleRate: Int, val priority: Priority) {
        val durationSeconds: Float get() = samples.size / sampleRate.toFloat()
        val bytes: Long get() = samples.size.toLong() * 4
    }

    private val normalQueue = LinkedBlockingDeque<Job>()
    private val alertQueue = LinkedBlockingDeque<Job>()

    private val voiceNotesLock = Any()

    // Insertion-ordered; oldest evicted first once over MAX_VOICE_NOTES or MAX_VOICE_NOTES_BYTES.
    // Guarded by [voiceNotesLock] since it's written from the worker thread and read from
    // whichever thread calls [replay]/[durationSeconds]/[voiceNoteBytes] (typically the UI).
    private val voiceNotes = LinkedHashMap<String, VoiceNote>()

    /** Fired on the worker thread once a received message's audio has been fully cached for replay. */
    var onVoiceNoteStored: ((id: String, durationSeconds: Float) -> Unit)? = null

    @Volatile private var closed = false
    @Volatile private var stopNormalRequested = false
    @Volatile private var currentNormalTrack: AudioTrack? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Refcounted (not boolean) because playAlert() can run nested inside playNormal()'s
    // queue-jump drain loop: without a count, the nested alert's `finally` would flip isPlaying
    // to false while the outer normal job is still about to resume playback.
    private val activePlaybacks = AtomicInteger(0)
    private val _isPlaying = MutableStateFlow(false)

    /** True while any audio (NORMAL or ALERT) is being written to a track. Used by the caller to
     * half-duplex-gate the mic so we never transcribe our own TTS output ("hearing itself"). */
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private fun beginPlayback() {
        if (activePlaybacks.getAndIncrement() == 0) _isPlaying.value = true
    }

    private fun endPlayback() {
        if (activePlaybacks.decrementAndGet() == 0) _isPlaying.value = false
    }

    private val worker = thread(name = "SpeechOutput", isDaemon = true) { runLoop() }

    /** Queues [plan] for playback; ALERT jumps ahead of any queued/playing NORMAL message. */
    fun enqueue(id: String, plan: SynthesisPlan, phoneMode: Boolean, onPlayStarted: (id: String, epochMs: Long) -> Unit) {
        if (closed) return
        val job = Job.Synthesize(id, plan, phoneMode, onPlayStarted)
        if (plan.priority == Priority.ALERT) alertQueue.put(job) else normalQueue.put(job)
    }

    /**
     * Re-plays the cached audio of a previously received message [id] (last 20, LRU, see
     * [voiceNotes]) without re-running TTS. ALERT notes queue-jump and use the same alarm path
     * (max STREAM_ALARM volume, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE) as their original playback.
     * Returns false if [id] is no longer cached (evicted, never spoken, or engine unavailable).
     */
    fun replay(id: String, onPlayStarted: (id: String, epochMs: Long) -> Unit = { _, _ -> }): Boolean {
        if (closed) return false
        val note = synchronized(voiceNotesLock) { voiceNotes[id] } ?: return false
        val job = Job.Replay(id, note, onPlayStarted)
        if (note.priority == Priority.ALERT) alertQueue.put(job) else normalQueue.put(job)
        return true
    }

    /** Duration of the cached voice note for [id] in seconds, or null if not (or no longer) cached. */
    fun durationSeconds(id: String): Float? = synchronized(voiceNotesLock) { voiceNotes[id]?.durationSeconds }

    /** Total bytes of PCM currently retained for replay (for the Metrics screen / diagnostics). */
    fun voiceNoteBytes(): Long = synchronized(voiceNotesLock) { voiceNotes.values.sumOf { it.bytes } }

    private fun storeVoiceNote(id: String, samples: FloatArray, sampleRate: Int, priority: Priority) {
        if (samples.isEmpty() || closed) return
        val note = VoiceNote(samples, sampleRate, priority)
        synchronized(voiceNotesLock) {
            voiceNotes.remove(id)
            voiceNotes[id] = note
            while (voiceNotes.size > MAX_VOICE_NOTES || voiceNotes.values.sumOf { it.bytes } > MAX_VOICE_NOTES_BYTES) {
                val oldest = voiceNotes.keys.firstOrNull() ?: break
                voiceNotes.remove(oldest)
            }
        }
        onVoiceNoteStored?.invoke(id, note.durationSeconds)
    }

    /** Stops/clears NORMAL playback only; never affects an ALERT. */
    fun stopNormal() {
        stopNormalRequested = true
        normalQueue.clear()
        currentNormalTrack?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stopNormal: track already released", e)
            }
        }
    }

    fun close() {
        closed = true
        normalQueue.clear()
        alertQueue.clear()
        synchronized(voiceNotesLock) { voiceNotes.clear() }
        _isPlaying.value = false
        worker.interrupt()
        try {
            tts.close()
        } catch (e: Exception) {
            Log.w(TAG, "close: tts.close() threw", e)
        }
    }

    private fun runLoop() {
        while (!closed) {
            val alertJob = alertQueue.poll()
            if (alertJob != null) {
                runCatching { playAlert(alertJob) }.onFailure { Log.e(TAG, "ALERT playback failed", it) }
                continue
            }
            val normalJob = try {
                normalQueue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            stopNormalRequested = false
            runCatching { playNormal(normalJob) }.onFailure { Log.e(TAG, "NORMAL playback failed", it) }
        }
    }

    private fun playNormal(job: Job) {
        val phoneMode = (job as? Job.Synthesize)?.phoneMode ?: false
        val usage = if (phoneMode) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val track = buildTrack(usage)
        currentNormalTrack = track
        beginPlayback()
        try {
            track.play()
            when (job) {
                is Job.Synthesize -> playSynthesizeSegments(track, job, respectNormalControls = true) { pcm ->
                    storeVoiceNote(job.id, pcm, tts.sampleRate, job.plan.priority)
                }
                is Job.Replay -> playStoredPcm(track, job, respectNormalControls = true)
            }
        } finally {
            currentNormalTrack = null
            releaseTrack(track)
            endPlayback()
        }
    }

    private fun playAlert(job: Job) {
        val streamMaxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val prevVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, streamMaxVol, 0)

        val focusRequest = requestAlertFocus()
        val track = buildTrack(AudioAttributes.USAGE_ALARM)
        beginPlayback()
        try {
            track.play()
            when (job) {
                is Job.Synthesize -> playSynthesizeSegments(track, job, respectNormalControls = false) { pcm ->
                    storeVoiceNote(job.id, pcm, tts.sampleRate, job.plan.priority)
                }
                is Job.Replay -> playStoredPcm(track, job, respectNormalControls = false)
            }
        } finally {
            releaseTrack(track)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
            abandonAlertFocus(focusRequest)
            endPlayback()
        }
    }

    /**
     * Plays [job]'s segments through [tts], accumulating the post-DSP PCM actually written to
     * [track] so [onComplete] can cache it as a [VoiceNote]. When [respectNormalControls] (i.e.
     * this is a NORMAL job, not an in-progress ALERT), honours [stopNormalRequested] and lets a
     * newly-queued ALERT queue-jump between segments, exactly as the original single-path
     * implementation did.
     */
    private fun playSynthesizeSegments(
        track: AudioTrack,
        job: Job.Synthesize,
        respectNormalControls: Boolean,
        onComplete: (FloatArray) -> Unit,
    ) {
        var playStartedFired = false
        val accumulator = PcmAccumulator()
        for (segment in job.plan.segments) {
            if (closed) break
            if (respectNormalControls && stopNormalRequested) break
            if (respectNormalControls && alertQueue.isNotEmpty()) {
                track.pause()
                while (!closed) {
                    val alertJob = alertQueue.poll() ?: break
                    runCatching { playAlert(alertJob) }.onFailure { Log.e(TAG, "ALERT playback failed", it) }
                }
                if (stopNormalRequested || closed) break
                track.play()
            }
            playSegment(track, job.plan, segment, accumulator) {
                if (!playStartedFired) {
                    playStartedFired = true
                    job.onPlayStarted(job.id, System.currentTimeMillis())
                }
            }
        }
        if (!closed) onComplete(accumulator.toArray())
    }

    /** Re-plays already-synthesized PCM (see [replay]); no [tts] call, so no re-accumulation. */
    private fun playStoredPcm(track: AudioTrack, job: Job.Replay, respectNormalControls: Boolean) {
        val samples = job.note.samples
        var fired = false
        var offset = 0
        val chunkSamples = maxOf(job.note.sampleRate / 4, 1) // ~250ms per write, matches synth chunking
        while (offset < samples.size && !closed) {
            if (respectNormalControls && stopNormalRequested) break
            if (respectNormalControls && alertQueue.isNotEmpty()) {
                track.pause()
                while (!closed) {
                    val alertJob = alertQueue.poll() ?: break
                    runCatching { playAlert(alertJob) }.onFailure { Log.e(TAG, "ALERT playback failed", it) }
                }
                if (stopNormalRequested || closed) break
                track.play()
            }
            val end = minOf(offset + chunkSamples, samples.size)
            writeBlocking(track, samples.copyOfRange(offset, end))
            if (!fired) {
                fired = true
                job.onPlayStarted(job.id, System.currentTimeMillis())
            }
            offset = end
        }
    }

    private fun playSegment(track: AudioTrack, plan: SynthesisPlan, segment: TtsSegment, accumulator: PcmAccumulator, onFirstChunk: () -> Unit) {
        val silence = Silence.samples(segment.pauseBeforeMs, tts.sampleRate)
        if (silence.isNotEmpty()) writeBlocking(track, silence)

        var firstChunk = true
        tts.synthesize(plan.lang, segment) { rawChunk ->
            if (closed) return@synthesize
            var chunk = rawChunk
            if (segment.rate != 1f) chunk = Wsola.changeRate(chunk, segment.rate, tts.sampleRate)
            if (segment.pitchSemitones != 0f) chunk = Pitch.shiftSemitones(chunk, segment.pitchSemitones, tts.sampleRate)
            if (segment.volumeDb != 0f) chunk = Gain.applyDb(chunk, segment.volumeDb)
            writeBlocking(track, chunk)
            accumulator.add(chunk)
            if (firstChunk) {
                firstChunk = false
                onFirstChunk()
            }
        }
    }

    /** Growable float buffer for the PCM actually written during one synthesis job, cheaper than
     * an ArrayList<Float> (no boxing) — flattened once into a [VoiceNote] at job end. */
    private class PcmAccumulator {
        private val chunks = ArrayList<FloatArray>()
        private var total = 0
        fun add(chunk: FloatArray) {
            if (chunk.isEmpty()) return
            chunks.add(chunk)
            total += chunk.size
        }
        fun toArray(): FloatArray {
            val out = FloatArray(total)
            var pos = 0
            for (c in chunks) {
                c.copyInto(out, pos)
                pos += c.size
            }
            return out
        }
    }

    private fun writeBlocking(track: AudioTrack, samples: FloatArray) {
        if (samples.isEmpty()) return
        var offset = 0
        while (offset < samples.size && !closed) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    private fun buildTrack(usage: Int): AudioTrack {
        val sampleRate = tts.sampleRate
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = maxOf(minBufferBytes, sampleRate / 2 * 4) // >= ~0.5s of float mono
        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "releaseTrack: stop() on non-playing track", e)
        }
        track.release()
    }

    private fun requestAlertFocus(): android.media.AudioFocusRequest {
        // Ignore focus loss entirely (ALERT is "not interruptible" per spec); the listener only
        // exists because requestAudioFocus() requires one.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { /* ignored: ALERT is not interruptible */ }
            .setWillPauseWhenDucked(false)
            .build()
        audioManager.requestAudioFocus(request)
        return request
    }

    private fun abandonAlertFocus(request: android.media.AudioFocusRequest) {
        audioManager.abandonAudioFocusRequest(request)
    }
}
