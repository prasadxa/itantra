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
 * Dark base is the deep indigo night from the app logo (#1E1B4B family); light base is a clean
 * off-white with ink text. All pairings below are contrast-checked (WCAG relative-luminance
 * formula) for >=4.5:1 text contrast against their intended background, or >=3:1 where the colour
 * is only ever used for a large glyph/fill/border (status dot, chip background) rather than text.
 */
object Brand {
    // Dark theme (indigo night) --------------------------------------------------------------
    val Indigo = Color(0xFF1E1B4B)
    val IndigoContainer = Color(0xFF272357)
    val IndigoContainerHigh = Color(0xFF322C6B)
    val IndigoContainerLow = Color(0xFF17143A)
    val IndigoOutline = Color(0xFF3E3878)
    val OnIndigo = Color(0xFFF3F1FA)
    val OnIndigoMuted = Color(0xFFC3BEDE)

    // Light theme (paper) ----------------------------------------------------------------------
    val Paper = Color(0xFFFAF9F6)
    val Ink = Color(0xFF1C1B2E)
    val InkMuted = Color(0xFF5A5678)
    val LightContainer = Color(0xFFF1EFF9)
    val LightContainerHigh = Color(0xFFE7E3F5)
    val LightOutline = Color(0xFFD6D2E8)

    // Accents ------------------------------------------------------------------------------------
    val Saffron = Color(0xFFF97316) // dark-theme primary (5.7:1 on Indigo)
    val SaffronOnLight = Color(0xFFB4530A) // light-theme primary (4.8:1 on Paper)

    val Teal = Color(0xFF0F9D8A) // dark-theme secondary / receive (4.7:1 on Indigo)
    val TealOnLight = Color(0xFF0F766E) // light-theme secondary (5.2:1 on Paper)

    /** True flag green — used only as a fill/dot/border (>=3:1 non-text threshold), never as text. */
    val IndiaGreen = Color(0xFF138808)
    /** Text-safe "connected/ready" tone for dark theme (7.0:1 on Indigo). */
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
    onPrimary = Color(0xFF1A1200),
    secondary = Brand.Teal,
    onSecondary = Color(0xFF00201B),
    tertiary = Brand.IndiaGreenOnDark,
    onTertiary = Color(0xFF04210B),
    tertiaryContainer = Brand.IndiaGreen.copy(alpha = 0.22f).compositeOverIndigo(),
    onTertiaryContainer = Brand.IndiaGreenOnDark,
    background = Brand.Indigo,
    onBackground = Brand.OnIndigo,
    surface = Brand.Indigo,
    onSurface = Brand.OnIndigo,
    surfaceVariant = Brand.IndigoContainer,
    onSurfaceVariant = Brand.OnIndigoMuted,
    surfaceContainer = Brand.IndigoContainer,
    surfaceContainerHigh = Brand.IndigoContainerHigh,
    surfaceContainerLow = Brand.IndigoContainerLow,
    outline = Brand.IndigoOutline,
    outlineVariant = Brand.IndigoOutline,
    error = Brand.Red,
    onError = Color.White,
    errorContainer = Color(0xFF4A1414),
    onErrorContainer = Color(0xFFFFD9D9),
)

private val LightColors = lightColorScheme(
    primary = Brand.SaffronOnLight,
    onPrimary = Color.White,
    secondary = Brand.TealOnLight,
    onSecondary = Color.White,
    tertiary = Brand.IndiaGreenOnLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDF3D8),
    onTertiaryContainer = Brand.IndiaGreenOnLight,
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.Paper,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.LightContainer,
    onSurfaceVariant = Brand.InkMuted,
    surfaceContainer = Brand.LightContainer,
    surfaceContainerHigh = Brand.LightContainerHigh,
    surfaceContainerLow = Brand.Paper,
    outline = Brand.LightOutline,
    outlineVariant = Brand.LightOutline,
    error = Brand.RedOnLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** Cheap opaque approximation of alpha-compositing a colour over the Indigo background, since
 * Material3's ColorScheme fields are plain (non-alpha-aware) colours. */
private fun Color.compositeOverIndigo(): Color {
    val bg = Brand.Indigo
    val a = this.alpha
    return Color(
        red = red * a + bg.red * (1 - a),
        green = green * a + bg.green * (1 - a),
        blue = blue * a + bg.blue * (1 - a),
        alpha = 1f,
    )
}

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
