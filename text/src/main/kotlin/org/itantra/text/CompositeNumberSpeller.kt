package org.itantra.text

import org.itantra.core.Lang

/** Tries [preferred] (may be null / may throw / may return null) then falls back to [fallback]. */
class CompositeNumberSpeller(
    private val preferred: NumberSpeller?,
    private val fallback: NumberSpeller,
) : NumberSpeller {
    override fun spellCardinal(n: Long, lang: Lang): String {
        val fromPreferred = preferred?.let {
            try {
                it.spellCardinal(n, lang)
            } catch (e: Throwable) {
                null
            }
        }
        return fromPreferred?.takeIf { it.isNotBlank() } ?: (fallback.spellCardinal(n, lang) ?: "")
    }
}
