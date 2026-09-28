package org.itantra.text

import org.itantra.core.Lang

/** Small per-language phrase table used by [IndicNormalizer] (currency, units, dates, time). */
internal data class LocalizedWords(
    val rupees: String,
    val paise: String,
    val percent: String,
    val point: String,
    val oclock: String,
    val minutesWord: String,
    val hoursWord: String,
    val months: Array<String>,
    val units: Map<String, String>,
)

internal object LocalizedWordsTable {
    private fun units(
        km: String, kg: String, m: String, c: String, cm: String, mm: String, l: String, ml: String,
    ) = mapOf("km" to km, "kg" to kg, "m" to m, "°C" to c, "cm" to cm, "mm" to mm, "l" to l, "ml" to ml)

    private val EN = LocalizedWords(
        "rupees", "paise", "percent", "point", "o'clock", "minutes", "hours",
        arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
        units("kilometers", "kilograms", "meters", "degrees Celsius", "centimeters", "millimeters", "liters", "milliliters"),
    )
    private val HI = LocalizedWords(
        "रुपये", "पैसे", "प्रतिशत", "दशमलव", "बजे", "मिनट", "घंटे",
        arrayOf("जनवरी", "फरवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर"),
        units("किलोमीटर", "किलोग्राम", "मीटर", "डिग्री सेल्सियस", "सेंटीमीटर", "मिलीमीटर", "लीटर", "मिलीलीटर"),
    )
    private val MR = LocalizedWords(
        "रुपये", "पैसे", "टक्के", "दशांश", "वाजता", "मिनिटे", "तास",
        arrayOf("जानेवारी", "फेब्रुवारी", "मार्च", "एप्रिल", "मे", "जून", "जुलै", "ऑगस्ट", "सप्टेंबर", "ऑक्टोबर", "नोव्हेंबर", "डिसेंबर"),
        units("किलोमीटर", "किलोग्रॅम", "मीटर", "डिग्री सेल्सिअस", "सेंटीमीटर", "मिलिमीटर", "लिटर", "मिलिलिटर"),
    )
    private val GU = LocalizedWords(
        "રૂપિયા", "પૈસા", "ટકા", "દશાંશ", "વાગ્યે", "મિનિટ", "કલાક",
        arrayOf("જાન્યુઆરી", "ફેબ્રુઆરી", "માર્ચ", "એપ્રિલ", "મે", "જૂન", "જુલાઈ", "ઓગસ્ટ", "સપ્ટેમ્બર", "ઓક્ટોબર", "નવેમ્બર", "ડિસેમ્બર"),
        units("કિલોમીટર", "કિલોગ્રામ", "મીટર", "ડિગ્રી સેલ્સિયસ", "સેન્ટિમીટર", "મિલિમીટર", "લિટર", "મિલિલિટર"),
    )
    private val BN = LocalizedWords(
        "টাকা", "পয়সা", "শতাংশ", "দশমিক", "টা", "মিনিট", "ঘণ্টা",
        arrayOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর"),
        units("কিলোমিটার", "কিলোগ্রাম", "মিটার", "ডিগ্রি সেলসিয়াস", "সেন্টিমিটার", "মিলিমিটার", "লিটার", "মিলিলিটার"),
    )
    private val OR = LocalizedWords(
        "ଟଙ୍କା", "ପଇସା", "ପ୍ରତିଶତ", "ଦଶମିକ", "ଟା", "ମିନିଟ", "ଘଣ୍ଟା",
        arrayOf("ଜାନୁଆରୀ", "ଫେବୃଆରୀ", "ମାର୍ଚ୍ଚ", "ଅପ୍ରେଲ", "ମଇ", "ଜୁନ", "ଜୁଲାଇ", "ଅଗଷ୍ଟ", "ସେପ୍ଟେମ୍ବର", "ଅକ୍ଟୋବର", "ନଭେମ୍ବର", "ଡିସେମ୍ବର"),
        units("କିଲୋମିଟର", "କିଲୋଗ୍ରାମ", "ମିଟର", "ଡିଗ୍ରୀ ସେଲସିୟସ", "ସେଣ୍ଟିମିଟର", "ମିଲିମିଟର", "ଲିଟର", "ମିଲିଲିଟର"),
    )
    private val KN = LocalizedWords(
        "ರೂಪಾಯಿ", "ಪೈಸೆ", "ಶೇಕಡಾ", "ದಶಮಾಂಶ", "ಗಂಟೆಗೆ", "ನಿಮಿಷ", "ಗಂಟೆ",
        arrayOf("ಜನವರಿ", "ಫೆಬ್ರವರಿ", "ಮಾರ್ಚ್", "ಏಪ್ರಿಲ್", "ಮೇ", "ಜೂನ್", "ಜುಲೈ", "ಆಗಸ್ಟ್", "ಸೆಪ್ಟೆಂಬರ್", "ಅಕ್ಟೋಬರ್", "ನವೆಂಬರ್", "ಡಿಸೆಂಬರ್"),
        units("ಕಿಲೋಮೀಟರ್", "ಕಿಲೋಗ್ರಾಂ", "ಮೀಟರ್", "ಡಿಗ್ರಿ ಸೆಲ್ಸಿಯಸ್", "ಸೆಂಟಿಮೀಟರ್", "ಮಿಲಿಮೀಟರ್", "ಲೀಟರ್", "ಮಿಲಿಲೀಟರ್"),
    )
    private val ML = LocalizedWords(
        "രൂപ", "പൈസ", "ശതമാനം", "ദശാംശം", "മണിക്ക്", "മിനിറ്റ്", "മണിക്കൂർ",
        arrayOf("ജനുവരി", "ഫെബ്രുവരി", "മാർച്ച്", "ഏപ്രിൽ", "മേയ്", "ജൂൺ", "ജൂലൈ", "ഓഗസ്റ്റ്", "സെപ്റ്റംബർ", "ഒക്ടോബർ", "നവംബർ", "ഡിസംബർ"),
        units("കിലോമീറ്റർ", "കിലോഗ്രാം", "മീറ്റർ", "ഡിഗ്രി സെൽഷ്യസ്", "സെന്റിമീറ്റർ", "മില്ലിമീറ്റർ", "ലിറ്റർ", "മില്ലിലിറ്റർ"),
    )
    private val TA = LocalizedWords(
        "ரூபாய்", "பைசா", "சதவீதம்", "புள்ளி", "மணிக்கு", "நிமிடம்", "மணி நேரம்",
        arrayOf("ஜனவரி", "பிப்ரவரி", "மார்ச்", "ஏப்ரல்", "மே", "ஜூன்", "ஜூலை", "ஆகஸ்ட்", "செப்டம்பர்", "அக்டோபர்", "நவம்பர்", "டிசம்பர்"),
        units("கிலோமீட்டர்", "கிலோகிராம்", "மீட்டர்", "டிகிரி செல்சியஸ்", "சென்டிமீட்டர்", "மில்லிமீட்டர்", "லிட்டர்", "மில்லிலிட்டர்"),
    )
    private val TE = LocalizedWords(
        "రూపాయలు", "పైసలు", "శాతం", "దశాంశం", "గంటలకు", "నిమిషాలు", "గంటలు",
        arrayOf("జనవరి", "ఫిబ్రవరి", "మార్చి", "ఏప్రిల్", "మే", "జూన్", "జూలై", "ఆగస్టు", "సెప్టెంబర్", "అక్టోబర్", "నవంబర్", "డిసెంబర్"),
        units("కిలోమీటర్", "కిలోగ్రామ్", "మీటర్", "డిగ్రీల సెల్సియస్", "సెంటీమీటర్", "మిల్లీమీటర్", "లీటర్", "మిల్లీలీటర్"),
    )

    private val map = mapOf(
        Lang.EN to EN, Lang.HI to HI, Lang.MR to MR, Lang.GU to GU, Lang.BN to BN,
        Lang.OR to OR, Lang.KN to KN, Lang.ML to ML, Lang.TA to TA, Lang.TE to TE,
    )

    fun of(lang: Lang): LocalizedWords = map.getValue(lang)
}
