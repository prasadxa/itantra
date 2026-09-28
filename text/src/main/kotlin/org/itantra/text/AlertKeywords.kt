package org.itantra.text

import org.itantra.core.Lang

/** Per-language emergency keyword lists (native script + common romanisations). */
object AlertKeywords {

    // Checked regardless of message language (STT / typed text often mixes romanised terms).
    private val COMMON = listOf(
        "help", "sos", "s.o.s", "emergency", "danger", "fire", "flood", "earthquake", "cyclone",
        "tsunami", "landslide", "evacuate", "ambulance", "police", "injured", "trapped",
        "bachao", "madad", "khatra",
    )

    private val HI = listOf(
        "मदद", "बचाओ", "आपातकाल", "खतरा", "आग", "बाढ़", "भूकंप", "चक्रवात", "सुनामी",
        "भूस्खलन", "खाली करो", "निकलो", "एम्बुलेंस", "पुलिस", "घायल", "फंसा", "फंसे",
    )
    private val MR = listOf(
        "मदत", "वाचवा", "आणीबाणी", "धोका", "आग", "पूर", "भूकंप", "चक्रीवादळ", "त्सुनामी",
        "भूस्खलन", "बाहेर पडा", "रुग्णवाहिका", "पोलीस", "जखमी", "अडकले",
    )
    private val GU = listOf(
        "મદદ", "બચાવો", "કટોકટી", "ભય", "ખતરો", "આગ", "પૂર", "ધરતીકંપ", "વાવાઝોડું", "સુનામી",
        "ભૂસ્ખલન", "ખાલી કરો", "એમ્બ્યુલન્સ", "પોલીસ", "ઘાયલ", "ફસાયેલા",
    )
    private val BN = listOf(
        "সাহায্য", "বাঁচাও", "জরুরি", "বিপদ", "আগুন", "বন্যা", "ভূমিকম্প", "ঘূর্ণিঝড়", "সুনামি",
        "ভূমিধস", "সরিয়ে নিন", "অ্যাম্বুলেন্স", "পুলিশ", "আহত", "আটকে",
    )
    private val OR = listOf(
        "ସାହାଯ୍ୟ", "ବଞ୍ଚାଅ", "ଜରୁରୀକାଳୀନ", "ବିପଦ", "ନିଆଁ", "ବନ୍ୟା", "ଭୂକମ୍ପ", "ଘୂର୍ଣ୍ଣିବାତ୍ୟା", "ସୁନାମି",
        "ଭୂସ୍ଖଳନ", "ଖାଲି କରନ୍ତୁ", "ଆମ୍ବୁଲାନ୍ସ", "ପୋଲିସ", "ଆହତ", "ଅଟକି",
    )
    private val KN = listOf(
        "ಸಹಾಯ", "ಕಾಪಾಡಿ", "ತುರ್ತು", "ಅಪಾಯ", "ಬೆಂಕಿ", "ಪ್ರವಾಹ", "ಭೂಕಂಪ", "ಚಂಡಮಾರುತ", "ಸುನಾಮಿ",
        "ಭೂಕುಸಿತ", "ಖಾಲಿ ಮಾಡಿ", "ಆಂಬ್ಯುಲೆನ್ಸ್", "ಪೊಲೀಸ್", "ಗಾಯಗೊಂಡ", "ಸಿಕ್ಕಿಬಿದ್ದ",
    )
    private val ML = listOf(
        "സഹായം", "രക്ഷിക്കൂ", "അടിയന്തരാവസ്ഥ", "അപകടം", "തീ", "വെള്ളപ്പൊക്കം", "ഭൂകമ്പം",
        "ചുഴലിക്കാറ്റ്", "സുനാമി", "മണ്ണിടിച്ചിൽ", "ഒഴിപ്പിക്കുക", "ആംബുലൻസ്", "പോലീസ്", "പരിക്ക്", "കുടുങ്ങി",
    )
    private val TA = listOf(
        "உதவி", "காப்பாற்று", "அவசரநிலை", "ஆபத்து", "தீ", "வெள்ளம்", "நிலநடுக்கம்", "புயல்",
        "சுனாமி", "நிலச்சரிவு", "வெளியேறு", "ஆம்புலன்ஸ்", "காவல்துறை", "போலீஸ்", "காயம்", "சிக்கி",
    )
    private val TE = listOf(
        "సహాయం", "కాపాడండి", "అత్యవసర పరిస్థితి", "ప్రమాదం", "మంట", "అగ్ని", "వరద", "భూకంపం",
        "తుఫాను", "సునామి", "కొండచరియలు", "ఖాళీ చేయండి", "అంబులెన్స్", "పోలీస్", "గాయపడిన", "చిక్కుకు",
    )
    private val EN = listOf(
        "help", "sos", "emergency", "danger", "fire", "flood", "earthquake", "cyclone",
        "tsunami", "landslide", "evacuate", "ambulance", "police", "injured", "trapped",
    )

    private val perLang: Map<Lang, List<String>> = mapOf(
        Lang.HI to HI, Lang.MR to MR, Lang.GU to GU, Lang.BN to BN, Lang.OR to OR,
        Lang.KN to KN, Lang.ML to ML, Lang.TA to TA, Lang.TE to TE, Lang.EN to EN,
    )

    /** True if [text] contains any emergency keyword for [lang] (or a common romanised one). */
    fun containsAlertKeyword(text: String, lang: Lang): Boolean {
        val lower = text.lowercase()
        val list = perLang[lang].orEmpty()
        return COMMON.any { lower.contains(it) } || list.any { text.contains(it) }
    }
}
