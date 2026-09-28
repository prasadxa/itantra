package org.itantra.text

import org.itantra.core.Lang
import kotlin.math.abs

/**
 * Hand-authored, unit-tested number-to-words tables with Indian digit grouping
 * (hundred / thousand / lakh / crore).
 *
 * For Hindi, Marathi, Gujarati and Bengali, 0-99 are fully irregular and are tabulated in full
 * (standard forms). For Kannada, Malayalam, Tamil, Telugu and Odia, 0-19 and the tens
 * (20,30..90) are tabulated and 21-99 are composed as "<tens> <unit>" — a safe, unambiguous
 * form that is understandable but not always the maximally colloquial fused/sandhi form;
 * native-speaker review is recommended before shipping, especially for Odia (flagged by the
 * spec as the language most likely to need this fallback).
 */
class HandTableNumberSpeller : NumberSpeller {

    override fun spellCardinal(n: Long, lang: Lang): String = spell(n, table(lang))

    /** Word for a single digit 0-9, reused for digit-by-digit reading (phone numbers, etc). */
    fun digitWord(d: Int, lang: Lang): String = table(lang).words[d]

    private fun spell(n: Long, t: Table): String {
        if (n == 0L) return t.zero
        val neg = n < 0
        var v = abs(n)
        val crore = v / 10_000_000; v %= 10_000_000
        val lakh = v / 100_000; v %= 100_000
        val thousand = v / 1000; v %= 1000
        val hundred = v / 100; v %= 100
        val rest = v
        val parts = mutableListOf<String>()
        if (crore > 0) parts += groupWord(crore, t) + " " + t.crore
        if (lakh > 0) parts += groupWord(lakh, t) + " " + t.lakh
        if (thousand > 0) parts += groupWord(thousand, t) + " " + t.thousand
        if (hundred > 0) parts += t.words[hundred.toInt()] + " " + t.hundred
        if (rest > 0) parts += t.words[rest.toInt()]
        val out = parts.joinToString(" ")
        return if (neg) t.minus + " " + out else out
    }

    private fun groupWord(v: Long, t: Table): String =
        if (v < 100) t.words[v.toInt()] else spell(v, t)

    private data class Table(
        val zero: String,
        val minus: String,
        val words: Array<String>, // index 0..99
        val hundred: String,
        val thousand: String,
        val lakh: String,
        val crore: String,
    )

    private val tables = HashMap<Lang, Table>()

    private fun table(lang: Lang): Table = tables.getOrPut(lang) { buildTable(lang) }

    private fun buildTable(lang: Lang): Table = when (lang) {
        Lang.HI -> Table("शून्य", "ऋण", HI_WORDS, "सौ", "हज़ार", "लाख", "करोड़")
        Lang.MR -> Table("शून्य", "उणे", MR_WORDS, "शंभर", "हजार", "लाख", "कोटी")
        Lang.GU -> Table("શૂન્ય", "ઓછા", GU_WORDS, "સો", "હજાર", "લાખ", "કરોડ")
        Lang.BN -> Table("শূন্য", "ঋণাত্মক", BN_WORDS, "শত", "হাজার", "লক্ষ", "কোটি")
        Lang.OR -> Table("ଶୂନ୍ୟ", "ଋଣାତ୍ମକ", compose99(OR_ONES, OR_TENS), "ଶହ", "ହଜାର", "ଲକ୍ଷ", "କୋଟି")
        Lang.KN -> Table("ಸೊನ್ನೆ", "ಋಣ", compose99(KN_ONES, KN_TENS), "ನೂರು", "ಸಾವಿರ", "ಲಕ್ಷ", "ಕೋಟಿ")
        Lang.ML -> Table("പൂജ്യം", "മൈനസ്", compose99(ML_ONES, ML_TENS), "നൂറ്", "ആയിരം", "ലക്ഷം", "കോടി")
        Lang.TA -> Table("பூஜ்யம்", "கழித்தல்", compose99(TA_ONES, TA_TENS), "நூறு", "ஆயிரம்", "லட்சம்", "கோடி")
        Lang.TE -> Table("సున్నా", "మైనస్", compose99(TE_ONES, TE_TENS), "వంద", "వేయి", "లక్ష", "కోటి")
        Lang.EN -> Table("zero", "minus", compose99(EN_ONES, EN_TENS), "hundred", "thousand", "lakh", "crore")
    }

