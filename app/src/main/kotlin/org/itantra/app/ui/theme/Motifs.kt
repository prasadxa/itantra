package org.itantra.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Procedural kolam/rangoli-style dot-grid motif, drawn entirely with [Canvas] (no bitmaps, so it
 * costs ~nothing in APK size). A grid of dots with a small pinwheel loop of four quarter-circle
 * arcs around every 2x2 block — the loop-around-dots construction that most South Indian kolam
 * and rangoli line patterns share — kept abstract and low-opacity so it reads as texture, not
 * imagery. Intentionally NOT the Ashoka Chakra, a religious symbol, or any flag/emblem motif: it
 * is a generic geometric dot-and-loop lattice.
 */
@Composable
fun KolamMotif(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    spacing: Dp = 30.dp,
    alpha: Float = 0.05f,
) {
    val lineColor = color
    Canvas(modifier) {
        val spacingPx = spacing.toPx()
        val dotR = spacingPx * 0.045f
        val cols = (size.width / spacingPx).toInt() + 2
        val rows = (size.height / spacingPx).toInt() + 2
        val dotColor = lineColor.copy(alpha = alpha * 1.6f)
        val loopColor = lineColor.copy(alpha = alpha)
        val loopStroke = Stroke(width = spacingPx * 0.035f)

        for (r in 0..rows) {
            for (c in 0..cols) {
                drawCircle(dotColor, radius = dotR, center = Offset(c * spacingPx, r * spacingPx))
            }
        }
        // Pinwheel loop of 4 quarter-circle arcs around the centre of every 2x2 block of dots —
        // each arc bulges from one corner dot towards the block centre and back to the next.
        var r = 0
        while (r < rows) {
            var c = 0
            while (c < cols) {
                val left = c * spacingPx
                val top = r * spacingPx
                val d = spacingPx
                drawArc(loopColor, startAngle = 0f, sweepAngle = 90f, useCenter = false, topLeft = Offset(left, top), size = Size(d, d), style = loopStroke)
                drawArc(loopColor, startAngle = 90f, sweepAngle = 90f, useCenter = false, topLeft = Offset(left - d, top), size = Size(d, d), style = loopStroke)
                drawArc(loopColor, startAngle = 180f, sweepAngle = 90f, useCenter = false, topLeft = Offset(left - d, top - d), size = Size(d, d), style = loopStroke)
                drawArc(loopColor, startAngle = 270f, sweepAngle = 90f, useCenter = false, topLeft = Offset(left, top - d), size = Size(d, d), style = loopStroke)
                c += 2
            }
            r += 2
        }
    }
}

/**
 * Thin 3-segment tricolour hairline — the ONLY tasteful nod to the flag anywhere in the app (no
 * Ashoka Chakra, no flag imagery). Used once, under the top status bar / on the splash.
 */
@Composable
fun TricolourHairline(modifier: Modifier = Modifier, thickness: Dp = 3.dp) {
    Row(modifier.fillMaxWidth().height(thickness)) {
        Box(Modifier.weight(1f).fillMaxHeight().background(Brand.SaffronBand))
        Box(Modifier.weight(1f).fillMaxHeight().background(Brand.WhiteBand))
        Box(Modifier.weight(1f).fillMaxHeight().background(Brand.GreenBand))
    }
}

/**
 * True (reduced motion requested) when the system "Remove animations" / animator-duration-scale
 * accessibility setting is 0 — the standard Android proxy for "prefers reduced motion" (there is
 * no dedicated API pre-Android 14). Infinite/looping decorative animations (pulses, VU bars,
 * crossfades) should check this and fall back to a static frame.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
