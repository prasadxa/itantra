package org.itantra.text

import org.itantra.core.Lang
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertKeywordsTest {
    @Test
    fun `detects native script emergency keywords per language`() {
        assertTrue(AlertKeywords.containsAlertKeyword("मदद करो जल्दी आओ", Lang.HI))
        assertTrue(AlertKeywords.containsAlertKeyword("आग लग गई है", Lang.HI))
        assertTrue(AlertKeywords.containsAlertKeyword("पूर आला आहे", Lang.MR))
        assertTrue(AlertKeywords.containsAlertKeyword("বন্যা হয়েছে", Lang.BN))
        assertTrue(AlertKeywords.containsAlertKeyword("ಭೂಕಂಪ ಆಗಿದೆ", Lang.KN))
    }

    @Test
    fun `detects common romanised keywords regardless of language`() {
        assertTrue(AlertKeywords.containsAlertKeyword("SOS please help", Lang.HI))
        assertTrue(AlertKeywords.containsAlertKeyword("there is a fire here", Lang.TA))
        assertTrue(AlertKeywords.containsAlertKeyword("bachao madad", Lang.EN))
    }

    @Test
    fun `ordinary messages are not flagged`() {
        assertFalse(AlertKeywords.containsAlertKeyword("I will reach home by evening", Lang.EN))
        assertFalse(AlertKeywords.containsAlertKeyword("आज मौसम अच्छा है", Lang.HI))
    }

    @Test
    fun `english emergency keywords`() {
        for (word in listOf("help", "emergency", "danger", "ambulance", "police", "trapped", "injured")) {
            assertTrue(word, AlertKeywords.containsAlertKeyword("please $word now", Lang.EN))
        }
    }
}
