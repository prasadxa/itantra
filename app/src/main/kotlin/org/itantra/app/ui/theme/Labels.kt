package org.itantra.app.ui.theme

import org.itantra.core.Lang

/**
 * Short microcopy that must always show in the *conversation* language ([AppRepository.language]
 * / a message's own [org.itantra.core.Lang]), independent of the device/app UI locale — e.g. the
 * PTT button label and an incoming ALERT's headline word should read in the script the two
 * walkie-talkie ends are actually speaking, not in whatever locale the phone's settings are in.
 * General app-chrome strings (menus, onboarding, about) instead live in res/values-<lang>/strings.xml
 * and follow the normal Android resource-locale mechanism (see LocaleManager.kt).
 *
 * Translations below are best-effort (author is not a native speaker of most of these languages).
 * hi/en/bn are used with higher confidence; gu, mr, kn, ml, ta, te and especially or should get a
 * native-speaker pass before this ships — flagged inline.
 */
object Labels {
    /** "Press to talk" / hold-to-talk button caption, kept short per the design spec. */
    val ptt: Map<Lang, String> = mapOf(
        Lang.HI to "बोलने के लिए दबाएँ", // given verbatim by design spec
        Lang.EN to "Press to talk",
        Lang.BN to "কথা বলতে চাপুন",
        Lang.GU to "બોલવા માટે દબાવો", // best-effort, needs native review
        Lang.MR to "बोलण्यासाठी दाबा",
        Lang.KN to "ಮಾತನಾಡಲು ಒತ್ತಿ", // best-effort, needs native review
        Lang.ML to "സംസാരിക്കാൻ അമർത്തുക", // best-effort, needs native review
        Lang.TA to "பேச அழுத்தவும்", // best-effort, needs native review
        Lang.TE to "మాట్లాడటానికి నొక్కండి", // best-effort, needs native review
        Lang.OR to "କଥା କହିବାକୁ ଦବାନ୍ତୁ", // low confidence, needs native review
    )

    /** Headline word shown on the red ALERT card, in the language of the alert message itself. */
    val alert: Map<Lang, String> = mapOf(
        Lang.HI to "चेतावनी",
        Lang.EN to "ALERT",
        Lang.BN to "সতর্কতা",
        Lang.GU to "ચેતવણી", // best-effort, needs native review
        Lang.MR to "इशारा",
        Lang.KN to "ಎಚ್ಚರಿಕೆ", // best-effort, needs native review
        Lang.ML to "മുന്നറിയിപ്പ്", // best-effort, needs native review
        Lang.TA to "எச்சரிக்கை", // best-effort, needs native review
        Lang.TE to "హెచ్చరిక", // best-effort, needs native review
        Lang.OR to "ସତର୍କତା", // low confidence, needs native review
    )

    data class SosSet(val needHelp: String, val medical: String, val safe: String, val supplies: String)

    /** Quick-reply SOS chips, one full set per language so the chip reads in the language the
     * sender is currently speaking (same rationale as [ptt]/[alert] above). */
    val sos: Map<Lang, SosSet> = mapOf(
        Lang.HI to SosSet("मदद चाहिए", "चिकित्सा आपातकाल", "सुरक्षित हूँ", "पानी/भोजन चाहिए"),
        Lang.EN to SosSet("Need help", "Medical emergency", "Safe", "Water/food needed"),
        Lang.BN to SosSet("সাহায্য দরকার", "চিকিৎসা জরুরি", "নিরাপদ আছি", "পানি/খাবার দরকার"),
        Lang.GU to SosSet("મદદ જોઈએ", "તબીબી કટોકટી", "સુરક્ષિત છું", "પાણી/ખોરાક જોઈએ"), // best-effort
        Lang.MR to SosSet("मदत हवी", "वैद्यकीय आणीबाणी", "सुरक्षित आहे", "पाणी/अन्न हवे"),
        Lang.KN to SosSet("ಸಹಾಯ ಬೇಕು", "ವೈದ್ಯಕೀಯ ತುರ್ತು", "ಸುರಕ್ಷಿತ", "ನೀರು/ಆಹಾರ ಬೇಕು"), // best-effort
        Lang.ML to SosSet("സഹായം വേണം", "മെഡിക്കൽ എമർജൻസി", "സുരക്ഷിതം", "വെള്ളം/ഭക്ഷണം വേണം"), // best-effort
        Lang.TA to SosSet("உதவி தேவை", "மருத்துவ அவசரநிலை", "பாதுகாப்பாக உள்ளேன்", "தண்ணீர்/உணவு தேவை"), // best-effort
        Lang.TE to SosSet("సహాయం కావాలి", "వైద్య అత్యవసరం", "సురక్షితం", "నీరు/ఆహారం కావాలి"), // best-effort
        Lang.OR to SosSet("ସାହାଯ୍ୟ ଦରକାର", "ଚିକିତ୍ସା ଜରୁରୀ", "ସୁରକ୍ଷିତ", "ପାଣି/ଖାଦ୍ୟ ଦରକାର"), // low confidence
    )

