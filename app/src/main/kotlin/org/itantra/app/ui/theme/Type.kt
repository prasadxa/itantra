package org.itantra.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import org.itantra.app.R

/** IBM Plex Mono (OFL) — bundled ONLY for numerics/telemetry (latencies, timers, RTT, language
 * codes). Transcript prose uses the system font (Noto via fallback) so Indic scripts render
 * correctly without bundling per-script fonts and bloating the judged APK size. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

fun itantraTypography(): Typography {
    val base = Typography()
    return base.copy(
        // Transcript body: large, generous line-height, respects system font scaling (sp units).
        bodyLarge = base.bodyLarge.copy(fontSize = 20.sp, lineHeight = 28.sp),
    )
}

/** Numerics/telemetry text: status strip, timestamps, latency figures, language codes, tables. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 13.sp,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = if (color == Color.Unspecified) LocalContentColor.current else color,
        fontFamily = PlexMono,
        fontSize = fontSize,
        fontWeight = fontWeight,
        letterSpacing = 0.2.sp,
        textAlign = textAlign,
    )
}
