package org.itantra.core

/** The 10 languages required by PS 26173. [code] is ISO 639-1. */
enum class Lang(val code: String, val label: String) {
    HI("hi", "हिन्दी"), GU("gu", "ગુજરાતી"), MR("mr", "मराठी"), KN("kn", "ಕನ್ನಡ"), ML("ml", "മലയാളം"),
    TA("ta", "தமிழ்"), TE("te", "తెలుగు"), OR("or", "ଓଡ଼ିଆ"), BN("bn", "বাংলা"), EN("en", "English");

    companion object {
        fun of(code: String): Lang = entries.first { it.code == code }
    }
}
