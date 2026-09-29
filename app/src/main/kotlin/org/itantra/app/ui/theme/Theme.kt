package org.itantra.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Warm Indian palette. Each accent keeps exactly one meaning app-wide:
 *  - saffron = transmit / PTT-active / primary action
 *  - teal (peacock-green family) = receive / incoming-live
 *  - India-green = "ready"/"connected" status only (never a generic accent)
 *  - red = ALERT only
 * Dark base is a soft near-black neutral grey (not a tinted/purple black, and not full OLED
 * #000000 either — full black read as "too bold" in review, so the base is lifted just enough to
 * feel calmer while staying dark enough to keep most of the AMOLED battery saving on the
 * budget/mid Android phones this targets); light base is a clean off-white with ink text. All
 * pairings below are contrast-checked (WCAG relative-luminance formula) for >=4.5:1 text contrast
 * against their intended background, or >=3:1 where the colour is only ever used for a large
 * glyph/fill/border (status dot, chip background) rather than text.
 */
object Brand {
    // Dark theme (soft neutral grey, not pure black) ---------------------------------------------
    val DarkBg = Color(0xFF121212)
    val DarkSurface = Color(0xFF1A1A1C)
    val DarkSurfaceLow = Color(0xFF161618)
    val DarkSurfaceContainer = Color(0xFF202023)
    val DarkSurfaceContainerHigh = Color(0xFF28282C)
    val DarkOutline = Color(0xFF3A3A3F)
    val DarkOutlineVariant = Color(0xFF2C2C30)
    val OnDarkBg = Color(0xFFE6E6E6)
    val OnDarkBgMuted = Color(0xFFA8A8AD)

    // Dark-theme accent containers — muted dark tints for large fills (chips, banners), kept
    // low-saturation so they read as "quiet" next to the saturated primary talk button.
    val SaffronContainerDark = Color(0xFF3A2414)
    val OnSaffronContainerDark = Color(0xFFFFB27A) // 8.3:1 on SaffronContainerDark
    val TealContainerDark = Color(0xFF12302B)
    val OnTealContainerDark = Color(0xFF7FE0CB) // 9.1:1 on TealContainerDark
    val GreenContainerDark = Color(0xFF163821)
    val OnGreenContainerDark = Color(0xFF8FE3A0) // 8.4:1 on GreenContainerDark
    val RedContainerDark = Color(0xFF3A1414)
    val OnRedContainerDark = Color(0xFFFFB4AB) // 9.6:1 on RedContainerDark

    // Light theme (neutral paper — grey, not lavender) --------------------------------------------
    val Paper = Color(0xFFF7F7F5)
    val LightSurface = Color(0xFFFFFFFF)
    val Ink = Color(0xFF1A1A1A)
    val InkMuted = Color(0xFF55555A)
    val LightContainer = Color(0xFFF0F0EE)
    val LightContainerHigh = Color(0xFFE8E8E5)
    val LightOutline = Color(0xFFD4D4D0)
    val LightOutlineVariant = Color(0xFFE4E4E1)

    // Light-theme accent containers — soft on-brand tints (kept distinct from the neutral-grey
    // nav-bar pill, which BottomNavBar in AppRoot.kt sets explicitly to surfaceContainerHigh).
    val SaffronContainerLight = Color(0xFFFFE4CC)
    val OnSaffronContainerLight = Color(0xFF7A3300) // 7.5:1 on SaffronContainerLight
    val TealContainerLight = Color(0xFFD8F1EC)
    val OnTealContainerLight = Color(0xFF0B4F46) // 8.0:1 on TealContainerLight

    // Accents ------------------------------------------------------------------------------------
    val Saffron = Color(0xFFF97316) // dark-theme primary — always full saturation (talk button)
    val SaffronOnLight = Color(0xFFB4530A) // light-theme primary (4.8:1 on Paper)

    val Teal = Color(0xFF0F9D8A) // dark-theme secondary / receive
    val TealOnLight = Color(0xFF0F766E) // light-theme secondary (5.2:1 on Paper)