    companion object {
        /** ones[0..19] + tens[20,30..90] -> full 0..99 table, joined with a space for 21-99. */
        private fun compose99(ones: Array<String>, tens: Map<Int, String>): Array<String> {
            val w = Array(100) { "" }
            for (i in 0..19) w[i] = ones[i]
            for (t in 2..9) {
                val tensWord = tens[t * 10] ?: ""
                w[t * 10] = tensWord
                for (u in 1..9) w[t * 10 + u] = "$tensWord ${ones[u]}"
            }
            return w
        }

        private val EN_ONES = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
            "eighteen", "nineteen",
        )
        private val EN_TENS = mapOf(
            20 to "twenty", 30 to "thirty", 40 to "forty", 50 to "fifty", 60 to "sixty",
            70 to "seventy", 80 to "eighty", 90 to "ninety",
        )

        private val KN_ONES = arrayOf(
            "ಸೊನ್ನೆ", "ಒಂದು", "ಎರಡು", "ಮೂರು", "ನಾಲ್ಕು", "ಐದು", "ಆರು", "ಏಳು", "ಎಂಟು", "ಒಂಬತ್ತು", "ಹತ್ತು",
            "ಹನ್ನೊಂದು", "ಹನ್ನೆರಡು", "ಹದಿಮೂರು", "ಹದಿನಾಲ್ಕು", "ಹದಿನೈದು", "ಹದಿನಾರು", "ಹದಿನೇಳು", "ಹದಿನೆಂಟು", "ಹತ್ತೊಂಬತ್ತು",
        )
        private val KN_TENS = mapOf(
            20 to "ಇಪ್ಪತ್ತು", 30 to "ಮೂವತ್ತು", 40 to "ನಲವತ್ತು", 50 to "ಐವತ್ತು", 60 to "ಅರವತ್ತು",
            70 to "ಎಪ್ಪತ್ತು", 80 to "ಎಂಬತ್ತು", 90 to "ತೊಂಬತ್ತು",
        )

        private val ML_ONES = arrayOf(
            "പൂജ്യം", "ഒന്ന്", "രണ്ട്", "മൂന്ന്", "നാല്", "അഞ്ച്", "ആറ്", "ഏഴ്", "എട്ട്", "ഒമ്പത്", "പത്ത്",
            "പതിനൊന്ന്", "പന്ത്രണ്ട്", "പതിമൂന്ന്", "പതിനാല്", "പതിനഞ്ച്", "പതിനാറ്", "പതിനേഴ്", "പതിനെട്ട്", "പത്തൊമ്പത്",
        )
        private val ML_TENS = mapOf(
            20 to "ഇരുപത്", 30 to "മുപ്പത്", 40 to "നാല്‍പത്", 50 to "അമ്പത്", 60 to "അറുപത്",
            70 to "എഴുപത്", 80 to "എൺപത്", 90 to "തൊണ്ണൂറ്",
        )

        private val TA_ONES = arrayOf(
            "பூஜ்யம்", "ஒன்று", "இரண்டு", "மூன்று", "நான்கு", "ஐந்து", "ஆறு", "ஏழு", "எட்டு", "ஒன்பது", "பத்து",
            "பதினொன்று", "பன்னிரண்டு", "பதிமூன்று", "பதினான்கு", "பதினைந்து", "பதினாறு", "பதினேழு", "பதினெட்டு", "பத்தொன்பது",
        )
        private val TA_TENS = mapOf(
            20 to "இருபது", 30 to "முப்பது", 40 to "நாற்பது", 50 to "ஐம்பது", 60 to "அறுபது",
            70 to "எழுபது", 80 to "எண்பது", 90 to "தொண்ணூறு",
        )

        private val TE_ONES = arrayOf(
            "సున్నా", "ఒకటి", "రెండు", "మూడు", "నాలుగు", "ఐదు", "ఆరు", "ఏడు", "ఎనిమిది", "తొమ్మిది", "పది",
            "పదకొండు", "పన్నెండు", "పదమూడు", "పధ్నాలుగు", "పదిహేను", "పదహారు", "పదిహేడు", "పద్దెనిమిది", "పందొమ్మిది",
        )
        private val TE_TENS = mapOf(
            20 to "ఇరవై", 30 to "ముప్పై", 40 to "నలభై", 50 to "యాభై", 60 to "అరవై",
            70 to "డెబ్భై", 80 to "ఎనభై", 90 to "తొంభై",
        )

        // Odia: high-confidence 0-19 + tens; 21-99 composed (see class doc — flagged low-confidence area).
        private val OR_ONES = arrayOf(
            "ଶୂନ୍ୟ", "ଏକ", "ଦୁଇ", "ତିନି", "ଚାରି", "ପାଞ୍ଚ", "ଛଅ", "ସାତ", "ଆଠ", "ନଅ", "ଦଶ",
            "ଏଗାର", "ବାର", "ତେର", "ଚଉଦ", "ପନ୍ଦର", "ଷୋହଳ", "ସତର", "ଅଠର", "ଊଣାଇଶ",
        )
        private val OR_TENS = mapOf(
            20 to "କୋଡ଼ିଏ", 30 to "ତିରିଶି", 40 to "ଚାଳିଶ", 50 to "ପଚାଶ", 60 to "ଷାଠିଏ",
            70 to "ସତୁରି", 80 to "ଅଶୀ", 90 to "ନବେ",
        )

