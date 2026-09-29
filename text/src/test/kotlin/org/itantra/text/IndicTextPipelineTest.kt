package org.itantra.text

import org.itantra.core.Emotion
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.VoiceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicTextPipelineTest {
    private val pipeline = IndicTextPipeline()

    private fun msg(
        text: String,
        lang: Lang = Lang.EN,
        priority: Priority = Priority.NORMAL,
        emotion: Emotion? = null,
        ssml: Boolean = false,
    ) = VoiceMessage(id = "1", from = "a", lang = lang, text = text, priority = priority, emotion = emotion, ssml = ssml)

    @Test
    fun `plain text is normalized and split into sentences`() {
        val plan = pipeline.plan(msg("I have 21 apples. Are you safe?"))
        assertEquals(2, plan.segments.size)
        assertEquals("I have twenty one apples.", plan.segments[0].text)
        assertTrue(plan.segments[1].text.endsWith("?"))
    }

    @Test
    fun `alert priority forces urgent style even without keywords`() {
        val plan = pipeline.plan(msg("Please respond soon", priority = Priority.ALERT))
        assertEquals(Emotion.URGENT, plan.segments[0].emotion)
        assertTrue(plan.segments[0].rate >= 1.1f)
        assertTrue(plan.segments[0].volumeDb >= 6f)
    }

    @Test
    fun `alert keyword in normal-priority text is detected as urgent`() {
        val plan = pipeline.plan(msg("There is a fire, please help", priority = Priority.NORMAL))
        assertEquals(Emotion.URGENT, plan.segments[0].emotion)
    }

    @Test
    fun `explicit emotion always wins over alert detection`() {
        val plan = pipeline.plan(msg("There is a fire, help", emotion = Emotion.HAPPY))
        assertEquals(Emotion.HAPPY, plan.segments[0].emotion)
    }

    @Test
    fun `ordinary text stays neutral`() {
        val plan = pipeline.plan(msg("See you at 5pm"))
        assertEquals(Emotion.NEUTRAL, plan.segments[0].emotion)
    }

    @Test
    fun `ssml messages are parsed instead of treated as plain text`() {
        val plan = pipeline.plan(msg("""<speak>Hello <say-as interpret-as="cardinal">5</say-as> friends.</speak>""", ssml = true))
        assertEquals(1, plan.segments.size)
        assertTrue(plan.segments[0].text.contains("five"))
    }

    @Test
    fun `plan carries the message language and priority`() {
        val plan = pipeline.plan(msg("नमस्ते", lang = Lang.HI, priority = Priority.ALERT))
        assertEquals(Lang.HI, plan.lang)
        assertEquals(Priority.ALERT, plan.priority)
    }

    @Test
    fun `a message with a GPS fix gets an extra spoken location segment`() {
        val withLocation = VoiceMessage(
            id = "1", from = "a", lang = Lang.EN, text = "Need rescue", priority = Priority.ALERT,
            lat = 12.9716, lon = 77.5946, accuracyM = 15f,
        )
        val plan = pipeline.plan(withLocation)
        assertEquals(2, plan.segments.size)
        assertEquals("Need rescue", plan.segments[0].text)
        // Numbers are spelled by IndicNormalizer just like any other number in the message body.
        assertTrue(plan.segments[1].text.contains("twelve"))
        assertTrue(plan.segments[1].text.contains("north"))
        assertTrue(plan.segments[1].text.contains("seventy seven"))
        assertTrue(plan.segments[1].text.contains("east"))
        assertTrue(plan.segments[1].text.contains("accuracy"))
        // The location segment inherits the same URGENT styling as the rest of an ALERT message.
        assertEquals(Emotion.URGENT, plan.segments[1].emotion)
    }

    @Test
    fun `a message without lat or lon gets no extra segment`() {
        val plan = pipeline.plan(msg("Need rescue", priority = Priority.ALERT))
        assertEquals(1, plan.segments.size)
    }
}
