package org.itantra.app.ui

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import java.util.Locale

/**
 * Per-app UI locale (menus/onboarding/about strings — see res/values-<lang>/strings.xml),
 * independent of the phone's system language, and the "have we shown onboarding" flag. Persisted
 * in a small SharedPreferences file so it survives process death without pulling in
 * androidx.appcompat just for AppCompatDelegate.setApplicationLocales() (APK size is judged).
 *
 * [wrap] is applied in `MainActivity.attachBaseContext` so every resource lookup — including the
 * very first Compose frame — resolves against the chosen locale; [applyAndRecreate] is used when
 * the user picks a language on the onboarding/language-picker screens, and calls
 * [Activity.recreate] so resources reload immediately.
 */
object LocaleManager {
    private const val PREFS = "itantra_prefs"
    private const val KEY_LOCALE_TAG = "ui_locale_tag" // ISO 639-1, or absent = follow system
    private const val KEY_ONBOARDING_DONE = "onboarding_done"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** null means "follow system locale". */
    fun getLocaleTag(context: Context): String? = prefs(context).getString(KEY_LOCALE_TAG, null)

    fun setLocaleTag(context: Context, tag: String?) {
        prefs(context).edit().apply {
            if (tag == null) remove(KEY_LOCALE_TAG) else putString(KEY_LOCALE_TAG, tag)
        }.apply()
    }

    fun isOnboardingDone(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDING_DONE, false)

    fun setOnboardingDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, done).apply()
    }

    /** Wraps [base] with a Configuration carrying the saved locale (if any); pass through the
     * system default otherwise. Safe to call unconditionally — a no-op when no tag is saved. */
    fun wrap(base: Context): Context {
        val tag = getLocaleTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun applyAndRecreate(activity: Activity, tag: String?) {
        setLocaleTag(activity, tag)
        activity.recreate()
    }
}
