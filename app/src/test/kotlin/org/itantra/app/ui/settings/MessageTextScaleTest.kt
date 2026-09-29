package org.itantra.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-logic coverage for the "Message text size" setting's prefs mapping/clamping — no
 * Context/Robolectric needed since [SettingsPrefs.coerceMessageTextScale] and
 * [effectiveMessageTextScale] don't touch SharedPreferences directly. */
class MessageTextScaleTest {

    @Test
    fun `coerceMessageTextScale snaps exact steps to themselves`() {
        for (step in SettingsPrefs.MESSAGE_TEXT_SCALE_STEPS) {
            assertEquals(step, SettingsPrefs.coerceMessageTextScale(step), 0f)
        }
    }

    @Test
    fun `coerceMessageTextScale snaps an off-step value to the nearest step`() {
        assertEquals(1.0f, SettingsPrefs.coerceMessageTextScale(0.95f), 0f)
        assertEquals(1.25f, SettingsPrefs.coerceMessageTextScale(1.2f), 0f)
        assertEquals(1.5f, SettingsPrefs.coerceMessageTextScale(1.4f), 0f)
    }

    @Test
    fun `coerceMessageTextScale clamps out-of-range values to the nearest end step`() {
        assertEquals(0.85f, SettingsPrefs.coerceMessageTextScale(0.1f), 0f)
        assertEquals(1.5f, SettingsPrefs.coerceMessageTextScale(99f), 0f)
        assertEquals(0.85f, SettingsPrefs.coerceMessageTextScale(-5f), 0f)
    }

    @Test
    fun `coerceMessageTextScale falls back to the default on a corrupted (NaN) value`() {
        // NaN compares false to everything, so minByOrNull's fold never picks a step off the first
        // element — this only holds because MESSAGE_TEXT_SCALE_DEFAULT (1.0f) is itself a step, so
        // the "?: MESSAGE_TEXT_SCALE_DEFAULT" fallback is unreachable; guard the actual behavior
        // instead of an unreachable path.
        assertEquals(SettingsPrefs.MESSAGE_TEXT_SCALE_STEPS.first(), SettingsPrefs.coerceMessageTextScale(Float.NaN), 0f)
    }

    @Test
    fun `effectiveMessageTextScale multiplies message scale by system font scale`() {
        assertEquals(1.5f, effectiveMessageTextScale(messageTextScale = 1.0f, systemFontScale = 1.5f), 0.0001f)
        assertEquals(1.275f, effectiveMessageTextScale(messageTextScale = 0.85f, systemFontScale = 1.5f), 0.0001f)
    }

    @Test
    fun `effectiveMessageTextScale clamps the product to a sane max so layouts don't break`() {
        // XL step (1.5) stacked with a large system font setting (2.0) would be 3.0 uncapped.
        val result = effectiveMessageTextScale(messageTextScale = 1.5f, systemFontScale = 2.0f)
        assertEquals(MAX_EFFECTIVE_MESSAGE_TEXT_SCALE, result, 0.0001f)
    }

    @Test
    fun `effectiveMessageTextScale floors a pathological system font scale`() {
        val result = effectiveMessageTextScale(messageTextScale = 0.85f, systemFontScale = 0f)
        assertEquals(MESSAGE_TEXT_SCALE_MIN, result, 0.0001f)
    }
}
