package org.itantra.app.ui

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.itantra.app.AppRepository
import org.itantra.app.CpuSampler
import org.itantra.app.Direction
import org.itantra.app.ExpectedModelFiles
import org.itantra.app.MessageMetrics
import org.itantra.app.Profile
import org.itantra.app.ui.charts.BarSeg
import org.itantra.app.ui.charts.ChartLegend
import org.itantra.app.ui.charts.Sparkline
import org.itantra.app.ui.charts.StackedBarChart
import org.itantra.app.ui.theme.MonoText
import org.itantra.core.Lang

private val COLS = listOf(56.dp, 44.dp, 56.dp, 48.dp, 56.dp, 56.dp, 56.dp, 40.dp)
private val HEADERS = listOf("DIR", "LANG", "STT", "RTF", "NET", "TTS", "E2E", "ENG")

/** TTS languages VITS actually has a voice for (see [org.itantra.app.Profile]'s doc / ModelFiles);
 * every other language falls back to Mio. Used only to label the RTF sparklines below — the STT
 * `rtf` field on [MessageMetrics] doesn't carry a TTS-engine tag, so this is the same
 * lang-to-engine mapping the rest of the app already relies on, not a separately measured value. */
private val VITS_LANGS = setOf(Lang.BN, Lang.KN, Lang.ML, Lang.MR, Lang.TA, Lang.TE)

