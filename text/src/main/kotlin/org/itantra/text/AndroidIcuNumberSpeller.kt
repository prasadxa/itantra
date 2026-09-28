package org.itantra.text

import org.itantra.core.Lang

/**
 * Runtime [NumberSpeller] that opportunistically tries ICU spellout on-device.
 *
 * `android.icu.text.RuleBasedNumberFormat` (the SPELLOUT engine) is **not** part of the public
 * Android SDK surface — it is absent from `android.jar` for every compileSdk we checked (36/37),
 * unlike upstream ICU4J where it exists. Some device images may still carry the class in the
 * runtime `android.icu` implementation even though it isn't declared in the public stub, so this
 * is called via reflection (which also means it compiles cleanly without a private/hidden-API
 * dependency). Any failure — class/method missing, no SPELLOUT rule for the locale, wrong output
 * shape, hidden-API restriction — is swallowed and the caller falls back to
 * [HandTableNumberSpeller], which is the actually-verified source of truth for this app. In
 * plain JVM unit tests this always fails closed (class not found), which is expected and
 * exercises that fallback path.
 */
class AndroidIcuNumberSpeller : NumberSpeller {
    override fun spellCardinal(n: Long, lang: Lang): String? = try {
        val uLocaleClass = Class.forName("android.icu.util.ULocale")
        val rbnfClass = Class.forName("android.icu.text.RuleBasedNumberFormat")
        val locale = uLocaleClass.getConstructor(String::class.java, String::class.java).newInstance(lang.code, "IN")
        val spelloutField = rbnfClass.getField("SPELLOUT")
        val spelloutStyle = spelloutField.getInt(null)
        val fmt = rbnfClass.getConstructor(uLocaleClass, Int::class.javaPrimitiveType).newInstance(locale, spelloutStyle)
        val format = rbnfClass.getMethod("format", Long::class.javaPrimitiveType)
        (format.invoke(fmt, n) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }
}