    /** The ten onboarding-hero greetings, one native script per [Lang] plus English, cycled with
     * a crossfade on onboarding step 1. Order matches [Lang.entries]. */
    val greetings: List<String> = listOf(
        "नमस्ते", "નમસ્તે", "नमस्कार", "ನಮಸ್ಕಾರ", "നമസ്കാരം", "வணக்கம்", "నమస్కారం", "ନମସ୍କାର", "নমস্কার", "Hello",
    )

    /** The six big one-tap SOS-sheet templates (see `org.itantra.app.ui.sos.SosSheet`) — a
     * different, larger set than [sos]'s four quick-reply chat chips. */
    data class SosTemplateSet(
        val rescue: String,
        val medical: String,
        val fire: String,
        val flood: String,
        val trapped: String,
        val safe: String,
    )

    val sosTemplates: Map<Lang, SosTemplateSet> = mapOf(
        Lang.HI to SosTemplateSet("बचाव चाहिए", "चिकित्सा आपातकाल", "आग लगी है", "बाढ़/पानी बढ़ रहा है", "फंस गए हैं", "सुरक्षित हूँ"),
        Lang.EN to SosTemplateSet("Need rescue", "Medical emergency", "Fire", "Flood/water rising", "Trapped", "I am safe"),
        Lang.BN to SosTemplateSet("উদ্ধার দরকার", "চিকিৎসা জরুরি", "আগুন লেগেছে", "বন্যা/পানি বাড়ছে", "আটকে গেছি", "নিরাপদ আছি"),
        Lang.GU to SosTemplateSet("બચાવ જોઈએ", "તબીબી કટોકટી", "આગ લાગી છે", "પૂર/પાણી વધી રહ્યું છે", "ફસાયેલા છીએ", "સુરક્ષિત છું"), // best-effort
        Lang.MR to SosTemplateSet("बचाव हवा", "वैद्यकीय आणीबाणी", "आग लागली आहे", "पूर/पाणी वाढत आहे", "अडकलो आहोत", "सुरक्षित आहे"),
        Lang.KN to SosTemplateSet("ರಕ್ಷಣೆ ಬೇಕು", "ವೈದ್ಯಕೀಯ ತುರ್ತು", "ಬೆಂಕಿ ಹೊತ್ತಿದೆ", "ಪ್ರವಾಹ/ನೀರು ಏರುತ್ತಿದೆ", "ಸಿಕ್ಕಿಬಿದ್ದಿದ್ದೇವೆ", "ಸುರಕ್ಷಿತ"), // best-effort, needs native review
        Lang.ML to SosTemplateSet("രക്ഷ വേണം", "മെഡിക്കൽ എമർജൻസി", "തീ പിടിച്ചു", "വെള്ളപ്പൊക്കം/വെള്ളം ഉയരുന്നു", "കുടുങ്ങിപ്പോയി", "സുരക്ഷിതം"), // best-effort, needs native review
        Lang.TA to SosTemplateSet("மீட்பு தேவை", "மருத்துவ அவசரநிலை", "தீ பிடித்தது", "வெள்ளம்/நீர் உயர்கிறது", "சிக்கிக்கொண்டோம்", "பாதுகாப்பாக உள்ளேன்"), // best-effort, needs native review
        Lang.TE to SosTemplateSet("రక్షణ కావాలి", "వైద్య అత్యవసరం", "మంటలు అంటుకున్నాయి", "వరద/నీరు పెరుగుతోంది", "చిక్కుకుపోయాము", "సురక్షితం"), // best-effort, needs native review
        Lang.OR to SosTemplateSet("ଉଦ୍ଧାର ଦରକାର", "ଚିକିତ୍ସା ଜରୁରୀ", "ନିଆଁ ଲାଗିଛି", "ବନ୍ୟା/ପାଣି ବଢୁଛି", "ଅଟକି ଯାଇଛୁ", "ସୁରକ୍ଷିତ"), // low confidence, needs native review
    )

    fun pttOf(lang: Lang): String = ptt[lang] ?: ptt.getValue(Lang.EN)
    fun alertOf(lang: Lang): String = alert[lang] ?: alert.getValue(Lang.EN)
    fun sosOf(lang: Lang): SosSet = sos[lang] ?: sos.getValue(Lang.EN)
    fun sosTemplatesOf(lang: Lang): SosTemplateSet = sosTemplates[lang] ?: sosTemplates.getValue(Lang.EN)
}
