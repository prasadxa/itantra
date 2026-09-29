package org.itantra.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle

/** Multiplier applied to chat message text and live partial captions (see
 * [org.itantra.app.ui.ChatScreen]), on top of whatever the current [androidx.compose.ui.unit.Density]
 * already applies for the system/app font scale. One of [SettingsPrefs.MESSAGE_TEXT_SCALE_STEPS];
 * provided at the app root ([org.itantra.app.ui.AppRoot]) from [SettingsPrefs.messageTextScaleFlow],
 * already combined with the system font scale via [effectiveMessageTextScale]. The default here (1f)
 * only matters for a `@Preview`/test composable that never sees the real provider. */
val LocalMessageTextScale = compositionLocalOf { 1f }

/** A sane ceiling for [effectiveMessageTextScale]'s product — keeps an XL message-text step stacked
 * on top of a large system/accessibility font setting from blowing past a size the chat message
 * card and live-caption layouts can absorb without clipping or wrapping badly. */
const val MAX_EFFECTIVE_MESSAGE_TEXT_SCALE = 2.2f

/**
 * Combines the user's chosen [SettingsPrefs.messageTextScale] step with the system font-scale
 * setting ([androidx.compose.ui.unit.Density.fontScale]) multiplicatively — accessibility font-size
 * preferences should still apply on top of this app-level setting, not be overridden by it — then
 * clamps the product to [MAX_EFFECTIVE_MESSAGE_TEXT_SCALE] (see its doc) via [MESSAGE_TEXT_SCALE_MIN].
 * Pure so it's unit-testable without a Context/Robolectric.
 */
fun effectiveMessageTextScale(messageTextScale: Float, systemFontScale: Float): Float =
    (messageTextScale * systemFontScale).coerceIn(MESSAGE_TEXT_SCALE_MIN, MAX_EFFECTIVE_MESSAGE_TEXT_SCALE)

/** Floor for [effectiveMessageTextScale] — guards a pathological (e.g. zero/negative) system
 * fontScale from collapsing message text to nothing. */
const val MESSAGE_TEXT_SCALE_MIN = 0.5f

/** Convenience for message-card/live-caption text styles: applies [LocalMessageTextScale] to both
 * [TextStyle.fontSize] and [TextStyle.lineHeight] so lines stay readably spaced at every step. */
@Composable
fun TextStyle.scaledByMessageTextSize(): TextStyle {
    val scale = LocalMessageTextScale.current
    return copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
}
