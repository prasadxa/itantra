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
 * [speechRate]/[playbackMode] are written here for the :tts side to read later (not yet wired up
 * to [org.itantra.tts.output.SpeechOutput] — that engine currently always synthesizes at rate
 * 1.0/"fast" pre-roll; hooking these two keys into [org.itantra.tts.output.PreRollEstimator] and
 * [org.itantra.core.TtsSegment.rate] is follow-up work for the TTS owner, out of this UI pass's
 * scope). [largeText] and [themeMode] are applied locally by [org.itantra.app.ui.AppRoot] and
 * [org.itantra.app.MainActivity] respectively.
 */
object SettingsPrefs {
    private const val PREFS_NAME = "itantra_settings"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_PLAYBACK_MODE = "playback_mode"
    private const val KEY_LARGE_TEXT = "large_text"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DEVICE_NAME = "device_name"

    const val PLAYBACK_SMOOTH = "smooth"
    const val PLAYBACK_FAST = "fast"

    const val SPEECH_RATE_MIN = 0.8f
    const val SPEECH_RATE_MAX = 1.3f

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSpeechRate(context: Context): Float =
        prefs(context).getFloat(KEY_SPEECH_RATE, 1f).coerceIn(SPEECH_RATE_MIN, SPEECH_RATE_MAX)

    fun setSpeechRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat(KEY_SPEECH_RATE, rate.coerceIn(SPEECH_RATE_MIN, SPEECH_RATE_MAX)).apply()
    }

    /** [PLAYBACK_SMOOTH] (default) or [PLAYBACK_FAST]. */
    fun getPlaybackMode(context: Context): String =
        prefs(context).getString(KEY_PLAYBACK_MODE, PLAYBACK_SMOOTH) ?: PLAYBACK_SMOOTH

    fun setPlaybackMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_PLAYBACK_MODE, mode).apply()
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
