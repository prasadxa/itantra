package org.itantra.tts.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
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
import org.itantra.tts.dsp.Fade
import org.itantra.tts.dsp.Gain
import org.itantra.tts.dsp.Pitch
import org.itantra.tts.dsp.Silence
import org.itantra.tts.dsp.Wsola
import org.itantra.tts.engine.FallbackTtsEngine

private const val TAG = "SpeechOutput"

/** Voice-note replay cache bounds — see the class doc and [replay]. */
private const val MAX_VOICE_NOTES = 20
private const val MAX_VOICE_NOTES_BYTES = 20L * 1024 * 1024

/** Bounded producer/consumer plumbing between synthesis and the AudioTrack writer — see the class
 * doc's "Producer/consumer" paragraph and [AudioChunkQueue]/[PreRollEstimator]. */
private const val QUEUE_CAPACITY_CHUNKS = 64
private const val PREROLL_POLL_MS = 5L
private const val PREROLL_MAX_WAIT_MS = 3_000L
private const val REBUFFER_TARGET_MS = 300L
private const val REBUFFER_MAX_WAIT_MS = 2_000L
private const val CHUNK_POLL_TIMEOUT_MS = 150L
private const val PRODUCER_JOIN_TIMEOUT_MS = 2_000L

/** 5-10ms range per spec; see [Fade]. */
private const val FADE_MS = 8

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
 * **Producer/consumer**: each [Job.Synthesize] runs synthesis on its own producer thread, which
 * streams PCM from [tts] one segment at a time, applies per-segment [TtsSegment.rate]/
 * [TtsSegment.volumeDb] DSP ([Wsola]/[Gain]) plus a short [Fade] fade-in at each segment/clause
 * join and after a pause, and pushes the result into a bounded [AudioChunkQueue]. The worker
 * thread (see [runLoop]) is the *consumer*: it writes queued chunks to the [AudioTrack], so
 * synthesis of later segments overlaps playback of earlier ones instead of the two serializing on
 * one thread (the original underrun root cause on Indic-Mio, RTF 1.0-1.46 on a Snapdragon 870 —
 * playback overtakes generation). [PreRollEstimator] decides how much to buffer before calling
 * `track.play()` (an adaptive pre-roll, not a fixed one), and if the queue still runs dry mid-
 * utterance (synthesis unexpectedly slow), the consumer pauses the track cleanly and rebuffers
 * rather than letting it click — see [playSynthesizeSegments].
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

    /** Adaptive pre-roll math (per (engine, lang) RTF/chars-per-second EMAs) — see its class doc. */
    private val preRollEstimator = PreRollEstimator()

    /** Queues [plan] for playback; ALERT jumps ahead of any queued/playing NORMAL message. See
     * [observeMetricsOnce] to also receive that message's [SpeechPlaybackMetrics] once it finishes. */
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
        metricsListeners.clear() // drop any listener for a job that will now never play/report
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
        val underrunsBefore = track.underrunCount
        val jobMetrics = JobMetrics()
        try {
            when (job) {
                // track.play() happens inside playSynthesizeSegments, after pre-roll buffering -
                // calling it eagerly here (as this used to) would start an empty track playing
                // immediately, which is itself an underrun.
                is Job.Synthesize -> playSynthesizeSegments(track, job, respectNormalControls = true, jobMetrics) { pcm ->
                    storeVoiceNote(job.id, pcm, tts.sampleRate, job.plan.priority)
                }
                is Job.Replay -> {
                    track.play()
                    playStoredPcm(track, job, respectNormalControls = true)
                }
            }
        } finally {
            currentNormalTrack = null
            val underrunDelta = (track.underrunCount - underrunsBefore).coerceAtLeast(0)
            releaseTrack(track)
            endPlayback()
            reportMetrics(job, underrunDelta, jobMetrics)
        }
    }

    private fun playAlert(job: Job) {
        val streamMaxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val prevVol = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, streamMaxVol, 0)

        val focusRequest = requestAlertFocus()
        val track = buildTrack(AudioAttributes.USAGE_ALARM)
        beginPlayback()
        val underrunsBefore = track.underrunCount
        val jobMetrics = JobMetrics()
        try {
            when (job) {
                is Job.Synthesize -> playSynthesizeSegments(track, job, respectNormalControls = false, jobMetrics) { pcm ->
                    storeVoiceNote(job.id, pcm, tts.sampleRate, job.plan.priority)
                }
                is Job.Replay -> {
                    track.play()
                    playStoredPcm(track, job, respectNormalControls = false)
                }
            }
        } finally {
            val underrunDelta = (track.underrunCount - underrunsBefore).coerceAtLeast(0)
            releaseTrack(track)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0)
            abandonAlertFocus(focusRequest)
            endPlayback()
            reportMetrics(job, underrunDelta, jobMetrics)
        }
    }

    /** Mutable per-job counters filled in by [playSynthesizeSegments]; zero for a [Job.Replay]
     * (no synthesis, so no pre-roll/rebuffer decisions are made for it). */
    private class JobMetrics {
        var preRollMs: Long = 0
        var rebufferPauses: Int = 0
    }

    private fun reportMetrics(job: Job, underrunDelta: Int, jobMetrics: JobMetrics) {
        Log.i(
            TAG,
            "playback done id=${job.id} underrunDelta=$underrunDelta preRollMs=${jobMetrics.preRollMs} " +
                "rebufferPauses=${jobMetrics.rebufferPauses}",
        )
        fireMetrics(SpeechPlaybackMetrics(job.id, underrunDelta, jobMetrics.preRollMs, jobMetrics.rebufferPauses))
    }

    /**
     * Producer/consumer playback of [job]'s segments (see the class doc). A dedicated producer
     * thread runs [tts] segment-by-segment, applies per-chunk DSP, and pushes the result into a
     * bounded [AudioChunkQueue]; this (the worker) thread is the consumer, writing queued chunks to
     * [track]. Synthesis of segment N+1 thus overlaps playback of segment N instead of the two
     * serializing on one thread.
     *
     * Before calling `track.play()`, waits for [PreRollEstimator] to consider enough buffered (or
     * for synthesis to finish first) — see [PreRollEstimator.targetPreRollMs]. If the queue still
     * empties out mid-utterance because synthesis fell further behind than estimated, pauses
     * [track] cleanly and rebuffers rather than letting it underrun audibly, and applies a short
     * [Fade] fade-in to the chunk that resumes playback so the pause/resume itself doesn't click.
     *
     * Accumulates the exact post-DSP (and, where applied, post-fade) PCM actually written to
     * [track] so [onComplete] can cache it as a [VoiceNote]. When [respectNormalControls] (i.e.
     * this is a NORMAL job, not an in-progress ALERT), honours [stopNormalRequested] and lets a
     * newly-queued ALERT queue-jump mid-stream, exactly as the original single-path implementation
     * did between segments (here, checked between every chunk, so ALERT pre-emption latency is
     * capped at [CHUNK_POLL_TIMEOUT_MS] instead of "however long the current segment takes").
     */
    private fun playSynthesizeSegments(
        track: AudioTrack,
        job: Job.Synthesize,
        respectNormalControls: Boolean,
        jobMetrics: JobMetrics,
        onComplete: (FloatArray) -> Unit,
    ) {
        val engineName = (tts as? FallbackTtsEngine)?.engineNameFor(job.plan.lang) ?: "unknown"
        val langCode = job.plan.lang.code
        val totalChars = job.plan.segments.sumOf { it.text.length }
        val preRollMs = preRollEstimator.targetPreRollMs(engineName, langCode, totalChars)
        val preRollSamples = (preRollMs * tts.sampleRate / 1000L).toInt()
        val rebufferTargetSamples = (REBUFFER_TARGET_MS * tts.sampleRate / 1000L).toInt()

        val queue = AudioChunkQueue(QUEUE_CAPACITY_CHUNKS)
        val accumulator = PcmAccumulator()

        fun jobShouldStop() = closed || (respectNormalControls && stopNormalRequested)

        val producer = thread(name = "SpeechOutput-synth-${job.id}", isDaemon = true) {
            var synthesizedSamples = 0
            var synthNanos = 0L
            runCatching {
                for (segment in job.plan.segments) {
                    if (jobShouldStop()) break
                    val silence = Silence.samples(segment.pauseBeforeMs, tts.sampleRate)
                    if (silence.isNotEmpty()) queue.put(silence)

                    var firstChunkOfSegment = true
                    val segStartNanos = System.nanoTime()
                    tts.synthesize(job.plan.lang, segment) { rawChunk ->
                        if (jobShouldStop()) return@synthesize
                        var chunk = rawChunk
                        // Bypassed at rate == 1.0 (the common case): WSOLA run per small streamed
                        // chunk rather than per whole segment already costs it cross-chunk context
                        // (see the class doc); skipping it entirely when there's nothing to time-
                        // scale avoids both that cost and any risk of it introducing its own clicks.
                        if (segment.rate != 1f) chunk = Wsola.changeRate(chunk, segment.rate, tts.sampleRate)
                        if (segment.pitchSemitones != 0f) chunk = Pitch.shiftSemitones(chunk, segment.pitchSemitones, tts.sampleRate)
                        if (segment.volumeDb != 0f) chunk = Gain.applyDb(chunk, segment.volumeDb)
                        if (firstChunkOfSegment) {
                            // Segment/clause join (and, when segment.pauseBeforeMs > 0, "after a
                            // pause" too): smooth the discontinuity instead of a click.
                            chunk = Fade.fadeIn(chunk, FADE_MS, tts.sampleRate)
                            firstChunkOfSegment = false
                        }
                        synthesizedSamples += chunk.size
                        queue.put(chunk)
                    }
                    synthNanos += System.nanoTime() - segStartNanos
                }
            }.onFailure { e ->
                Log.e(TAG, "synthesis failed for ${job.id}", e)
                queue.markProducerFailed()
            }
            queue.markProducerDone()
            if (synthesizedSamples > 0) {
                preRollEstimator.recordSample(
                    engineName, langCode, totalChars,
                    audioSeconds = synthesizedSamples / tts.sampleRate.toFloat(),
                    synthSeconds = synthNanos / 1_000_000_000f,
                )
            }
        }

        try {
            awaitBuffered(queue, preRollSamples, PREROLL_MAX_WAIT_MS)
            jobMetrics.preRollMs = queue.bufferedSamples.toLong() * 1000L / tts.sampleRate
            if (closed) return
            track.play()

            var playStartedFired = false
            var pendingResumeFade = false
            while (!closed) {
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

                val chunk = queue.poll(CHUNK_POLL_TIMEOUT_MS)
                if (chunk == null) {
                    if (queue.isProducerDone) break // whole utterance played
                    // Queue empty but synthesis still running: pause cleanly instead of an audible
                    // underrun click, then rebuffer before resuming.
                    track.pause()
                    jobMetrics.rebufferPauses++
                    Log.i(TAG, "rebuffer pause id=${job.id}: queue empty, synthesis still running")
                    awaitBuffered(queue, rebufferTargetSamples, REBUFFER_MAX_WAIT_MS)
                    if (closed) break
                    track.play()
                    pendingResumeFade = true
                    continue
                }

                val toWrite = if (pendingResumeFade) {
                    pendingResumeFade = false
                    Fade.fadeIn(chunk, FADE_MS, tts.sampleRate)
                } else {
                    chunk
                }
                writeBlocking(track, toWrite)
                accumulator.add(toWrite)
                if (!playStartedFired) {
                    playStartedFired = true
                    job.onPlayStarted(job.id, System.currentTimeMillis())
                }
            }
        } finally {
            // Unblock a producer possibly stuck in queue.put() (queue full) so it observes
            // jobShouldStop()/isProducerDone and exits instead of leaking a blocked thread.
            queue.clear()
            try {
                producer.join(PRODUCER_JOIN_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (!closed) onComplete(accumulator.toArray())
    }

    /** Blocks (polling) until [queue] has [targetSamples] buffered, synthesis finishes, [closed]
     * becomes true, or [maxWaitMs] elapses (safety cap against a stalled/abnormally slow producer
     * holding up playback indefinitely). */
    private fun awaitBuffered(queue: AudioChunkQueue, targetSamples: Int, maxWaitMs: Long) {
        if (targetSamples <= 0) return
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (!closed && queue.bufferedSamples < targetSamples && !queue.isProducerDone) {
            if (System.currentTimeMillis() >= deadline) break
            try {
                Thread.sleep(PREROLL_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    /** Re-plays already-synthesized PCM (see [replay]); no [tts] call, so no re-accumulation and no
     * pre-roll/rebuffer logic (the whole message is already in memory). */
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
        // >= 2x the platform minimum and >= ~400ms of float mono, whichever is larger - gives the
        // producer/consumer pipeline (see the class doc) enough hardware-side headroom that a
        // brief writer stall doesn't underrun on its own; PreRollEstimator/AudioChunkQueue handle
        // the "synthesis genuinely can't keep up" case on top of this.
        val bufferBytes = maxOf(minBufferBytes * 2, sampleRate * 400 / 1000 * 4)
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

    private fun fireMetrics(metrics: SpeechPlaybackMetrics) {
        metricsListeners.remove(metrics.id)?.invoke(metrics)
    }

    companion object {
        // Keyed by job id, instance-independent: debug tooling (`DebugCommandReceiver`) only holds
        // a `SpeechOutputPort` reference (Orchestrator's small playback contract, deliberately not
        // widened with synthesis-internal metrics), so it can't call an instance method to receive
        // these. Registering here instead - before enqueueing the same id via the port as usual -
        // lets it observe a message's SpeechPlaybackMetrics without changing that contract. Entries
        // are one-shot (removed by [fireMetrics]) and never accumulate across a run: a request that
        // never gets its own job played (e.g. enqueue() on a closed SpeechOutput) simply never fires,
        // and the concrete id is unique per call (UUID in practice), so this can't leak across
        // requests for the same id, only for one that's genuinely dropped and never replayed.
        private val metricsListeners = ConcurrentHashMap<String, (SpeechPlaybackMetrics) -> Unit>()

        /** Registers a one-shot listener for [id]'s [SpeechPlaybackMetrics], fired once playback of
         * that message (queued via [enqueue] with the same [id]) finishes. Call before [enqueue]. */
        fun observeMetricsOnce(id: String, listener: (SpeechPlaybackMetrics) -> Unit) {
            metricsListeners[id] = listener
        }
    }
}

/**
 * Per-message playback metrics gathered by [SpeechOutput], surfaced via
 * [SpeechOutput.observeMetricsOnce] — used by `DebugCommandReceiver`'s `DEBUG_SPEAK` hook and
 * SpeechOutput's own "normal playback logs" (see [SpeechOutput.reportMetrics]).
 */
data class SpeechPlaybackMetrics(
    val id: String,
    /** `AudioTrack.getUnderrunCount()` delta over this message's playback; target 0. */
    val underrunCountDelta: Int,
    /** Buffered-audio-before-`track.play()` actually used, in ms — see [PreRollEstimator]. */
    val preRollMs: Long,
    /** Times playback was paused mid-utterance to rebuffer because synthesis fell behind; target 0. */
    val rebufferPauses: Int,
)
