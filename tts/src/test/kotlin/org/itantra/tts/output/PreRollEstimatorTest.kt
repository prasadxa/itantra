package org.itantra.tts.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRollEstimatorTest {

    @Test
    fun `seeds rtf from docs design md - mio 1_3, vits 0_7`() {
        val estimator = PreRollEstimator()
        assertEquals(1.3f, estimator.rtf("mio", "hi"), 1e-6f)
        assertEquals(0.7f, estimator.rtf("vits", "ta"), 1e-6f)
    }

    @Test
    fun `unknown engine name defaults to the slower (mio) seed - conservative`() {
        val estimator = PreRollEstimator()
        assertEquals(PreRollEstimator.SEED_RTF_MIO, estimator.rtf("unknown", "hi"), 1e-6f)
    }

    @Test
    fun `default chars-per-second is in the 12-15 spec range`() {
        val estimator = PreRollEstimator()
        val cps = estimator.charsPerSecond("mio", "hi")
        assertTrue("expected 12-15, got $cps", cps in 12f..15f)
    }

    @Test
    fun `estimateDurationMs is text length over chars-per-second`() {
        val estimator = PreRollEstimator()
        val cps = PreRollEstimator.DEFAULT_CHARS_PER_SECOND
        val chars = 100
        val expectedMs = (chars / cps * 1000).toLong()
        assertEquals(expectedMs, estimator.estimateDurationMs("mio", "hi", chars))
    }

    @Test
    fun `estimateDurationMs of empty text is zero`() {
        val estimator = PreRollEstimator()
        assertEquals(0L, estimator.estimateDurationMs("mio", "hi", 0))
    }

    @Test
    fun `targetPreRollMs never goes below the 250ms floor`() {
        val estimator = PreRollEstimator()
        // Very fast (rtf << 1) engine and tiny text - the formula's own value would be far under
        // the floor, so the floor must win.
        estimator.recordSample("vits", "ta", textChars = 5, audioSeconds = 1f, synthSeconds = 0.05f)
        val preRoll = estimator.targetPreRollMs("vits", "ta", 5)
        assertEquals(PreRollEstimator.MIN_PREROLL_MS, preRoll)
    }

    @Test
    fun `targetPreRollMs at rtf less than or equal to 1 collapses to floor plus margin`() {
        val estimator = PreRollEstimator()
        // rtf == 1: deficit factor is exactly 0, so pre-roll should be max(floor, 0 + margin).
        estimator.recordSample("vits", "ta", textChars = 130, audioSeconds = 10f, synthSeconds = 10f)
        val preRoll = estimator.targetPreRollMs("vits", "ta", 130)
        assertEquals(maxOf(PreRollEstimator.MIN_PREROLL_MS, PreRollEstimator.MARGIN_MS), preRoll)
    }

    @Test
    fun `targetPreRollMs grows with rtf above 1 (slower synthesis needs more buffer)`() {
        val estimator = PreRollEstimator()
        // Force the RTF EMA hard towards 2.0 with repeated identical samples (avoids depending on
        // the exact alpha/seed blend - only that "much slower than real time" ends up > seed's 1.3).
        repeat(20) { estimator.recordSample("mio", "hi", textChars = 130, audioSeconds = 10f, synthSeconds = 20f) }
        val slow = estimator.targetPreRollMs("mio", "hi", 130)

        val fastEstimator = PreRollEstimator()
        repeat(20) { fastEstimator.recordSample("mio", "hi", textChars = 130, audioSeconds = 10f, synthSeconds = 5f) }
        val fast = fastEstimator.targetPreRollMs("mio", "hi", 130)

        assertTrue("slower synthesis should need a longer pre-roll ($slow vs $fast)", slow > fast)
    }

    @Test
    fun `targetPreRollMs formula matches spec - max(250, estDuration times max(0, 1-1-rtf) + 200)`() {
        val estimator = PreRollEstimator()
        // Pin both EMAs to known values with one large sample (alpha=1 not available, so pin via a
        // custom-alpha estimator instead, to make the arithmetic exact).
        val pinned = PreRollEstimator(alpha = 1f)
        pinned.recordSample("mio", "hi", textChars = 100, audioSeconds = 10f, synthSeconds = 13f) // rtf = 1.3, cps = 10
        val expectedEstDurationMs = (100 / 10f * 1000).toLong() // 10_000ms
        val expectedDeficit = (1f - 1f / 1.3f).coerceAtLeast(0f)
        val expected = maxOf(250L, (expectedEstDurationMs * expectedDeficit).toLong() + 200L)
        assertEquals(expected, pinned.targetPreRollMs("mio", "hi", 100))
    }

    @Test
    fun `recordSample with non-positive audioSeconds is ignored`() {
        val estimator = PreRollEstimator()
        val before = estimator.rtf("mio", "hi")
        estimator.recordSample("mio", "hi", textChars = 50, audioSeconds = 0f, synthSeconds = 5f)
        assertEquals(before, estimator.rtf("mio", "hi"), 1e-6f)
    }

    @Test
    fun `engine and lang keys are independent`() {
        val estimator = PreRollEstimator()
        estimator.recordSample("mio", "hi", textChars = 100, audioSeconds = 10f, synthSeconds = 30f)
        // A different (engine, lang) key must still read back the untouched seed.
        assertEquals(PreRollEstimator.SEED_RTF_MIO, estimator.rtf("mio", "gu"), 1e-6f)
        assertEquals(PreRollEstimator.SEED_RTF_VITS, estimator.rtf("vits", "hi"), 1e-6f)
    }
}