        // Hindi 0-99, standard forms.
        private val HI_WORDS = arrayOf(
            "शून्य", "एक", "दो", "तीन", "चार", "पांच", "छह", "सात", "आठ", "नौ", "दस",
            "ग्यारह", "बारह", "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस", "बीस",
            "इक्कीस", "बाईस", "तेईस", "चौबीस", "पच्चीस", "छब्बीस", "सत्ताईस", "अट्ठाईस", "उनतीस", "तीस",
            "इकतीस", "बत्तीस", "तैंतीस", "चौंतीस", "पैंतीस", "छत्तीस", "सैंतीस", "अड़तीस", "उनतालीस", "चालीस",
            "इकतालीस", "बयालीस", "तैंतालीस", "चौंतालीस", "पैंतालीस", "छियालीस", "सैंतालीस", "अड़तालीस", "उनचास", "पचास",
            "इक्यावन", "बावन", "तिरपन", "चौवन", "पचपन", "छप्पन", "सत्तावन", "अट्ठावन", "उनसठ", "साठ",
            "इकसठ", "बासठ", "तिरसठ", "चौंसठ", "पैंसठ", "छियासठ", "सड़सठ", "अड़सठ", "उनहत्तर", "सत्तर",
            "इकहत्तर", "बहत्तर", "तिहत्तर", "चौहत्तर", "पचहत्तर", "छिहत्तर", "सतहत्तर", "अठहत्तर", "उनासी", "अस्सी",
            "इक्यासी", "बयासी", "तिरासी", "चौरासी", "पचासी", "छियासी", "सत्तासी", "अट्ठासी", "नवासी", "नब्बे",
            "इक्यानवे", "बानवे", "तिरानवे", "चौरानवे", "पचानवे", "छियानवे", "सत्तानवे", "अट्ठानवे", "निन्यानवे",
        )

        // Marathi 0-99, standard forms.
        private val MR_WORDS = arrayOf(
            "शून्य", "एक", "दोन", "तीन", "चार", "पाच", "सहा", "सात", "आठ", "नऊ", "दहा",
            "अकरा", "बारा", "तेरा", "चौदा", "पंधरा", "सोळा", "सतरा", "अठरा", "एकोणीस", "वीस",
            "एकवीस", "बावीस", "तेवीस", "चोवीस", "पंचवीस", "सव्वीस", "सत्तावीस", "अठ्ठावीस", "एकोणतीस", "तीस",
            "एकतीस", "बत्तीस", "तेहेतीस", "चौतीस", "पस्तीस", "छत्तीस", "सदतीस", "अडतीस", "एकोणचाळीस", "चाळीस",
            "एकेचाळीस", "बेचाळीस", "त्रेचाळीस", "चव्वेचाळीस", "पंचेचाळीस", "सेहेचाळीस", "सत्तेचाळीस", "अठ्ठेचाळीस", "एकोणपन्नास", "पन्नास",
            "एक्कावन्न", "बावन्न", "त्रेपन्न", "चोपन्न", "पंचावन्न", "छप्पन्न", "सत्तावन्न", "अठ्ठावन्न", "एकोणसाठ", "साठ",
            "एकसष्ट", "बासष्ट", "त्रेसष्ट", "चौसष्ट", "पासष्ट", "सहासष्ट", "सदुसष्ट", "अडुसष्ट", "एकोणसत्तर", "सत्तर",
            "एक्काहत्तर", "बाहत्तर", "त्र्याहत्तर", "चौर्‍याहत्तर", "पंच्याहत्तर", "शहात्तर", "सत्याहत्तर", "अठ्ठ्याहत्तर", "एकोणऐंशी", "ऐंशी",
            "एक्क्याऐंशी", "ब्याऐंशी", "त्र्याऐंशी", "चौऱ्याऐंशी", "पंच्याऐंशी", "शहाऐंशी", "सत्त्याऐंशी", "अठ्ठ्याऐंशी", "एकोणनव्वद", "नव्वद",
            "एक्क्याण्णव", "ब्याण्णव", "त्र्याण्णव", "चौऱ्याण्णव", "पंच्याण्णव", "शहाण्णव", "सत्त्याण्णव", "अठ्ठ्याण्णव", "नव्याण्णव",
        )

