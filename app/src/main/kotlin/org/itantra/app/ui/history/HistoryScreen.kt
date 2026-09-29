package org.itantra.app.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.itantra.app.AppRepository
import org.itantra.app.Direction
import org.itantra.app.LogEntry
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.app.ui.theme.MonoText
import org.itantra.core.Lang

/**
 * Full-transcript history: search + filters over every sent/received message currently held in
 * [AppRepository.messages] (the app's in-memory, session-scoped log — there is no on-device
 * database, matching the rest of the app's "process-wide singleton" state model; see
 * [AppRepository]'s class doc), grouped by day, with an export-to-text-file action and a replay
 * button on any message whose voice-note audio is still cached (same [TalkService.onReplay] path
 * ChatScreen's bubbles use).
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val messages by AppRepository.messages.collectAsState()
    val voiceNoteDurations by AppRepository.voiceNoteDurations.collectAsState()

    var query by remember { mutableStateOf("") }
    var alertsOnly by remember { mutableStateOf(false) }
    var langFilter by remember { mutableStateOf<Lang?>(null) }
    var langMenuOpen by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    // Device back: unwind search/filters first, then leave the screen — matches the usual
    // "back closes the local UI state before navigating away" pattern.
    BackHandler {
        when {
            query.isNotEmpty() -> query = ""
            alertsOnly || langFilter != null -> { alertsOnly = false; langFilter = null }
            else -> onBack()
        }
    }

    val filtered = remember(messages, query, alertsOnly, langFilter) {
        messages.filter { m ->
            (!alertsOnly || m.alert) &&
                (langFilter == null || m.lang == langFilter) &&
                (query.isBlank() || m.text.contains(query, ignoreCase = true))
        }.sortedByDescending { it.timestamp }
    }
    val grouped = remember(filtered) { filtered.groupBy { dayKeyOf(it.timestamp) } }
    val usedLangs = remember(messages) { messages.map { it.lang }.distinct() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.history_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.cd_history_search)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close_composer))
                        }
                    }
                },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    label = stringResource(R.string.history_filter_alerts),
                    selected = alertsOnly,
                    color = MaterialTheme.colorScheme.error,
                    onClick = { alertsOnly = !alertsOnly },
                )
                Box {
                    FilterChip(
                        label = langFilter?.label ?: stringResource(R.string.history_filter_language),
                        selected = langFilter != null,
                        color = MaterialTheme.colorScheme.secondary,
                        onClick = { langMenuOpen = true },
                    )
                    DropdownMenu(expanded = langMenuOpen, onDismissRequest = { langMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.history_filter_all_languages)) },
                            onClick = { langFilter = null; langMenuOpen = false },
                        )
                        usedLangs.forEach { lang ->
                            DropdownMenuItem(text = { Text(lang.label) }, onClick = { langFilter = lang; langMenuOpen = false })
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                MonoText(
                    "${filtered.size}/${messages.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                grouped.forEach { (day, entries) ->
                    stickyHeader(key = "hdr_$day") { DayHeader(day) }
                    items(entries, key = { it.id + it.direction.name }) { entry ->
                        HistoryRow(
                            entry = entry,
                            durationSeconds = voiceNoteDurations[entry.id],
                            onReplay = { TalkService.instance?.onReplay(entry.id) },
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedButton(onClick = {
                exportMessage = exportHistoryText(context, filtered)
            }) { Text(stringResource(R.string.history_export)) }
            exportMessage?.let {
                MonoText(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, if (selected) color else MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .background(if (selected) color.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun DayHeader(day: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 6.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            MonoText(day, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun HistoryRow(entry: LogEntry, durationSeconds: Float?, onReplay: () -> Unit) {
    val sent = entry.direction == Direction.SENT
    val accent = if (sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val bg = if (entry.alert) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (entry.alert) {
            Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.cd_alert_icon), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(if (sent) "YOU" else "PEER", color = accent, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp)).background(accent.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    MonoText(entry.lang.label, color = accent, fontSize = 11.sp)
                }
                Spacer(Modifier.width(6.dp))
                MonoText(timeOf(entry.timestamp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(
                entry.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (durationSeconds != null) {
            IconButton(onClick = onReplay, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.cd_replay), tint = accent)
            }
        }
    }
}

private fun timeOf(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun dayKeyOf(epochMs: Long): String {
    val cal = Calendar.getInstance()
    val today = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
    cal.timeInMillis = epochMs
    val sameDay = { a: Calendar, b: Calendar -> a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR) }
    return when {
        sameDay(cal, today) -> "TODAY"
        run { val y = today.clone() as Calendar; y.add(Calendar.DAY_OF_YEAR, -1); sameDay(cal, y) } -> "YESTERDAY"
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(epochMs)).uppercase(Locale.getDefault())
    }
}

private fun exportHistoryText(context: android.content.Context, entries: List<LogEntry>): String {
    return try {
        val dir = context.getExternalFilesDir(null) ?: return "No external files dir"
        val name = "itantra_history_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val file = File(dir, name)
        FileWriter(file).use { w ->
            entries.sortedBy { it.timestamp }.forEach { m ->
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(m.timestamp))
                val who = if (m.direction == Direction.SENT) "YOU" else "PEER"
                val tag = if (m.alert) " [ALERT]" else ""
                w.append("[$stamp] $who (${m.lang.code})$tag: ${m.text}\n")
            }
        }
        "Exported to ${file.absolutePath}"
    } catch (t: Throwable) {
        "Export failed: ${t.message}"
    }
}
