package org.itantra.stt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Process

/** Which acoustic path the mic is opened for. */
enum class CaptureMode { PTT, PHONE }

/**
 * Captures 16 kHz mono PCM16 audio from the mic and delivers it as float samples in [-1, 1]
 * on a dedicated background thread, in ~20 ms chunks (320 samples).
 *
 * [CaptureMode.PTT] uses `VOICE_RECOGNITION` (push-to-talk, mic only while held).
 * [CaptureMode.PHONE] uses `VOICE_COMMUNICATION` and enables AEC/NS when the device supports
 * them (mic always on, echo cancelled against concurrent TTS playback).
 *
 * Requires `RECORD_AUDIO` (declared in this module's manifest).
 */
class MicCapture(private val context: Context) {

    @Volatile
    var isRunning: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var thread: Thread? = null

    fun start(mode: CaptureMode, onSamples: (FloatArray) -> Unit) {
        if (isRunning) return

        val audioSource = when (mode) {
            CaptureMode.PTT -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            CaptureMode.PHONE -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBufferBytes > 0) { "AudioRecord.getMinBufferSize failed: $minBufferBytes" }
        // A few chunks deep so the reader thread never underruns.
        val bufferBytes = maxOf(minBufferBytes, CHUNK_SAMPLES * 2 * 4)

        val record = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize (source=$audioSource)")
        }

        if (mode == CaptureMode.PHONE) {
            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        }

        audioRecord = record
        isRunning = true
        record.startRecording()

        val t = Thread({ readLoop(record, onSamples) }, "MicCapture")
        thread = t
        t.start()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        thread?.join(500)
        thread = null

        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null

        audioRecord?.let { rec ->
            runCatching { rec.stop() }
            rec.release()
        }
        audioRecord = null
    }

    private fun readLoop(record: AudioRecord, onSamples: (FloatArray) -> Unit) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val pcm = ShortArray(CHUNK_SAMPLES)
        while (isRunning) {
            val n = record.read(pcm, 0, pcm.size)
            if (n > 0) {
                onSamples(pcm16ToFloat(pcm, n))
            } else if (n < 0) {
                // AudioRecord error code (ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT, ...).
                break
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** ~20 ms at 16 kHz. */
        const val CHUNK_SAMPLES = SAMPLE_RATE / 50

        /** PCM16 `[-32768, 32767]` -> float `[-1, 1]`. Pure, unit-testable. */
        fun pcm16ToFloat(pcm: ShortArray, count: Int = pcm.size): FloatArray {
            val out = FloatArray(count)
            for (i in 0 until count) {
                out[i] = pcm[i] / 32768f
            }
            return out
        }
    }
}
