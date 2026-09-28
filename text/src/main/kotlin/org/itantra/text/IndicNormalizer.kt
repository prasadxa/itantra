package org.itantra.text

import org.itantra.core.Lang

/**
 * Converts numbers, dates, currency, times, phone numbers and units embedded in [lang] text
 * into spoken words, and converts native-script digits to numbers along the way. Pure Kotlin,
 * regex based; no Android APIs beyond the optional (and always-guarded) ICU number speller.
 */
class IndicNormalizer(
    private val speller: NumberSpeller = CompositeNumberSpeller(AndroidIcuNumberSpeller(), HandTableNumberSpeller()),
) {
    private val hand = HandTableNumberSpeller()

    fun normalize(text: String, lang: Lang): String {
        var t = text
        t = CURRENCY_RE.replace(t) { m -> spellCurrency(m.groupValues[1], lang) }
        t = PERCENT_RE.replace(t) { m -> spellNumberPart(m.groupValues[1], lang) + " " + words(lang).percent }
        t = TIME_RE.replace(t) { m -> spellTimeMatch(m.groupValues[1], m.groupValues[2], lang) ?: m.value }
        t = DATE_RE.replace(t) { m -> spellDateMatch(m.groupValues[1], m.groupValues[2], m.groupValues[3], lang) ?: m.value }
        t = UNIT_RE.replace(t) { m ->
            val unitWord = words(lang).units[m.groupValues[2]]
            if (unitWord == null) m.value else spellNumberPart(m.groupValues[1], lang) + " " + unitWord
        }
        t = PHONE_RE.replace(t) { m -> spellDigitsRaw(m.value, lang) }
        t = PLAIN_NUMBER_RE.replace(t) { m -> spellNumberPart(m.value, lang) }
        return t
    }

    // ---- say-as helpers (also used by SsmlParser) ----

    fun spellCardinalText(raw: String, lang: Lang): String = spellNumberPart(raw, lang)

    fun spellDigitsText(raw: String, lang: Lang): String = spellDigitsRaw(raw, lang)

    fun spellTelephoneText(raw: String, lang: Lang): String = spellDigitsRaw(raw, lang)

    fun spellCharactersText(raw: String, lang: Lang): String =
        raw.trim().map { c ->
            val d = toAsciiDigit(c)
            if (d != null) hand.digitWord(d - '0', lang) else c.toString()
        }.joinToString(" ")

    fun spellDateText(raw: String, lang: Lang): String {
        val m = DATE_RE.find(raw) ?: return spellDigitsRaw(raw, lang)
        return spellDateMatch(m.groupValues[1], m.groupValues[2], m.groupValues[3], lang) ?: spellDigitsRaw(raw, lang)
    }

    fun spellTimeText(raw: String, lang: Lang): String {
        val m = TIME_RE.find(raw) ?: return spellDigitsRaw(raw, lang)
        return spellTimeMatch(m.groupValues[1], m.groupValues[2], lang) ?: spellDigitsRaw(raw, lang)
    }

    // ---- internals ----

    private fun words(lang: Lang) = LocalizedWordsTable.of(lang)

    /** [speller]'s result if non-blank, else the guaranteed-correct hand-table fallback. */
    private fun spell(n: Long, lang: Lang): String = speller.spellCardinal(n, lang)?.takeIf { it.isNotBlank() } ?: hand.spellCardinal(n, lang)

    private fun spellCurrency(raw: String, lang: Lang): String {
        val cleaned = toAscii(raw.replace(",", ""))
        val parts = cleaned.split(".")
        val rupeesN = parts[0].toLongOrNull() ?: 0L
        val w = words(lang)
        val rupeesWords = spell(rupeesN, lang) + " " + w.rupees
        if (parts.size < 2 || parts[1].isEmpty()) return rupeesWords
        val paiseDigits = parts[1].take(2).padEnd(2, '0')
        val paiseN = paiseDigits.toLongOrNull() ?: 0L
        if (paiseN == 0L) return rupeesWords
        val paiseWords = spell(paiseN, lang) + " " + w.paise
        return "$rupeesWords $paiseWords"
    }

    private fun spellNumberPart(raw: String, lang: Lang): String {
        val cleaned = toAscii(raw.replace(",", ""))
        val dot = cleaned.indexOf('.')
        if (dot < 0) {
            val n = cleaned.toLongOrNull() ?: return raw
            return spell(n, lang)
        }
        val intPart = cleaned.substring(0, dot)
        val fracPart = cleaned.substring(dot + 1)
        val intN = intPart.toLongOrNull() ?: 0L
        val intWords = spell(intN, lang)
        if (fracPart.isEmpty()) return intWords
        val fracWords = fracPart.map { c -> hand.digitWord(c - '0', lang) }.joinToString(" ")
        return "$intWords ${words(lang).point} $fracWords"
    }

    private fun spellDigitsRaw(raw: String, lang: Lang): String {
        val cleaned = toAscii(raw)
        return cleaned.mapNotNull { c -> if (c in '0'..'9') hand.digitWord(c - '0', lang) else null }.joinToString(" ")
    }

    private fun spellTimeMatch(hourRaw: String, minuteRaw: String, lang: Lang): String? {
        val hour = toAscii(hourRaw).toIntOrNull() ?: return null
        val minute = toAscii(minuteRaw).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val w = words(lang)
        val hourWords = spell(hour.toLong(), lang)
        return if (minute == 0) {
            "$hourWords ${w.oclock}"
        } else {
            val minuteWords = spell(minute.toLong(), lang)
            "$hourWords ${w.oclock} $minuteWords ${w.minutesWord}"
        }
    }

    private fun spellDateMatch(dayRaw: String, monthRaw: String, yearRaw: String, lang: Lang): String? {
        val day = toAscii(dayRaw).toIntOrNull() ?: return null
        val month = toAscii(monthRaw).toIntOrNull() ?: return null
        val year = toAscii(yearRaw).toIntOrNull() ?: return null
        if (day !in 1..31 || month !in 1..12) return null
        val w = words(lang)
        val dayWords = spell(day.toLong(), lang)
        val yearWords = spell(year.toLong(), lang)
        return "$dayWords ${w.months[month - 1]} $yearWords"
    }

    companion object {
        // Native-script Indic digit blocks: Devanagari (hi, mr), Gujarati, Bengali, Odia,
        // Kannada, Malayalam, Tamil, Telugu; each maps 0..9 with a fixed codepoint offset.
        private const val DIGIT_CLASS =
            "0-9" +
                "\u0966-\u096F" + // Devanagari
                "\u0AE6-\u0AEF" + // Gujarati
                "\u09E6-\u09EF" + // Bengali
                "\u0B66-\u0B6F" + // Odia
                "\u0CE6-\u0CEF" + // Kannada
                "\u0D66-\u0D6F" + // Malayalam
                "\u0BE6-\u0BEF" + // Tamil
                "\u0C66-\u0C6F" // Telugu

        private val CURRENCY_RE = Regex("(?:₹|[Rr][sS]\\.?)\\s?([$DIGIT_CLASS][$DIGIT_CLASS,]*(?:\\.[$DIGIT_CLASS]+)?)")
        private val PERCENT_RE = Regex("([$DIGIT_CLASS][$DIGIT_CLASS,]*(?:\\.[$DIGIT_CLASS]+)?)\\s?%")
        private val TIME_RE = Regex("\\b([$DIGIT_CLASS]{1,2}):([$DIGIT_CLASS]{2})\\b")
        private val DATE_RE = Regex("\\b([$DIGIT_CLASS]{1,2})[/-]([$DIGIT_CLASS]{1,2})[/-]([$DIGIT_CLASS]{4})\\b")
        private val UNIT_RE = Regex("([$DIGIT_CLASS][$DIGIT_CLASS,]*(?:\\.[$DIGIT_CLASS]+)?)\\s?(km|kg|°C|cm|mm|ml|l|m)\\b")
        private val PHONE_RE = Regex("\\b[$DIGIT_CLASS]{6,}\\b")
        private val PLAIN_NUMBER_RE = Regex("[$DIGIT_CLASS][$DIGIT_CLASS,]*(?:\\.[$DIGIT_CLASS]+)?")

        private fun toAsciiDigit(c: Char): Char? {
            val code = c.code
            return when {
                c in '0'..'9' -> c
                code in 0x0966..0x096F -> '0' + (code - 0x0966)
                code in 0x0AE6..0x0AEF -> '0' + (code - 0x0AE6)
                code in 0x09E6..0x09EF -> '0' + (code - 0x09E6)
                code in 0x0B66..0x0B6F -> '0' + (code - 0x0B66)
                code in 0x0CE6..0x0CEF -> '0' + (code - 0x0CE6)
                code in 0x0D66..0x0D6F -> '0' + (code - 0x0D66)
                code in 0x0BE6..0x0BEF -> '0' + (code - 0x0BE6)
                code in 0x0C66..0x0C6F -> '0' + (code - 0x0C66)
                else -> null
            }
        }

        private fun toAscii(s: String): String = s.map { toAsciiDigit(it) ?: it }.joinToString("")
    }
}
