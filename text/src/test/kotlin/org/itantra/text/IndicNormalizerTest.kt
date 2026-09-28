package org.itantra.text

import org.itantra.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicNormalizerTest {
    // Deterministic: bypass CompositeNumberSpeller's android.icu attempt for speed/clarity.
    private val n = IndicNormalizer(HandTableNumberSpeller())

    @Test
    fun `plain cardinal numbers per language`() {
        assertEquals("I have twenty one apples", n.normalize("I have 21 apples", Lang.EN))
        assertEquals("मेरे पास इक्कीस सेब हैं", n.normalize("मेरे पास 21 सेब हैं", Lang.HI))
        assertEquals("एकवीस", n.normalize("21", Lang.MR))
        assertEquals("નવ્વાણું", n.normalize("99", Lang.GU))
        assertEquals("একুশ", n.normalize("21", Lang.BN))
    }

    @Test
    fun `native script digits are read as numbers`() {
        // Devanagari 21 -> हिन्दी इक्कीस
        assertEquals("इक्कीस", n.normalize("२१", Lang.HI))
        // Tamil digits 21 -> tens + unit composed form
        assertEquals("இருபது ஒன்று", n.normalize("௨௧", Lang.TA))
    }

    @Test
    fun `decimals are spelled digit by digit after the point`() {
        assertEquals("twelve point five", n.normalize("12.5", Lang.EN))
        assertEquals("बारह दशमलव पांच छह", n.normalize("12.56", Lang.HI))
    }

    @Test
    fun `percentages`() {
        assertEquals("fifty percent", n.normalize("50%", Lang.EN))
        assertEquals("पचास प्रतिशत", n.normalize("50%", Lang.HI))
    }

    @Test
    fun `currency rupees and paise per language`() {
        assertEquals("one hundred rupees", n.normalize("Rs 100", Lang.EN))
        assertEquals("one hundred rupees fifty paise", n.normalize("₹100.50", Lang.EN))
        assertEquals("एक सौ रुपये", n.normalize("₹100", Lang.HI))
        assertEquals("एक सौ रुपये पचास पैसे", n.normalize("Rs. 100.50", Lang.HI))
        assertEquals("એકાવન રૂપિયા", n.normalize("Rs51", Lang.GU))
    }

    @Test
    fun `dates DD slash MM slash YYYY and DD dash MM dash YYYY`() {
        assertEquals("seventeen May two thousand twenty four", n.normalize("17/05/2024", Lang.EN))
        assertEquals("seventeen May two thousand twenty four", n.normalize("17-05-2024", Lang.EN))
        assertEquals("सत्रह मई दो हज़ार चौबीस", n.normalize("17/05/2024", Lang.HI))
    }

    @Test
    fun `times HH colon MM`() {
        assertEquals("ten o'clock", n.normalize("10:00", Lang.EN))
        assertEquals("ten o'clock thirty minutes", n.normalize("10:30", Lang.EN))
        assertEquals("दस बजे तीस मिनट", n.normalize("10:30", Lang.HI))
    }

    @Test
    fun `long digit runs are read digit by digit like phone numbers`() {
        assertEquals("nine eight seven six five four three two one zero", n.normalize("9876543210", Lang.EN))
    }

    @Test
    fun `units km kg m celsius`() {
        assertEquals("five kilometers", n.normalize("5km", Lang.EN))
        assertEquals("दस किलोग्राम", n.normalize("10kg", Lang.HI))
        assertEquals("two meters", n.normalize("2m", Lang.EN))
        assertEquals("thirty eight degrees Celsius", n.normalize("38°C", Lang.EN))
    }

    @Test
    fun `no ascii or native digits remain after normalization`() {
        val digitClasses = Regex("[0-9०-९૦-૯০-৯୦-୯೦-೯൦-൯௦-௯౦-౯]")
        for (lang in Lang.entries) {
            val out = n.normalize("Message 21 sent at 10:30 on 17/05/2024 costing Rs 100.50, 5km away, call 9876543210", lang)
            assertFalse("$lang left digits in: $out", digitClasses.containsMatchIn(out))
        }
    }

    @Test
    fun `question mark is preserved for intonation`() {
        assertTrue(n.normalize("Are you safe?", Lang.EN).endsWith("?"))
    }
}
