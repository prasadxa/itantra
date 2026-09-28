package org.itantra.app.ui

import android.content.Context
import android.os.Debug
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.itantra.app.AppRepository
import org.itantra.app.CpuSampler
import org.itantra.app.ExpectedModelFiles
import org.itantra.app.MessageMetrics
import org.itantra.app.Profile
import org.itantra.app.ui.theme.MonoText

private val COLS = listOf(56.dp, 44.dp, 56.dp, 48.dp, 56.dp, 56.dp, 56.dp)
private val HEADERS = listOf("DIR", "LANG", "STT", "RTF", "NET", "TTS", "E2E")

@Composable
fun MetricsScreen() {
    val context = LocalContext.current
    val metrics by AppRepository.metrics.collectAsState()
    val engineStatus by AppRepository.engineStatus.collectAsState()
    val profile by AppRepository.profile.collectAsState()
    var pssKb by remember { mutableStateOf<Int?>(null) }
    var javaHeapKb by remember { mutableStateOf<Long?>(null) }
    var nativeHeapKb by remember { mutableStateOf<Long?>(null) }
    // Sampled over a full 10s window per the judging spec ("idle CPU% over the last 10s").
    var cpuPercent10s by remember { mutableStateOf<Float?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            pssKb = Debug.getPss().toInt()
            val runtime = Runtime.getRuntime()
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024
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

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        MonoText(
            "PROFILE ${profile?.name ?: "…"}  ·  RAM ${pssKb?.let { "${it / 1024}MB" } ?: "…"}  ·  " +
                "CPU(10s) ${cpuPercent10s?.let { "%.1f%%".format(it) } ?: "…"}",
            color = MaterialTheme.colorScheme.onSurface,
        )
        MonoText(
            "JAVA ${javaHeapKb?.let { "${it / 1024}MB" } ?: "…"}  ·  NATIVE ${nativeHeapKb?.let { "${it / 1024}MB" } ?: "…"}  ·  " +
                "MODELS ${totalModelBytes / (1024 * 1024)}MB  ·  APK ${apkSizeBytes / (1024 * 1024)}MB",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (engineStatus != null) {
            MonoText(
                "STT:${engineStatus!!.sttReady.tick()}  TTS:${engineStatus!!.ttsReady.tick()}  TEXT:${engineStatus!!.textReady.tick()}  LINK:${engineStatus!!.transportReady.tick()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            exportMessage = exportCsv(
                context, metrics, profile, pssKb, javaHeapKb, nativeHeapKb, cpuPercent10s, totalModelBytes, apkSizeBytes,
            )
        }) { Text("Export CSV") }
        exportMessage?.let { MonoText(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }

        Spacer(Modifier.height(14.dp))

        val sttVals = metrics.mapNotNull { it.sttLatencyMs }
        val netVals = metrics.mapNotNull { it.networkMs }
        val ttsVals = metrics.mapNotNull { it.ttsStartMs }
        val e2eVals = metrics.mapNotNull { it.endToEndMs }

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            HEADERS.forEachIndexed { i, h ->
                MonoText(h, Modifier.width(COLS[i]), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

        SummaryRow("AVG", sttVals.average(), netVals.average(), ttsVals.average(), e2eVals.average())
        SummaryRow("P95", sttVals.p95(), netVals.p95(), ttsVals.p95(), e2eVals.p95())
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(metrics.asReversed()) { m -> MetricsRow(m) }
        }
    }
}

private fun Boolean.tick() = if (this) "✓" else "—"

private fun List<Long>.p95(): Double {
    if (isEmpty()) return Double.NaN
    val sorted = sorted()
    val idx = ((sorted.size - 1) * 0.95).toInt()
    return sorted[idx].toDouble()
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
): String {
    return try {
        val dir = context.getExternalFilesDir(null) ?: return "No external files dir"
        val name = "itantra_metrics_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        val file = File(dir, name)
        FileWriter(file).use { w ->
            // Resource snapshot (judging efficiency criteria) as a one-row header block, then the
            // per-message telemetry table below it.
            w.append("# resource_snapshot\n")
            w.append("profile,pssKb,javaHeapKb,nativeHeapKb,cpuPercent10s,modelBytes,apkSizeBytes\n")
            w.append("${profile?.name ?: ""},${pssKb ?: ""},${javaHeapKb ?: ""},${nativeHeapKb ?: ""},${cpuPercent10s ?: ""},$modelBytes,$apkSizeBytes\n")
            w.append("\nid,direction,lang,priority,sttLatencyMs,rtf,networkMs,ttsStartMs,endToEndMs,timestamp\n")
            metrics.forEach { m ->
                w.append("${m.id},${m.direction},${m.lang.code},${m.priority},${m.sttLatencyMs ?: ""},${m.rtf ?: ""},${m.networkMs ?: ""},${m.ttsStartMs ?: ""},${m.endToEndMs ?: ""},${m.timestamp}\n")
            }
        }
        "Exported to ${file.absolutePath}"
    } catch (t: Throwable) {
        "Export failed: ${t.message}"
    }
}
