package org.itantra.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small SharedPreferences-backed settings for the Settings screen — same file
 * ([ProfileManager]'s `itantra_settings`) so every user-tunable knob lives in one place.
 *
 * [speechRate]/[playbackMode] are read live via [speechRateFlow]/[playbackModeFlow] (same
 * seeded-StateFlow pattern as [largeText], pushed to by [setSpeechRate]/[setPlaybackMode]) so
 * [org.itantra.app.Orchestrator] (speech rate, applied to [org.itantra.core.TtsSegment.rate] for
 * received messages only) and [org.itantra.tts.output.SpeechOutput] (playback mode, gating
 * [org.itantra.tts.output.PreRollEstimator]'s adaptive pre-roll vs a fixed minimal one) pick up a
 * change immediately, without a service restart. [largeText] and [themeMode] are applied locally
 * by [org.itantra.app.ui.AppRoot] and [org.itantra.app.MainActivity] respectively.
 */
object SettingsPrefs {
    private const val PREFS_NAME = "itantra_settings"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_PLAYBACK_MODE = "playback_mode"
    private const val KEY_LARGE_TEXT = "large_text"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_MESSAGE_TEXT_SCALE = "message_text_scale"

    const val PLAYBACK_SMOOTH = "smooth"
    const val PLAYBACK_FAST = "fast"

    /** S/M/L/XL steps for the "Message text size" setting — applied only to chat message text (and
     * live partial captions) via [org.itantra.app.ui.settings.LocalMessageTextScale], not the whole
     * app's fontScale (that's [largeText]'s job). */
    val MESSAGE_TEXT_SCALE_STEPS = listOf(0.85f, 1.0f, 1.25f, 1.5f)
    const val MESSAGE_TEXT_SCALE_DEFAULT = 1.0f

    /** Snaps an arbitrary float onto the nearest entry in [MESSAGE_TEXT_SCALE_STEPS] — guards a
     * corrupted/pre-migration persisted value (or a future steps-list change) from producing an
     * off-step scale the settings UI's segmented buttons can't represent as "selected". */
    fun coerceMessageTextScale(raw: Float): Float =
        MESSAGE_TEXT_SCALE_STEPS.minByOrNull { kotlin.math.abs(it - raw) } ?: MESSAGE_TEXT_SCALE_DEFAULT

    const val SPEECH_RATE_MIN = 0.8f
    const val SPEECH_RATE_MAX = 1.3f

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSpeechRate(context: Context): Float =
        prefs(context).getFloat(KEY_SPEECH_RATE, 1f).coerceIn(SPEECH_RATE_MIN, SPEECH_RATE_MAX)

    fun setSpeechRate(context: Context, rate: Float) {
        val clamped = rate.coerceIn(SPEECH_RATE_MIN, SPEECH_RATE_MAX)
        prefs(context).edit().putFloat(KEY_SPEECH_RATE, clamped).apply()
        speechRateFlow(context) // ensures speechRateFlowRef is seeded before we push into it
        speechRateFlowRef?.value = clamped
    }

    /** [PLAYBACK_SMOOTH] (default) or [PLAYBACK_FAST]. */
    fun getPlaybackMode(context: Context): String =
        prefs(context).getString(KEY_PLAYBACK_MODE, PLAYBACK_SMOOTH) ?: PLAYBACK_SMOOTH

    fun setPlaybackMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_PLAYBACK_MODE, mode).apply()
        playbackModeFlow(context) // ensures playbackModeFlowRef is seeded before we push into it
        playbackModeFlowRef?.value = mode
    }

    private var speechRateFlowRef: MutableStateFlow<Float>? = null
    private var playbackModeFlowRef: MutableStateFlow<String>? = null

    /** Reactive so [org.itantra.app.TalkService] can hand [org.itantra.app.Orchestrator] a
     * provider lambda that reflects a change made while the service is running (see class doc). */
    fun speechRateFlow(context: Context): StateFlow<Float> {
        val flow = speechRateFlowRef ?: MutableStateFlow(getSpeechRate(context)).also { speechRateFlowRef = it }
        return flow.asStateFlow()
    }

    /** Reactive counterpart of [playbackMode] — see [speechRateFlow]. */
    fun playbackModeFlow(context: Context): StateFlow<String> {
        val flow = playbackModeFlowRef ?: MutableStateFlow(getPlaybackMode(context)).also { playbackModeFlowRef = it }
        return flow.asStateFlow()
    }

    /** Reactive so [org.itantra.app.ui.AppRoot] can scale the whole subtree's fontScale without a
     * shared app-wide singleton (kept local to this settings package). Seeded from the persisted
     * value on first access. */
    private var largeTextFlow: MutableStateFlow<Boolean>? = null

    fun largeText(context: Context): StateFlow<Boolean> {
        val flow = largeTextFlow ?: MutableStateFlow(prefs(context).getBoolean(KEY_LARGE_TEXT, false)).also { largeTextFlow = it }
        return flow.asStateFlow()
    }

    fun setLargeText(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LARGE_TEXT, enabled).apply()
        largeText(context) // ensures largeTextFlow is seeded before we push into it
        largeTextFlow?.value = enabled
    }

    private var messageTextScaleFlowRef: MutableStateFlow<Float>? = null

    /** Reactive so [org.itantra.app.ui.ChatScreen] (message cards + live captions) picks up a
     * change immediately, without a service/activity restart — see [speechRateFlow]. */
    fun messageTextScaleFlow(context: Context): StateFlow<Float> {
        val flow = messageTextScaleFlowRef
            ?: MutableStateFlow(coerceMessageTextScale(prefs(context).getFloat(KEY_MESSAGE_TEXT_SCALE, MESSAGE_TEXT_SCALE_DEFAULT)))
                .also { messageTextScaleFlowRef = it }
        return flow.asStateFlow()
    }

    fun setMessageTextScale(context: Context, scale: Float) {
        val clamped = coerceMessageTextScale(scale)
        prefs(context).edit().putFloat(KEY_MESSAGE_TEXT_SCALE, clamped).apply()
        messageTextScaleFlow(context) // ensures messageTextScaleFlowRef is seeded before we push into it
        messageTextScaleFlowRef?.value = clamped
    }

    fun getThemeMode(context: Context): ThemeMode {
        val raw = prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(raw ?: ThemeMode.SYSTEM.name)
        } catch (e: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /** null means "use the device model name" (see [TalkService.onCreate]). */
    fun getDeviceName(context: Context): String? = prefs(context).getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }

    fun setDeviceName(context: Context, name: String?) {
        prefs(context).edit().apply {
            if (name.isNullOrBlank()) remove(KEY_DEVICE_NAME) else putString(KEY_DEVICE_NAME, name.trim())
        }.apply()
    }
}
