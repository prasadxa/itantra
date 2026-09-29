package org.itantra.transport

import org.itantra.core.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDictionaryCodecTest {

    @Test
    fun `compress then decompress round-trips for every language`() {
        val samples = mapOf(
            Lang.HI to "बाढ़ की चेतावनी: पानी का स्तर बढ़ रहा है, ऊँचे स्थान पर जाएं।",
            Lang.GU to "હમણાં જ ઇમારત ખાલી કરો, આગ ત્રીજા માળે છે.",
            Lang.MR to "आत्ताच इमारत रिकामी करा, आग तिसऱ्या मजल्यावर आहे.",
            Lang.KN to "ಪಿರಮಿಡ್ ಧ್ವನಿ ಮತ್ತು ಬೆಳಕಿನ ಪ್ರದರ್ಶನ",
            Lang.ML to "സഹ ഗുസ്തിക്കാരും ലൂണയ്ക്ക് ആദരാഞ്ജലികൾ അർപ്പിച്ചു.",
            Lang.TA to "இது வேதியியல் pH என அழைக்கப்படுகிறது.",
            Lang.TE to "చిన్న ద్వీపాలలో చాలా వరకు స్వతంత్ర దేశాలు",
            Lang.OR to "କେତେକ କ୍ରିୟାପଦ ଓ କର୍ମପଦ ମଧ୍ୟରେ ପାର୍ଥକ୍ୟ",
            Lang.BN to "অ্যারোস্মিথ তাদের সফরের অবশিষ্ট কনসার্টগুলো বাতিল করেছেন।",
            Lang.EN to "Flood warning: water level is rising, move to higher ground now.",
        )
        for ((lang, text) in samples) {
            val (scheme, compressed) = TextDictionaryCodec.compress(text, lang)
            val decoded = TextDictionaryCodec.decompress(scheme, compressed, lang)
            assertEquals("round-trip failed for $lang", text, decoded)
        }
    }

    @Test
    fun `a shipped dictionary actually shrinks a real emergency sentence`() {
        val text = "बाढ़ की चेतावनी: पानी का स्तर 4 मीटर है और बढ़ रहा है, शाम 6:30 बजे से पहले ऊंची जगह पर चले जाएं।"
        val (scheme, compressed) = TextDictionaryCodec.compress(text, Lang.HI)
        assertEquals(TextDictionaryCodec.SCHEME_DEFLATE_DICT, scheme)
        assertTrue(compressed.size < text.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `raw scheme round-trips trivially (fallback path)`() {
        val text = "42"
        val decoded = TextDictionaryCodec.decompress(TextDictionaryCodec.SCHEME_RAW, text.toByteArray(Charsets.UTF_8), Lang.EN)
        assertEquals(text, decoded)
    }

    @Test
    fun `empty text round-trips`() {
        val (scheme, compressed) = TextDictionaryCodec.compress("", Lang.HI)
        assertEquals("", TextDictionaryCodec.decompress(scheme, compressed, Lang.HI))
    }
}