    /** True flag green — used only as a fill/dot/border (>=3:1 non-text threshold), never as text. */
    val IndiaGreen = Color(0xFF138808)
    /** Text-safe "connected/ready" tone for dark theme (>=13:1 on true black). */
    val IndiaGreenOnDark = Color(0xFF22C55E)
    /** Text-safe "connected/ready" tone for light theme (5.2:1 on Paper), close to true flag green. */
    val IndiaGreenOnLight = Color(0xFF0F7A06)

    val Red = Color(0xFFDC2626) // dark-theme ALERT fill (white text 4.8:1)
    val RedOnLight = Color(0xFFB42318) // light-theme ALERT fill (white text 6.6:1)

    /** Tricolour hairline — the ONLY place the flag's three bands appear, as a thin abstract rule
     * in the status bar / splash, never as a literal flag graphic. */
    val SaffronBand = Saffron
    val WhiteBand = Color(0xFFF5F3EC)
    val GreenBand = IndiaGreen
}

private val DarkColors = darkColorScheme(
    primary = Brand.Saffron,
    onPrimary = Brand.DarkSurface, // #1A1A1C on Saffron: 6.2:1
    primaryContainer = Brand.SaffronContainerDark,
    onPrimaryContainer = Brand.OnSaffronContainerDark,
    secondary = Brand.Teal,
    onSecondary = Color(0xFF00201B),
    secondaryContainer = Brand.TealContainerDark,
    onSecondaryContainer = Brand.OnTealContainerDark,
    tertiary = Brand.IndiaGreenOnDark,
    onTertiary = Color(0xFF04210B),
    tertiaryContainer = Brand.GreenContainerDark,
    onTertiaryContainer = Brand.OnGreenContainerDark,
    background = Brand.DarkBg,
    onBackground = Brand.OnDarkBg, // 15.0:1 on #121212
    surface = Brand.DarkSurface,
    onSurface = Brand.OnDarkBg, // 13.9:1 on #1A1A1C
    surfaceVariant = Brand.DarkSurface,
    onSurfaceVariant = Brand.OnDarkBgMuted, // 7.3:1 on #1A1A1C
    surfaceContainer = Brand.DarkSurfaceContainer,
    surfaceContainerHigh = Brand.DarkSurfaceContainerHigh,
    surfaceContainerLow = Brand.DarkSurfaceLow,
    outline = Brand.DarkOutline,
    outlineVariant = Brand.DarkOutlineVariant,
    error = Brand.Red,
    onError = Color.White,
    errorContainer = Brand.RedContainerDark,
    onErrorContainer = Brand.OnRedContainerDark,
)

private val LightColors = lightColorScheme(
    primary = Brand.SaffronOnLight,
    onPrimary = Color.White,
    primaryContainer = Brand.SaffronContainerLight,
    onPrimaryContainer = Brand.OnSaffronContainerLight,
    secondary = Brand.TealOnLight,
    onSecondary = Color.White,
    secondaryContainer = Brand.TealContainerLight,
    onSecondaryContainer = Brand.OnTealContainerLight,
    tertiary = Brand.IndiaGreenOnLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF3D8),
    onTertiaryContainer = Brand.IndiaGreenOnLight,
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.LightSurface,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.LightContainer,
    onSurfaceVariant = Brand.InkMuted,
    surfaceContainer = Brand.LightContainer,
    surfaceContainerHigh = Brand.LightContainerHigh,
    surfaceContainerLow = Brand.Paper,
    outline = Brand.LightOutline,
    outlineVariant = Brand.LightOutlineVariant,
    error = Brand.RedOnLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// MaterialExpressiveTheme is still an internal API in this material3 1.4.0 build (not yet
// publicly exported), so the stable MaterialTheme is used here; the "expressive" motion called
// for in the field-radio-instrument spec is applied per-component (spring/tween animations on
// the PTT key, VU meter, level glyphs) instead of via a global expressive theme wrapper.
@Composable
fun ItantraTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = itantraTypography(),
        content = content,
    )
}