        // Gujarati 0-99, standard forms.
        private val GU_WORDS = arrayOf(
            "શૂન્ય", "એક", "બે", "ત્રણ", "ચાર", "પાંચ", "છ", "સાત", "આઠ", "નવ", "દસ",
            "અગિયાર", "બાર", "તેર", "ચૌદ", "પંદર", "સોળ", "સત્તર", "અઢાર", "ઓગણીસ", "વીસ",
            "એકવીસ", "બાવીસ", "તેવીસ", "ચોવીસ", "પચીસ", "છવ્વીસ", "સત્તાવીસ", "અઠ્ઠાવીસ", "ઓગણત્રીસ", "ત્રીસ",
            "એકત્રીસ", "બત્રીસ", "તેત્રીસ", "ચોત્રીસ", "પાંત્રીસ", "છત્રીસ", "સાડત્રીસ", "આડત્રીસ", "ઓગણચાળીસ", "ચાળીસ",
            "એકતાળીસ", "બેતાળીસ", "ત્રેતાળીસ", "ચુમાળીસ", "પિસ્તાળીસ", "છેતાળીસ", "સુડતાળીસ", "અડતાળીસ", "ઓગણપચાસ", "પચાસ",
            "એકાવન", "બાવન", "ત્રેપન", "ચોપન", "પંચાવન", "છપ્પન", "સત્તાવન", "અઠ્ઠાવન", "ઓગણસાઠ", "સાઠ",
            "એકસઠ", "બાસઠ", "ત્રેસઠ", "ચોસઠ", "પાંસઠ", "છાસઠ", "સડસઠ", "અડસઠ", "અગણોસિત્તેર", "સિત્તેર",
            "એકોતેર", "બોતેર", "ત્યોતેર", "ચુમોતેર", "પંચોતેર", "છોતેર", "સિત્યોતેર", "ઇઠોતેર", "ઓગણ્યાએંસી", "એંસી",
            "એક્યાસી", "બ્યાસી", "ત્યાસી", "ચોર્યાસી", "પંચાસી", "છ્યાસી", "સત્યાસી", "અઠ્યાસી", "નેવ્યાસી", "નેવું",
            "એકાણું", "બાણું", "ત્રાણું", "ચોરાણું", "પંચાણું", "છન્નુ", "સત્તાણું", "અઠ્ઠાણું", "નવ્વાણું",
        )

        // Bengali 0-99, standard forms.
        private val BN_WORDS = arrayOf(
            "শূন্য", "এক", "দুই", "তিন", "চার", "পাঁচ", "ছয়", "সাত", "আট", "নয়", "দশ",
            "এগারো", "বারো", "তেরো", "চৌদ্দ", "পনেরো", "ষোলো", "সতেরো", "আঠারো", "উনিশ", "বিশ",
            "একুশ", "বাইশ", "তেইশ", "চব্বিশ", "পঁচিশ", "ছাব্বিশ", "সাতাশ", "আটাশ", "ঊনত্রিশ", "ত্রিশ",
            "একত্রিশ", "বত্রিশ", "তেত্রিশ", "চৌত্রিশ", "পঁয়ত্রিশ", "ছত্রিশ", "সাঁইত্রিশ", "আটত্রিশ", "ঊনচল্লিশ", "চল্লিশ",
            "একচল্লিশ", "বিয়াল্লিশ", "তেতাল্লিশ", "চুয়াল্লিশ", "পঁয়তাল্লিশ", "ছেচল্লিশ", "সাতচল্লিশ", "আটচল্লিশ", "ঊনপঞ্চাশ", "পঞ্চাশ",
            "একান্ন", "বাহান্ন", "তিপ্পান্ন", "চুয়ান্ন", "পঞ্চান্ন", "ছাপ্পান্ন", "সাতান্ন", "আটান্ন", "ঊনষাট", "ষাট",
            "একষট্টি", "বাষট্টি", "তেষট্টি", "চৌষট্টি", "পঁয়ষট্টি", "ছেষট্টি", "সাতষট্টি", "আটষট্টি", "ঊনসত্তর", "সত্তর",
            "একাত্তর", "বাহাত্তর", "তিয়াত্তর", "চুয়াত্তর", "পঁচাত্তর", "ছিয়াত্তর", "সাতাত্তর", "আটাত্তর", "ঊনআশি", "আশি",
            "একাশি", "বিরাশি", "তিরাশি", "চুরাশি", "পঁচাশি", "ছিয়াশি", "সাতাশি", "আটাশি", "ঊননব্বই", "নব্বই",
            "একানব্বই", "বিরানব্বই", "তিরানব্বই", "চুরানব্বই", "পঁচানব্বই", "ছিয়ানব্বই", "সাতানব্বই", "আটানব্বই", "নিরানব্বই",
        )
    }
}
