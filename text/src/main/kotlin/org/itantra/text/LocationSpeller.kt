package org.itantra.text

import org.itantra.core.Lang
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns a GPS fix (and, for [distancePhrase], a second fix — usually "my own location") into a
 * short, localized sentence, e.g. `"location 12.97 north, 77.59 east, accuracy 15 metres"`.
 *
 * The numbers are emitted as plain ASCII decimal text (`"12.97"`), not spelled out here — this
 * class only supplies the sentence template and the compass/unit words per [Lang]; the embedded
 * numbers get spoken by [IndicNormalizer] when the sentence is later normalized (see
 * [IndicTextPipeline.plan], which appends [phrase] as one more plain-text segment for a message
 * that carries a location, so it goes through the same `PLAIN_NUMBER_RE`/decimal-point path as
 * any other number in a message).
 */
object LocationSpeller {

    data class Words(
        val location: String,
        val north: String,
        val south: String,
        val east: String,
        val west: String,
        val accuracy: String,
        val metres: String,
        val distance: String,
    )

    private val EN = Words("location", "north", "south", "east", "west", "accuracy", "metres", "distance")
    private val HI = Words("स्थान", "उत्तर", "दक्षिण", "पूर्व", "पश्चिम", "सटीकता", "मीटर", "दूरी")
    private val MR = Words("स्थान", "उत्तर", "दक्षिण", "पूर्व", "पश्चिम", "अचूकता", "मीटर", "अंतर")
    private val GU = Words("સ્થાન", "ઉત્તર", "દક્ષિણ", "પૂર્વ", "પશ્ચિમ", "ચોકસાઈ", "મીટર", "અંતર") // best-effort, needs native review
    private val BN = Words("অবস্থান", "উত্তর", "দক্ষিণ", "পূর্ব", "পশ্চিম", "নির্ভুলতা", "মিটার", "দূরত্ব")
    private val OR = Words("ଅବସ୍ଥାନ", "ଉତ୍ତର", "ଦକ୍ଷିଣ", "ପୂର୍ବ", "ପଶ୍ଚିମ", "ସଠିକତା", "ମିଟର", "ଦୂରତା") // low confidence, needs native review
    private val KN = Words("ಸ್ಥಳ", "ಉತ್ತರ", "ದಕ್ಷಿಣ", "ಪೂರ್ವ", "ಪಶ್ಚಿಮ", "ನಿಖರತೆ", "ಮೀಟರ್", "ದೂರ") // best-effort, needs native review
    private val ML = Words("സ്ഥലം", "വടക്ക്", "തെക്ക്", "കിഴക്ക്", "പടിഞ്ഞാറ്", "കൃത്യത", "മീറ്റർ", "ദൂരം") // best-effort, needs native review
    private val TA = Words("இடம்", "வடக்கு", "தெற்கு", "கிழக்கு", "மேற்கு", "துல்லியம்", "மீட்டர்", "தூரம்") // best-effort, needs native review
    private val TE = Words("స్థానం", "ఉత్తరం", "దక్షిణం", "తూర్పు", "పడమర", "ఖచ్చితత్వం", "మీటర్లు", "దూరం") // best-effort, needs native review

    private val map = mapOf(
        Lang.EN to EN, Lang.HI to HI, Lang.MR to MR, Lang.GU to GU, Lang.BN to BN,
        Lang.OR to OR, Lang.KN to KN, Lang.ML to ML, Lang.TA to TA, Lang.TE to TE,
    )

    fun wordsOf(lang: Lang): Words = map[lang] ?: EN

    /** `"location 12.97 north, 77.59 east, accuracy 15 metres"` (accuracy clause omitted if
     * [accuracyM] is null or negative). */
    fun phrase(lat: Double, lon: Double, accuracyM: Float?, lang: Lang): String {
        val w = wordsOf(lang)
        val latAbs = "%.2f".format(abs(lat))
        val lonAbs = "%.2f".format(abs(lon))
        val ns = if (lat >= 0) w.north else w.south
        val ew = if (lon >= 0) w.east else w.west
        val base = "${w.location} $latAbs $ns, $lonAbs $ew"
        return if (accuracyM != null && accuracyM >= 0f) {
            "$base, ${w.accuracy} ${"%.0f".format(accuracyM)} ${w.metres}"
        } else {
            base
        }
    }

    /** Great-circle distance in metres between two fixes (haversine, spherical-Earth approximation). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial compass bearing in degrees `[0, 360)` from `(lat1,lon1)` to `(lat2,lon2)`. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** `"distance 350 metres, north-east"` from `(fromLat,fromLon)` (usually "my own location") to
     * `(toLat,toLon)` (the sender's) — for a received message's card, not spoken TTS. */
    fun distancePhrase(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, lang: Lang): String {
        val w = wordsOf(lang)
        val d = distanceMeters(fromLat, fromLon, toLat, toLon)
        val dir = compassWord(bearingDegrees(fromLat, fromLon, toLat, toLon), w)
        val distText = if (d >= 1000) "%.1f km".format(d / 1000) else "${"%.0f".format(d)} ${w.metres}"
        return "${w.distance} $distText, $dir"
    }

    private fun compassWord(bearing: Double, w: Words): String = when {
        bearing < 22.5 || bearing >= 337.5 -> w.north
        bearing < 67.5 -> "${w.north}-${w.east}"
        bearing < 112.5 -> w.east
        bearing < 157.5 -> "${w.south}-${w.east}"
        bearing < 202.5 -> w.south
        bearing < 247.5 -> "${w.south}-${w.west}"
        bearing < 292.5 -> w.west
        else -> "${w.north}-${w.west}"
    }
}
