package org.itantra.text

import com.ibm.icu.text.RuleBasedNumberFormat
import com.ibm.icu.util.ULocale
import org.itantra.core.Lang

/**
 * Test-only twin of [AndroidIcuNumberSpeller], backed by upstream ICU4J (`com.ibm.icu`) since
 * `android.icu` is an unimplemented stub under plain JVM unit tests. Used to check what CLDR's
 * SPELLOUT rules actually produce per language (see [IcuNumberSpellerTest]) — most of these ten
 * locales either have no spellout rule or don't use Indian thousand/lakh/crore grouping, which
 * is why [HandTableNumberSpeller] is the verified default in [IndicNormalizer].
 */
class IcuNumberSpeller : NumberSpeller {
    override fun spellCardinal(n: Long, lang: Lang): String? = try {
        val locale = ULocale(lang.code, "IN")
        val fmt = RuleBasedNumberFormat(locale, RuleBasedNumberFormat.SPELLOUT)
        fmt.format(n).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }
}
