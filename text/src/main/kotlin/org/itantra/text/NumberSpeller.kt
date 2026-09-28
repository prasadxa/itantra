package org.itantra.text

import org.itantra.core.Lang

/**
 * Spells out a whole non-negative number as words in [lang] using Indian digit grouping
 * (thousand / lakh / crore). Implementations may return null when they cannot produce a
 * result (e.g. no CLDR spellout rule for the locale) so callers can fall back.
 */
interface NumberSpeller {
    fun spellCardinal(n: Long, lang: Lang): String?
}