@Composable
fun MetricsScreen() {
    val context = LocalContext.current
    val metrics by AppRepository.metrics.collectAsState()
    val messages by AppRepository.messages.collectAsState()
    val engineStatus by AppRepository.engineStatus.collectAsState()
    val profile by AppRepository.profile.collectAsState()
    var pssKb by remember { mutableStateOf<Int?>(null) }
    var javaHeapKb by remember { mutableStateOf<Long?>(null) }
    var nativeHeapKb by remember { mutableStateOf<Long?>(null) }
    // Sampled over a full 10s window per the judging spec ("idle CPU% over the last 10s").
    var cpuPercent10s by remember { mutableStateOf<Float?>(null) }
    var thermalStatus by remember { mutableStateOf<Int?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            pssKb = Debug.getPss().toInt()
            val runtime = Runtime.getRuntime()
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024
            thermalStatus = readThermalStatus(context)
            cpuPercent10s = CpuSampler.samplePercent(10_000)
        }
    }

    val modelsRoot = File(context.getExternalFilesDir(null), "models")
    val modelFiles = remember(modelsRoot) { ExpectedModelFiles.list(org.itantra.core.ModelPaths(modelsRoot)) }
    val totalModelBytes = modelFiles.sumOf { it.sizeBytes }
    val apkSizeBytes = remember {
        val info = context.applicationInfo
        (listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList()))
            .sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
    }
    val deviceLine = remember { deviceModelLine() }

    val sentCount = messages.count { it.direction == Direction.SENT }
    val receivedCount = messages.count { it.direction == Direction.RECEIVED }

    val sttVals = metrics.mapNotNull { it.sttLatencyMs }
    val netVals = metrics.mapNotNull { it.networkMs }
    val ttsVals = metrics.mapNotNull { it.ttsStartMs }
    val e2eVals = metrics.mapNotNull { it.endToEndMs }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        item {
            BadgeRow(profile, thermalStatus)
            MonoText(deviceLine, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (engineStatus != null) {
                MonoText(
                    "STT:${engineStatus!!.sttReady.tick()}  TTS:${engineStatus!!.ttsReady.tick()}  TEXT:${engineStatus!!.textReady.tick()}  LINK:${engineStatus!!.transportReady.tick()}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            SectionLabel("EFFICIENCY")
            SummaryTilesGrid(
                cpuPercent10s = cpuPercent10s,
                ramMb = pssKb?.let { it / 1024 },
                modelMb = totalModelBytes / (1024 * 1024),
                apkMb = apkSizeBytes / (1024 * 1024),
                sentCount = sentCount,
                receivedCount = receivedCount,
            )
            MonoText(
                "JAVA ${javaHeapKb?.let { "${it / 1024}MB" } ?: "…"}  ·  NATIVE ${nativeHeapKb?.let { "${it / 1024}MB" } ?: "…"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionLabel("LATENCY — per message (ms)")
            val e2eP50 = e2eVals.p50().toFloatOrNaN()
            val e2eP95 = e2eVals.p95().toFloatOrNaN()
            val bars = metrics.takeLast(16).barsWithTheme()
            if (bars.isNotEmpty()) {
                val refLines = buildList {
                    if (!e2eP50.isNaN()) add(e2eP50 to MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!e2eP95.isNaN()) add(e2eP95 to MaterialTheme.colorScheme.error)
                }
                StackedBarChart(bars = bars, refLines = refLines, modifier = Modifier.padding(top = 6.dp))
                ChartLegend(
                    listOf(
                        "STT" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        "NET" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        "TTS" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                        "OTHER" to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
                        "P50" to MaterialTheme.colorScheme.onSurfaceVariant,
                        "P95" to MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                MonoText("No message latency yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionLabel("STT RTF — real-time factor (decode / audio; target < 1.0x)")
            val vitsLangRtf = metrics.filter { it.rtf != null && it.lang in VITS_LANGS }.mapNotNull { it.rtf }.takeLast(24)
            val mioLangRtf = metrics.filter { it.rtf != null && it.lang !in VITS_LANGS }.mapNotNull { it.rtf }.takeLast(24)
            RtfRow("Mio-family langs (hi/gu/or/en)", mioLangRtf)
            Spacer(Modifier.height(8.dp))
            RtfRow("VITS-voiced langs (bn/kn/ml/mr/ta/te)", vitsLangRtf)
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionLabel("TTS RTF — synthesis (synth-time / audio; target < 1.0x), by actual engine")
            val vitsRtf = metrics.filter { it.ttsEngine == "vits" }.mapNotNull { it.ttsRtf }.takeLast(24)
            val mioRtf = metrics.filter { it.ttsEngine == "mio" }.mapNotNull { it.ttsRtf }.takeLast(24)
            RtfRow("VITS", vitsRtf)
            Spacer(Modifier.height(8.dp))
            RtfRow("Mio", mioRtf)
            val underrunTotal = metrics.sumOf { it.underrunCountDelta ?: 0 }
            val rebufferTotal = metrics.sumOf { it.rebufferPauses ?: 0 }
            MonoText(
                "Underruns total: $underrunTotal   ·   Rebuffer pauses total: $rebufferTotal",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(14.dp))
        }

        item {
            OutlinedButton(onClick = {
                exportMessage = exportCsv(
                    context, metrics, profile, pssKb, javaHeapKb, nativeHeapKb, cpuPercent10s,
                    totalModelBytes, apkSizeBytes, thermalStatus, deviceLine,
                )
            }) { Text("Export CSV") }
            exportMessage?.let { MonoText(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
            Spacer(Modifier.height(14.dp))
        }

        item {
            SectionLabel("MESSAGES")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                HEADERS.forEachIndexed { i, h ->
                    MonoText(h, Modifier.width(COLS[i]), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
            SummaryRow("AVG", sttVals.average(), netVals.average(), ttsVals.average(), e2eVals.average())
            SummaryRow("P95", sttVals.p95(), netVals.p95(), ttsVals.p95(), e2eVals.p95())
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
        }

        items(metrics.asReversed()) { m -> MetricsRow(m) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    MonoText(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun BadgeRow(profile: Profile?, thermalStatus: Int?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Badge(
            text = "PROFILE ${profile?.name ?: "…"}",
            color = if (profile == Profile.LITE) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
        )
        val (thermalLabel, thermalColor) = thermalBadge(thermalStatus)
        Badge(text = thermalLabel, color = thermalColor)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        MonoText(text, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun thermalBadge(status: Int?): Pair<String, Color> {
    if (status == null) return "THERMAL …" to MaterialTheme.colorScheme.onSurfaceVariant
    return when (status) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT ->
            "THERMAL OK" to MaterialTheme.colorScheme.tertiary
        PowerManager.THERMAL_STATUS_MODERATE ->
            "THERMAL MODERATE" to MaterialTheme.colorScheme.secondary
        else ->
            "THERMAL HIGH" to MaterialTheme.colorScheme.error
    }
}

@Composable
private fun SummaryTilesGrid(
    cpuPercent10s: Float?,
    ramMb: Int?,
    modelMb: Long,
    apkMb: Long,
    sentCount: Int,
    receivedCount: Int,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("CPU (10s)", cpuPercent10s?.let { "%.1f%%".format(it) } ?: "…", Modifier.weight(1f))
            SummaryTile("APP RAM", ramMb?.let { "${it}MB" } ?: "…", Modifier.weight(1f))
            SummaryTile("MODEL SIZE", "${modelMb}MB", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryTile("APK SIZE", "${apkMb}MB", Modifier.weight(1f))
            SummaryTile("SENT", sentCount.toString(), Modifier.weight(1f))
            SummaryTile("RECEIVED", receivedCount.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        MonoText(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
        MonoText(value, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, fontSize = 17.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun RtfRow(label: String, values: List<Float>) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonoText(
                if (values.isEmpty()) "—" else "last %.2fx".format(values.last()),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
        if (values.isNotEmpty()) {
            Sparkline(values = values, refLine = 1.0f, modifier = Modifier.padding(top = 2.dp))
        } else {
            MonoText("No samples yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun List<MessageMetrics>.barsWithTheme(): List<List<BarSeg>> {
    val c0 = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    val c1 = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    val c2 = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    val c3 = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    return map { m ->
        val stt = (m.sttLatencyMs ?: 0L).toFloat()
        val net = (m.networkMs ?: 0L).toFloat()
        val tts = (m.ttsStartMs ?: 0L).toFloat()
        val e2e = (m.endToEndMs ?: 0L).toFloat()
        val other = (e2e - stt - net - tts).coerceAtLeast(0f)
        listOf(BarSeg(stt, c0), BarSeg(net, c1), BarSeg(tts, c2), BarSeg(other, c3))
    }
}

private fun Boolean.tick() = if (this) "✓" else "—"

private fun List<Long>.p95(): Double {
    if (isEmpty()) return Double.NaN
    val sorted = sorted()
    val idx = ((sorted.size - 1) * 0.95).toInt()
    return sorted[idx].toDouble()
}

private fun List<Long>.p50(): Double {
    if (isEmpty()) return Double.NaN
    val sorted = sorted()
    val idx = ((sorted.size - 1) * 0.5).toInt()
    return sorted[idx].toDouble()
}

private fun Double.toFloatOrNaN(): Float = if (isNaN()) Float.NaN else toFloat()

private fun readThermalStatus(context: Context): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
    return try { pm.currentThermalStatus } catch (t: Throwable) { null }
}

private fun deviceModelLine(): String {
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        runCatching { Build.SOC_MODEL }.getOrNull()?.takeIf { it.isNotBlank() && it != "unknown" }
    } else null
    return "${Build.MANUFACTURER} ${Build.MODEL}" + (soc?.let { " · SoC $it" } ?: " · ${Build.HARDWARE}")
}

@Composable
private fun SummaryRow(label: String, stt: Double, net: Double, tts: Double, e2e: Double) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceContainer)) {
        MonoText(label, Modifier.width(COLS[0]), fontWeight = FontWeight.Medium)
        MonoText("", Modifier.width(COLS[1]))
        MonoText(stt.fmt(), Modifier.width(COLS[2]), textAlign = TextAlign.End)
        MonoText("", Modifier.width(COLS[3]))
        MonoText(net.fmt(), Modifier.width(COLS[4]), textAlign = TextAlign.End)
        MonoText(tts.fmt(), Modifier.width(COLS[5]), textAlign = TextAlign.End)
        MonoText(e2e.fmt(), Modifier.width(COLS[6]), textAlign = TextAlign.End)
        MonoText("", Modifier.width(COLS[7]))
    }
}

@Composable
private fun MetricsRow(m: MessageMetrics) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        MonoText(m.direction.name.take(3), Modifier.width(COLS[0]))
        MonoText(m.lang.code, Modifier.width(COLS[1]))
        MonoText(m.sttLatencyMs?.toString() ?: "—", Modifier.width(COLS[2]), textAlign = TextAlign.End)
        MonoText(m.rtf?.let { "%.2f".format(it) } ?: "—", Modifier.width(COLS[3]), textAlign = TextAlign.End)
        MonoText(m.networkMs?.toString() ?: "—", Modifier.width(COLS[4]), textAlign = TextAlign.End)
        MonoText(m.ttsStartMs?.toString() ?: "—", Modifier.width(COLS[5]), textAlign = TextAlign.End)
        MonoText(m.endToEndMs?.toString() ?: "—", Modifier.width(COLS[6]), textAlign = TextAlign.End)
        MonoText(m.ttsEngine ?: "—", Modifier.width(COLS[7]))
    }
}

private fun Double.fmt(): String = if (isNaN()) "—" else "%.0f".format(this)

private fun exportCsv(
    context: Context,
    metrics: List<MessageMetrics>,
    profile: Profile?,
    pssKb: Int?,
    javaHeapKb: Long?,
    nativeHeapKb: Long?,
    cpuPercent10s: Float?,
    modelBytes: Long,
    apkSizeBytes: Long,
    thermalStatus: Int?,
    deviceLine: String,
): String {
    return try {
        val dir = context.getExternalFilesDir(null) ?: return "No external files dir"
        val name = "itantra_metrics_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        val file = File(dir, name)
        FileWriter(file).use { w ->
            // Resource snapshot (judging efficiency criteria) as a one-row header block, then the
            // per-message telemetry table below it.
            w.append("# resource_snapshot\n")
            w.append("profile,pssKb,javaHeapKb,nativeHeapKb,cpuPercent10s,modelBytes,apkSizeBytes,thermalStatus,device\n")
            w.append(
                "${profile?.name ?: ""},${pssKb ?: ""},${javaHeapKb ?: ""},${nativeHeapKb ?: ""},${cpuPercent10s ?: ""},$modelBytes,$apkSizeBytes," +
                    "${thermalStatus ?: ""},\"${deviceLine.replace("\"", "'")}\"\n",
            )
            w.append(
                "\nid,direction,lang,priority,sttLatencyMs,rtf,networkMs,ttsStartMs,endToEndMs,timestamp," +
                    "ttsEngine,ttsRtf,preRollMs,rebufferPauses,underrunCountDelta\n",
            )
            metrics.forEach { m ->
                w.append(
                    "${m.id},${m.direction},${m.lang.code},${m.priority},${m.sttLatencyMs ?: ""},${m.rtf ?: ""}," +
                        "${m.networkMs ?: ""},${m.ttsStartMs ?: ""},${m.endToEndMs ?: ""},${m.timestamp}," +
                        "${m.ttsEngine ?: ""},${m.ttsRtf ?: ""},${m.preRollMs ?: ""},${m.rebufferPauses ?: ""},${m.underrunCountDelta ?: ""}\n",
                )
            }
        }
        "Exported to ${file.absolutePath}"
    } catch (t: Throwable) {
        "Export failed: ${t.message}"
    }
}
