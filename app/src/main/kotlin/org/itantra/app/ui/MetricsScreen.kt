package org.itantra.app.ui

import android.content.Context
import android.os.Debug
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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

@Composable
fun MetricsScreen() {
    val context = LocalContext.current
    val metrics by AppRepository.metrics.collectAsState()
    val engineStatus by AppRepository.engineStatus.collectAsState()
    var pssKb by remember { mutableStateOf<Int?>(null) }
    var cpuPercent by remember { mutableStateOf<Float?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            pssKb = Debug.getPss().toInt()
            cpuPercent = CpuSampler.samplePercent(1000)
        }
    }

    val modelsRoot = File(context.getExternalFilesDir(null), "models")
    val modelFiles = remember(modelsRoot) { ExpectedModelFiles.list(org.itantra.core.ModelPaths(modelsRoot)) }
    val totalModelBytes = modelFiles.sumOf { it.sizeBytes }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Metrics", style = MaterialTheme.typography.titleLarge)
        Text("RAM (PSS): ${pssKb?.let { "${it / 1024} MB" } ?: "measuring…"}")
        Text("CPU (idle sample): ${cpuPercent?.let { "%.1f%%".format(it) } ?: "measuring…"}")
        Text("Model files on disk: ${totalModelBytes / (1024 * 1024)} MB")
        if (engineStatus != null) {
            Text(
                "Engines: stt=${engineStatus!!.sttReady} tts=${engineStatus!!.ttsReady} " +
                    "text=${engineStatus!!.textReady} transport=${engineStatus!!.transportReady}",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Button(onClick = { exportMessage = exportCsv(context, metrics) }) { Text("Export CSV") }
        exportMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }

        Spacer()

        val avgSttMs = metrics.mapNotNull { it.sttLatencyMs }.average().takeIf { !it.isNaN() }
        val avgNetMs = metrics.mapNotNull { it.networkMs }.average().takeIf { !it.isNaN() }
        val avgTtsMs = metrics.mapNotNull { it.ttsStartMs }.average().takeIf { !it.isNaN() }
        val avgE2eMs = metrics.mapNotNull { it.endToEndMs }.average().takeIf { !it.isNaN() }
        Text(
            "Averages: stt=${avgSttMs?.fmt()} net=${avgNetMs?.fmt()} tts=${avgTtsMs?.fmt()} e2e=${avgE2eMs?.fmt()}",
            style = MaterialTheme.typography.labelMedium,
        )

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
            listOf("dir", "lang", "stt", "rtf", "net", "tts", "e2e").forEach {
                Text(it, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(metrics.asReversed()) { m -> MetricsRow(m) }
        }
    }
}

@Composable
private fun Spacer() = androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))

@Composable
private fun MetricsRow(m: MessageMetrics) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(m.direction.name.take(4), Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Text(m.lang.code, Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Text(m.sttLatencyMs?.toString() ?: "-", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Text(m.rtf?.let { "%.2f".format(it) } ?: "-", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Text(m.networkMs?.toString() ?: "-", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Text(m.ttsStartMs?.toString() ?: "-", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
        Text(m.endToEndMs?.toString() ?: "-", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun Double.fmt(): String = "%.0fms".format(this)

private fun exportCsv(context: Context, metrics: List<MessageMetrics>): String {
    return try {
        val dir = context.getExternalFilesDir(null) ?: return "No external files dir"
        val name = "itantra_metrics_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        val file = File(dir, name)
        FileWriter(file).use { w ->
            w.append("id,direction,lang,priority,sttLatencyMs,rtf,networkMs,ttsStartMs,endToEndMs,timestamp\n")
            metrics.forEach { m ->
                w.append("${m.id},${m.direction},${m.lang.code},${m.priority},${m.sttLatencyMs ?: ""},${m.rtf ?: ""},${m.networkMs ?: ""},${m.ttsStartMs ?: ""},${m.endToEndMs ?: ""},${m.timestamp}\n")
            }
        }
        "Exported to ${file.absolutePath}"
    } catch (t: Throwable) {
        "Export failed: ${t.message}"
    }
}
