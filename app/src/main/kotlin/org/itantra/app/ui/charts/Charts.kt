package org.itantra.app.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.itantra.app.ui.theme.MonoText
import org.itantra.app.ui.theme.PlexMono

/**
 * Small, dependency-free chart primitives for the Metrics screen, drawn with plain
 * [androidx.compose.foundation.Canvas] — no chart library, since APK size is a judged metric.
 * Colours are passed in by the caller (captured from [MaterialTheme.colorScheme] at composition
 * time, never inside the draw lambda) so these stay theme-agnostic and reusable in both themes.
 */

/** One stacked-bar segment: a value in the bar's unit (ms) and the colour it draws in. */
data class BarSeg(val value: Float, val color: Color)

/**
 * Stacked-bar-with-reference-lines chart: one bar per data point, segments stacked bottom-up,
 * plus up to two dashed horizontal reference lines (e.g. p50/p95) drawn against the same
 * value axis. A left-side ms axis with gridlines is drawn to scale so the chart reads as real
 * data, not a sparkline decoration.
 */
@Composable
fun StackedBarChart(
    bars: List<List<BarSeg>>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 120.dp,
    refLines: List<Pair<Float, Color>> = emptyList(),
    axisColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    gridColor: Color = MaterialTheme.colorScheme.outline,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontFamily = PlexMono, fontSize = 9.sp, color = axisColor)
    val maxVal = (bars.maxOfOrNull { segs -> segs.sumOf { it.value.toDouble() }.toFloat() } ?: 0f)
        .coerceAtLeast(refLines.maxOfOrNull { it.first } ?: 0f)
        .coerceAtLeast(1f)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val leftPad = 32.dp.toPx()
        val chartW = size.width - leftPad
        val chartH = size.height

        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
            val y = chartH - chartH * frac
            drawLine(gridColor.copy(alpha = 0.3f), Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1f)
            val label = (maxVal * frac).toInt().toString()
            val res = measurer.measure(label, labelStyle)
            drawText(res, topLeft = Offset((leftPad - res.size.width - 4.dp.toPx()).coerceAtLeast(0f), (y - res.size.height / 2f).coerceIn(0f, chartH - res.size.height)))
        }

        if (bars.isNotEmpty() && chartW > 0f) {
            val slot = chartW / bars.size
            val barW = (slot * 0.6f).coerceAtLeast(2f)
            bars.forEachIndexed { i, segs ->
                var yCursor = chartH
                val x = leftPad + slot * i + (slot - barW) / 2f
                segs.forEach { seg ->
                    if (seg.value <= 0f) return@forEach
                    val h = chartH * (seg.value / maxVal)
                    drawRect(seg.color, topLeft = Offset(x, yCursor - h), size = Size(barW, h))
                    yCursor -= h
                }
            }
        }

        val dash = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f)
        refLines.forEach { (value, color) ->
            val y = chartH - chartH * (value / maxVal).coerceIn(0f, 1f)
            drawLine(color, Offset(leftPad, y), Offset(size.width, y), strokeWidth = 1.6.dp.toPx(), pathEffect = dash)
        }
    }
}

/**
 * Compact legend row: a coloured square swatch + [MonoText] label per entry, wrapped so it fits
 * under a chart without pulling in a layout library.
 */
@Composable
fun ChartLegend(entries: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEach { (label, color) ->
            Row {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(4.dp))
                MonoText(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
    }
}

/**
 * A single-series sparkline: a scaled polyline over [values] (oldest first) with an optional
 * dashed reference line (e.g. RTF == 1.0x real-time) and min/last labels drawn to the sides so
 * the line reads as measured data, not decoration.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 56.dp,
    lineColor: Color = MaterialTheme.colorScheme.onSurface,
    refLine: Float? = null,
    refLineColor: Color = MaterialTheme.colorScheme.error,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val h = size.height
        val w = size.width
        val lo = (values.minOrNull() ?: 0f).coerceAtMost(refLine ?: Float.MAX_VALUE)
        val hi = (values.maxOrNull() ?: 1f).coerceAtLeast(refLine ?: 0f).coerceAtLeast(lo + 0.01f)

        refLine?.let {
            val y = h - h * ((it - lo) / (hi - lo)).coerceIn(0f, 1f)
            drawLine(
                refLineColor, Offset(0f, y), Offset(w, y),
                strokeWidth = 1.4.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
            )
        }

        if (values.size >= 2) {
            val path = Path()
            val stepX = w / (values.size - 1)
            values.forEachIndexed { i, v ->
                val x = i * stepX
                val y = h - h * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
            val lastX = (values.size - 1) * stepX
            val lastY = h - h * ((values.last() - lo) / (hi - lo)).coerceIn(0f, 1f)
            drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
        } else if (values.size == 1) {
            drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(w / 2f, h / 2f))
        }
    }
}
