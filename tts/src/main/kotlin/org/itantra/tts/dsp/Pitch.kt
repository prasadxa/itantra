package org.itantra.tts.dsp

/**
 * [org.itantra.core.TtsSegment.pitchSemitones] is a documented no-op for now: a correct
 * pitch shift independent of [Wsola]'s rate change needs a phase vocoder (or resampling the
 * WSOLA-stretched output back to the original duration, which reintroduces the rate change we
 * just removed) — out of scope for this pass. [SpeechOutput] calls [shiftSemitones] so the hook
 * exists once someone implements it; today it always returns [samples] unchanged.
 */
object Pitch {
    fun shiftSemitones(samples: FloatArray, semitones: Float, sampleRate: Int): FloatArray {
        // No-op by design; see class doc.
        return samples
    }
}
