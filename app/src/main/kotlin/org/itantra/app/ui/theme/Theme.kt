package org.itantra.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Restrained "field radio instrument" palette. Each accent has exactly one meaning app-wide:
 * saffron = transmit/PTT-active/primary action, teal = incoming/live-received, red = ALERT only.
 */
object Brand {
    val DarkSurface = Color(0xFF14161B)
    val DarkSurfaceContainer = Color(0xFF1C1F26)
    val DarkOutline = Color(0xFF2A2E37)
    val DarkOnSurface = Color(0xFFE8E6E1) // warm white

    val Paper = Color(0xFFF6F4EF)
    val Ink = Color(0xFF1A1A1A)
    val LightOutline = Color(0xFFCBC7BA)

    val Saffron = Color(0xFFF97316)
    val Teal = Color(0xFF2DD4BF)
    val Red = Color(0xFFEF4444)

    // Darkened for AA contrast on the light (paper) surface.
    val SaffronOnLight = Color(0xFFB4530A)
    val TealOnLight = Color(0xFF0F766E)
    val RedOnLight = Color(0xFFB42318)
}

private val DarkColors = darkColorScheme(
    primary = Brand.Saffron,
    onPrimary = Color(0xFF1A1200),
    secondary = Brand.Teal,
    onSecondary = Color(0xFF00201B),
    background = Brand.DarkSurface,
    onBackground = Brand.DarkOnSurface,
    surface = Brand.DarkSurface,
    onSurface = Brand.DarkOnSurface,
    surfaceVariant = Brand.DarkSurfaceContainer,
    onSurfaceVariant = Color(0xFFA9A69C),
    surfaceContainer = Brand.DarkSurfaceContainer,
    surfaceContainerHigh = Color(0xFF23262E),
    surfaceContainerLow = Color(0xFF101217),
    outline = Brand.DarkOutline,
    outlineVariant = Brand.DarkOutline,
    error = Brand.Red,
    onError = Color(0xFF2B0000),
    errorContainer = Color(0xFF3A0D0D),
    onErrorContainer = Color(0xFFFFD9D9),
)

private val LightColors = lightColorScheme(
    primary = Brand.SaffronOnLight,
    onPrimary = Color.White,
    secondary = Brand.TealOnLight,
    onSecondary = Color.White,
    background = Brand.Paper,
    onBackground = Brand.Ink,
    surface = Brand.Paper,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFEDEAE2),
    onSurfaceVariant = Color(0xFF5B5848),
    surfaceContainer = Color(0xFFEDEAE2),
    surfaceContainerHigh = Color(0xFFE3E0D6),
    surfaceContainerLow = Color(0xFFF6F4EF),
    outline = Brand.LightOutline,
    outlineVariant = Brand.LightOutline,
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
