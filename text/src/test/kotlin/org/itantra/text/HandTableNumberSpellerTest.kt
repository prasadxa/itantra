package org.itantra.text

import org.itantra.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandTableNumberSpellerTest {
    private val speller = HandTableNumberSpeller()

    @Test
    fun `every language has a non-blank word for 0 through 99`() {
        for (lang in Lang.entries) {
            for (n in 0..99) {
                val word = speller.spellCardinal(n.toLong(), lang)
                assertTrue("$lang $n was blank", word.isNotBlank())
            }
        }
    }

    @Test
    fun `english cardinals use Indian grouping`() {
        assertEquals("zero", speller.spellCardinal(0, Lang.EN))
        assertEquals("twenty one", speller.spellCardinal(21, Lang.EN))
        assertEquals("ninety nine", speller.spellCardinal(99, Lang.EN))
        assertEquals("one hundred", speller.spellCardinal(100, Lang.EN))
        assertEquals("one thousand two hundred thirty four", speller.spellCardinal(1234, Lang.EN))
        assertEquals("one lakh twenty thousand", speller.spellCardinal(120_000, Lang.EN))
        assertEquals("one crore", speller.spellCardinal(10_000_000, Lang.EN))
        assertEquals("two crore fifty lakh", speller.spellCardinal(25_000_000, Lang.EN))
    }

    @Test
    fun `hindi cardinals`() {
        assertEquals("शून्य", speller.spellCardinal(0, Lang.HI))
        assertEquals("इक्कीस", speller.spellCardinal(21, Lang.HI))
        assertEquals("निन्यानवे", speller.spellCardinal(99, Lang.HI))
        assertEquals("एक सौ", speller.spellCardinal(100, Lang.HI))
        assertEquals("एक लाख बीस हज़ार", speller.spellCardinal(120_000, Lang.HI))
        assertEquals("एक करोड़", speller.spellCardinal(10_000_000, Lang.HI))
    }

    @Test
    fun `marathi gujarati bengali spot checks`() {
        assertEquals("एकवीस", speller.spellCardinal(21, Lang.MR))
        assertEquals("નવ્વાણું", speller.spellCardinal(99, Lang.GU))
        assertEquals("একুশ", speller.spellCardinal(21, Lang.BN))
    }

    @Test
    fun `dravidian and odia compositional 21 through 99`() {
        assertEquals("ಇಪ್ಪತ್ತು", speller.spellCardinal(20, Lang.KN))
        assertEquals("ಇಪ್ಪತ್ತು ಒಂದು", speller.spellCardinal(21, Lang.KN))
        assertEquals("தொண்ணூறு ஒன்பது", speller.spellCardinal(99, Lang.TA))
        assertEquals("ఇరవై ఒకటి", speller.spellCardinal(21, Lang.TE))
        assertEquals("କୋଡ଼ିଏ ଏକ", speller.spellCardinal(21, Lang.OR))
    }

    @Test
    fun `digitWord basic`() {
        assertEquals("five", speller.digitWord(5, Lang.EN))
        assertEquals("पांच", speller.digitWord(5, Lang.HI))
    }

    @Test
    fun `negative numbers get a minus prefix and zero is exact`() {
        assertEquals("minus five", speller.spellCardinal(-5, Lang.EN))
        assertFalse(speller.spellCardinal(0, Lang.EN).contains("minus"))
    }
}
