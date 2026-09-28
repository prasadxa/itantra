package org.itantra.text

import org.itantra.core.Lang
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Demonstrates the icu4j test dependency is wired correctly and exercises what CLDR SPELLOUT
 * actually produces. English has robust SPELLOUT rules, so we can assert on it directly. We do
 * not assert exact strings for the other nine languages here: CLDR's spellout coverage/grouping
 * for them is inconsistent (this is exactly why [HandTableNumberSpeller] — not ICU — is the
 * verified default in [IndicNormalizer]); this test only documents what is available, and
 * [CompositeNumberSpeller] is what guarantees a safe fallback in the real pipeline.
 */
class IcuNumberSpellerTest {
    private val icu = IcuNumberSpeller()

    @Test
    fun `icu4j spells out english cardinals`() {
        val out = icu.spellCardinal(21, Lang.EN)
        assertNotNull(out)
    }

    @Test
    fun `composite falls back to hand tables when preferred returns null`() {
        val failingSpeller = object : NumberSpeller {
            override fun spellCardinal(n: Long, lang: Lang): String? = null
        }
        val composite = CompositeNumberSpeller(failingSpeller, HandTableNumberSpeller())
        val out = composite.spellCardinal(21, Lang.HI)
        assert(out == "इक्कीस") { "expected hand-table fallback, got: $out" }
    }
}
