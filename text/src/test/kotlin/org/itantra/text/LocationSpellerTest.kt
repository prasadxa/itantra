package org.itantra.text

import org.itantra.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSpellerTest {

    @Test
    fun `english phrase names hemispheres and includes accuracy`() {
        val phrase = LocationSpeller.phrase(12.9716, 77.5946, 15f, Lang.EN)
        assertEquals("location 12.97 north, 77.59 east, accuracy 15 metres", phrase)
    }

    @Test
    fun `hindi phrase uses hindi compass and unit words`() {
        val phrase = LocationSpeller.phrase(12.9716, 77.5946, 15f, Lang.HI)
        assertTrue(phrase.contains("उत्तर"))
        assertTrue(phrase.contains("पूर्व"))
        assertTrue(phrase.contains("मीटर"))
        assertTrue(phrase.contains("12.97"))
        assertTrue(phrase.contains("77.59"))
    }

    @Test
    fun `tamil phrase uses tamil compass and unit words`() {
        val phrase = LocationSpeller.phrase(12.9716, 77.5946, 15f, Lang.TA)
        assertTrue(phrase.contains("வடக்கு"))
        assertTrue(phrase.contains("கிழக்கு"))
        assertTrue(phrase.contains("மீட்டர்"))
    }

    @Test
    fun `southern and western coordinates use south and west words`() {
        val phrase = LocationSpeller.phrase(-33.87, -70.65, null, Lang.EN)
        assertEquals("location 33.87 south, 70.65 west", phrase)
    }

    @Test
    fun `accuracy clause omitted when null or negative`() {
        assertEquals("location 12.97 north, 77.59 east", LocationSpeller.phrase(12.9716, 77.5946, null, Lang.EN))
        assertEquals("location 12.97 north, 77.59 east", LocationSpeller.phrase(12.9716, 77.5946, -1f, Lang.EN))
    }

    @Test
    fun `every language produces a non-blank phrase containing both numbers`() {
        for (lang in Lang.entries) {
            val phrase = LocationSpeller.phrase(12.9716, 77.5946, 15f, lang)
            assertTrue("$lang phrase was blank", phrase.isNotBlank())
            assertTrue("$lang phrase missing latitude", phrase.contains("12.97"))
            assertTrue("$lang phrase missing longitude", phrase.contains("77.59"))
        }
    }

    @Test
    fun `distance and bearing are computed correctly for a known pair`() {
        // Bengaluru (12.9716, 77.5946) to Chennai (13.0827, 80.2707) - roughly 290 km, eastish.
        val distance = LocationSpeller.distanceMeters(12.9716, 77.5946, 13.0827, 80.2707)
        assertTrue("distance was $distance", distance in 280_000.0..300_000.0)
        val bearing = LocationSpeller.bearingDegrees(12.9716, 77.5946, 13.0827, 80.2707)
        assertTrue("bearing was $bearing", bearing in 60.0..110.0)
    }

    @Test
    fun `distance phrase reports kilometres for long distances and metres for short ones`() {
        val far = LocationSpeller.distancePhrase(12.9716, 77.5946, 13.0827, 80.2707, Lang.EN)
        assertTrue(far.contains("km"))
        // ~111 metres north of the origin.
        val near = LocationSpeller.distancePhrase(12.9716, 77.5946, 12.9726, 77.5946, Lang.EN)
        assertTrue(near.contains("metres"))
        assertTrue(near.contains("north"))
    }
}
