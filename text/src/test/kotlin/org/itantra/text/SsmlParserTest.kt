package org.itantra.text

import org.itantra.core.Emotion
import org.itantra.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsmlParserTest {
    private val parser = SsmlParser(IndicNormalizer(HandTableNumberSpeller()))

    @Test
    fun `plain text without tags is split into sentences`() {
        val segs = parser.parse("Hello there. How are you?", Lang.EN)
        assertEquals(2, segs.size)
        assertEquals("Hello there.", segs[0].text)
        assertEquals("How are you?", segs[1].text)
    }

    @Test
    fun `break sets pauseBeforeMs on the next segment only`() {
        val segs = parser.parse("""<speak><s>One.</s><break time="500ms"/><s>Two.</s></speak>""", Lang.EN)
        assertEquals(2, segs.size)
        assertEquals(0, segs[0].pauseBeforeMs)
        assertEquals(500, segs[1].pauseBeforeMs)
    }

    @Test
    fun `break time accepts seconds and strength keywords`() {
        val secs = parser.parse("""<speak><s>A</s><break time="1s"/><s>B</s></speak>""", Lang.EN)
        assertEquals(1000, secs[1].pauseBeforeMs)
        val strong = parser.parse("""<speak><s>A</s><break strength="strong"/><s>B</s></speak>""", Lang.EN)
        assertEquals(750, strong[1].pauseBeforeMs)
    }

    @Test
    fun `p adds a 300ms pause before the next paragraph`() {
        val segs = parser.parse("""<speak><p>First.</p><p>Second.</p></speak>""", Lang.EN)
        assertEquals(2, segs.size)
        assertEquals(0, segs[0].pauseBeforeMs)
        assertEquals(300, segs[1].pauseBeforeMs)
    }

    @Test
    fun `nested prosody composes rate multiplicatively and pitch volume additively`() {
        val segs = parser.parse(
            """<speak><prosody rate="1.2" pitch="+1st" volume="+2dB"><prosody rate="1.1" pitch="+1st" volume="+1dB"><s>fast</s></prosody></prosody></speak>""",
            Lang.EN,
        )
        assertEquals(1, segs.size)
        assertEquals(1.32f, segs[0].rate, 0.01f)
        assertEquals(2f, segs[0].pitchSemitones, 0.01f)
        assertEquals(3f, segs[0].volumeDb, 0.01f)
    }

    @Test
    fun `prosody keyword rates`() {
        val slow = parser.parse("""<speak><s><prosody rate="x-slow">slow</prosody></s></speak>""", Lang.EN)
        assertEquals(0.6f, slow[0].rate, 0.01f)
    }

    @Test
    fun `emphasis merges into the running sentence as stress words with a volume bump`() {
        val segs = parser.parse("""<speak>Hello <emphasis>world</emphasis> now.</speak>""", Lang.EN)
        assertEquals(1, segs.size)
        assertTrue(segs[0].text.contains("Hello"))
        assertTrue(segs[0].text.contains("world"))
        assertTrue(segs[0].stressWords.contains("world"))
        assertTrue(segs[0].volumeDb > 0f)
    }

    @Test
    fun `say-as cardinal converts the number and merges into the sentence`() {
        val segs = parser.parse("""<speak>Room <say-as interpret-as="cardinal">21</say-as> is ready.</speak>""", Lang.EN)
        assertEquals(1, segs.size)
        assertEquals("Room twenty one is ready.", segs[0].text)
    }

    @Test
    fun `say-as digits characters and telephone`() {
        val digits = parser.parse("""<speak><say-as interpret-as="digits">120</say-as></speak>""", Lang.EN)
        assertEquals("one two zero", digits[0].text)

        val chars = parser.parse("""<speak><say-as interpret-as="characters">AB</say-as></speak>""", Lang.EN)
        assertTrue(chars[0].text.contains("A"))
        assertTrue(chars[0].text.contains("B"))

        val phone = parser.parse("""<speak><say-as interpret-as="telephone">911</say-as></speak>""", Lang.EN)
        assertEquals("nine one one", phone[0].text)
    }

    @Test
    fun `custom emotion tag sets segment emotion`() {
        val segs = parser.parse("""<speak><emotion name="urgent"><s>Warning</s></emotion></speak>""", Lang.EN)
        assertEquals(Emotion.URGENT, segs[0].emotion)
    }

    @Test
    fun `unknown tags pass through their content unchanged`() {
        val segs = parser.parse("""<speak><randomtag>Hello 5 apples</randomtag></speak>""", Lang.EN)
        assertEquals(1, segs.size)
        assertEquals("Hello five apples", segs[0].text)
    }

    @Test
    fun `xml entities are unescaped`() {
        val segs = parser.parse("""<speak>Tom &amp; Jerry</speak>""", Lang.EN)
        assertEquals("Tom & Jerry", segs[0].text)
    }

    @Test
    fun `malformed ssml never throws and still produces speakable output`() {
        val inputs = listOf(
            "<speak><s>Unclosed sentence",
            "</broken><random weird=\"attr\">text</random></also-broken>",
            "<speak attr=unquoted>hello<<<broken>>></speak>",
            "just plain text with a stray < angle bracket",
        )
        for (input in inputs) {
            val segs = parser.parse(input, Lang.EN)
            assertTrue("expected non-empty output for: $input", segs.isNotEmpty())
        }
    }

    @Test
    fun `mismatched closing tags are ignored leniently`() {
        val segs = parser.parse("""<speak><s>Hello</p></s></speak>""", Lang.EN)
        assertFalse(segs.isEmpty())
        assertTrue(segs[0].text.contains("Hello"))
    }
}
