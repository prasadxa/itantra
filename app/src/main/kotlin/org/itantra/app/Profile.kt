package org.itantra.app

import android.app.ActivityManager
import android.content.Context

/**
 * Device performance tier. Chosen at [TalkService] startup (see [ProfileManager.resolve]) so
 * engines can be sized for the phone judges actually test on — SIH PS 26173 scores "runs smoothly
 * on low and mid range phones" and efficiency (RAM/flash/idle CPU) is 20% of the mark.
 *
 * LITE: STT/TTS numThreads 2, VITS-only where VITS has a voice (bn kn ml mr ta te), Mio (with the
 * lite/q4 GGUF if present, else q8 — see [org.itantra.core.ModelPaths]) for hi/gu/or/en, live STT
 * partials off, recognizer + VAD only.
 * FULL: current behaviour (numThreads 4, live partials every 500ms).
 */
enum class Profile { FULL, LITE }

/** User-facing override for [Profile] detection, persisted so testers can force LITE on a
 * high-RAM phone (e.g. the 16 GB OnePlus 12 used to validate this build) or vice versa. */
enum class ProfileOverride { AUTO, FULL, LITE }

/**
 * Detects [Profile] from `ActivityManager.MemoryInfo.totalMem` (< 6 GB -> LITE) unless the user
 * has forced one via Settings (see the Profile row in [org.itantra.app.ui.ModelsScreen]).
 */
object ProfileManager {
    private const val PREFS_NAME = "itantra_settings"
    private const val KEY_OVERRIDE = "profile_override"

    /** Below this, the phone is treated as low/mid-range and gets [Profile.LITE]. */
    const val LITE_THRESHOLD_BYTES = 6L * 1024 * 1024 * 1024

    fun resolve(context: Context): Profile {
        val override = getOverride(context)
        return when (override) {
            ProfileOverride.FULL -> Profile.FULL
            ProfileOverride.LITE -> Profile.LITE
            ProfileOverride.AUTO -> detect(context)
        }
    }

    /** Ignores the override — used to show "auto would pick X" next to the override control. */
    fun detect(context: Context): Profile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return Profile.FULL
        val info = ActivityManager.MemoryInfo()
        return try {
            am.getMemoryInfo(info)
            if (info.totalMem in 1..(LITE_THRESHOLD_BYTES - 1)) Profile.LITE else Profile.FULL
        } catch (t: Throwable) {
            Profile.FULL
        }
    }

    fun getOverride(context: Context): ProfileOverride {
        val raw = prefs(context).getString(KEY_OVERRIDE, ProfileOverride.AUTO.name)
        return try {
            ProfileOverride.valueOf(raw ?: ProfileOverride.AUTO.name)
        } catch (e: IllegalArgumentException) {
            ProfileOverride.AUTO
        }
    }

    fun setOverride(context: Context, override: ProfileOverride) {
        prefs(context).edit().putString(KEY_OVERRIDE, override.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
