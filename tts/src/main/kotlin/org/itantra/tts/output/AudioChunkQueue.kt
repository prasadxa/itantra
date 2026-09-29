package org.itantra.tts.output

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded producer/consumer queue of post-DSP PCM chunks used to decouple TTS synthesis (producer
 * thread) from `AudioTrack` writing (consumer/writer thread) — see [SpeechOutput]'s class doc,
 * "Producer/consumer". Pure Kotlin, no Android dependency, so it's directly unit-testable (see
 * `AudioChunkQueueTest`).
 *
 * Bounded by chunk *count* ([capacity]), not bytes/duration: callers keep individual chunks to a
 * fraction of a second (matching each engine's native streaming granularity), so the count bound
 * gives ample headroom (tens of seconds) without needing to know sample rate up front. [bufferedSamples]
 * is the precise duration signal the writer actually uses for pre-roll/rebuffer decisions.
 */
class AudioChunkQueue(private val capacity: Int = 64) {
    private val queue = ArrayBlockingQueue<FloatArray>(capacity)
    private val bufferedSamplesRef = AtomicInteger(0)

    @Volatile private var producerDone = false
    @Volatile private var producerFailed = false

    /** Total samples currently queued but not yet [poll]ed. */
    val bufferedSamples: Int get() = bufferedSamplesRef.get()

    /** True once [markProducerDone] (or [markProducerFailed]) has been called; the consumer should
     * drain whatever remains and then stop, rather than waiting for more. */
    val isProducerDone: Boolean get() = producerDone

    /** True if the producer ended via [markProducerFailed] (synthesis threw). */
    val didProducerFail: Boolean get() = producerFailed

    /** Enqueues [chunk], blocking (backpressure) if [capacity] chunks are already buffered. No-op
     * for an empty chunk. */
    fun put(chunk: FloatArray) {
        if (chunk.isEmpty()) return
        queue.put(chunk)
        bufferedSamplesRef.addAndGet(chunk.size)
    }

    /** Signals no more chunks will be [put] — synthesis finished normally. */
    fun markProducerDone() {
        producerDone = true
    }

    /** Signals synthesis failed; also implies [markProducerDone] so the consumer stops waiting. */
    fun markProducerFailed() {
        producerFailed = true
        producerDone = true
    }

    /** Non-blocking poll; null if nothing buffered right now. */
    fun poll(): FloatArray? = takeAndAccount(queue.poll())

    /** Blocking poll, waiting up to [timeoutMs] for a chunk; null on timeout. */
    fun poll(timeoutMs: Long): FloatArray? = takeAndAccount(queue.poll(timeoutMs, TimeUnit.MILLISECONDS))

    private fun takeAndAccount(chunk: FloatArray?): FloatArray? {
        if (chunk != null) bufferedSamplesRef.addAndGet(-chunk.size)
        return chunk
    }

    /** Drops everything buffered (used when a job is being abandoned, e.g. `stopNormal()`) — also
     * frees space for a producer possibly blocked in [put] so it can observe a stop signal and exit
     * instead of blocking forever. */
    fun clear() {
        queue.clear()
        bufferedSamplesRef.set(0)
    }
}
