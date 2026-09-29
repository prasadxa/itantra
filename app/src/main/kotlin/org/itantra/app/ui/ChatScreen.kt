package org.itantra.app.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import org.itantra.app.AppRepository
import org.itantra.app.Direction
import org.itantra.app.LogEntry
import org.itantra.app.MessageMetrics
import org.itantra.app.Mode
import org.itantra.app.PartialEntry
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.app.ui.components.DateHeader
import org.itantra.app.ui.components.DeliveryTicksRow
import org.itantra.app.ui.components.EmptyStateTips
import org.itantra.app.ui.components.MessageOptionsMenu
import org.itantra.app.ui.components.NearbyDevicesSheet
import org.itantra.app.ui.components.PeerCard
import org.itantra.app.ui.components.SosButton
import org.itantra.app.ui.components.TalkButton
import org.itantra.app.ui.components.UnreadDivider
import org.itantra.app.ui.components.rememberAlertFlash
import org.itantra.app.ui.components.vibrateAlert
import org.itantra.app.ui.settings.LocalMessageTextScale
import org.itantra.app.ui.sos.SosSheet
import org.itantra.app.ui.theme.Labels
import org.itantra.app.ui.theme.MonoText
import org.itantra.core.Lang
import org.itantra.core.LinkState
import org.itantra.transport.LinkVia

private sealed interface ChatItem {
    val id: String
    val timestamp: Long
    data class Final(val entry: LogEntry) : ChatItem {
        override val id get() = entry.id
        override val timestamp get() = entry.timestamp
    }
    data class Live(val entry: PartialEntry) : ChatItem {
        override val id get() = entry.id
        override val timestamp get() = entry.timestamp
    }
}

/** One row in the rendered deck: a date separator, the unread marker, or an actual message —
 * inserted around the plain time-sorted [ChatItem] list so grouping/unread live purely in the UI
 * layer (see [buildRows]). */
private sealed interface DeckRow {
    val key: String
    data class Header(val epochMs: Long) : DeckRow { override val key = "hdr-$epochMs" }
    data object Unread : DeckRow { override val key = "unread-divider" }
    data class Msg(val item: ChatItem) : DeckRow { override val key get() = item.id }
}

private fun dayKeyOf(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMs))

private fun buildRows(items: List<ChatItem>, unreadFromIndex: Int): List<DeckRow> {
    val rows = mutableListOf<DeckRow>()
    var lastDayKey: String? = null
    items.forEachIndexed { idx, item ->
        val dayKey = dayKeyOf(item.timestamp)
        if (dayKey != lastDayKey) {
            rows += DeckRow.Header(item.timestamp)
            lastDayKey = dayKey
        }
        if (idx == unreadFromIndex) rows += DeckRow.Unread
        rows += DeckRow.Msg(item)
    }
    return rows
}

@Composable
fun ChatScreen(onOpenModels: () -> Unit) {
    val mode by AppRepository.mode.collectAsState()
    val language by AppRepository.language.collectAsState()
    val alertNext by AppRepository.alertNext.collectAsState()
    val peerTalking by AppRepository.peerTalking.collectAsState()
    val pttHeld by AppRepository.pttHeld.collectAsState()
    val messages by AppRepository.messages.collectAsState()
    val partials by AppRepository.partials.collectAsState()
    val metrics by AppRepository.metrics.collectAsState()
    val engineStatus by AppRepository.engineStatus.collectAsState()
    val speakingId by AppRepository.speakingId.collectAsState()
    val ttsPlaying by AppRepository.ttsPlaying.collectAsState()
    val micLevel by AppRepository.micLevel.collectAsState()
    val voiceNoteDurations by AppRepository.voiceNoteDurations.collectAsState()
    val linkState by AppRepository.linkState.collectAsState()
    val linkVia by AppRepository.linkVia.collectAsState()
    val rttMs by AppRepository.rttMs.collectAsState()

    val context = LocalContext.current
    var nearbySheetOpen by remember { mutableStateOf(false) }
    var sosSheetOpen by remember { mutableStateOf(false) }

    val finalIds = remember(messages) { messages.map { it.id }.toHashSet() }
    val items = remember(messages, partials) {
        (messages.map { ChatItem.Final(it) } + partials.values.filter { it.id !in finalIds }.map { ChatItem.Live(it) })
            .sortedBy { it.timestamp }
    }
    // Everything already in the deck the first time this screen composes counts as "seen"; only
    // messages that arrive afterwards get the unread divider (see requirement: unread divider).
    val seenCountAtOpen = remember { items.size }
    val rows = remember(items) { buildRows(items, seenCountAtOpen) }

    // New-ALERT bookkeeping for the finite flash + vibration: seed with whatever alerts already
    // exist at first composition so history never re-announces itself, then track ids that show
    // up afterwards as "fresh" exactly once each.
    val seenAlertIds = remember { mutableStateSetOf<String>().apply { addAll(messages.filter { it.alert }.map { it.id }) } }
    val freshAlertIds = remember(messages) {
        val fresh = messages.asSequence()
            .filter { it.alert && it.direction == Direction.RECEIVED && it.id !in seenAlertIds }
            .map { it.id }.toSet()
        seenAlertIds += fresh
        fresh
    }
    LaunchedEffect(freshAlertIds) { if (freshAlertIds.isNotEmpty()) vibrateAlert(context) }

    Column(Modifier.fillMaxSize()) {
        PeerCard(linkState = linkState, rttMs = rttMs, via = linkVia, language = language, onTap = { nearbySheetOpen = true })
        if (engineStatus != null && (engineStatus!!.sttReady == false || engineStatus!!.ttsReady == false)) {
            EngineWarningBanner(engineStatus!!.sttReady, engineStatus!!.ttsReady, onOpenModels)
        }
        if (peerTalking) PeerTalkingBanner()

        Box(Modifier.weight(1f)) {
            if (rows.isEmpty()) {
                EmptyStateTips(Modifier.fillMaxSize())
            } else {
                val listState = rememberLazyListState()
                val isAtBottom by remember {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        last >= listState.layoutInfo.totalItemsCount - 2
                    }
                }
                // Follow new messages unless the user has dragged up to read history. Measuring
                // "at bottom" alone fails when one tall card (e.g. a long alert) fills the viewport.
                val dragged by listState.interactionSource.collectIsDraggedAsState()
                var userScrolledUp by remember { mutableStateOf(false) }
                LaunchedEffect(dragged, isAtBottom) {
                    if (dragged && !isAtBottom) userScrolledUp = true
                    if (isAtBottom) userScrolledUp = false
                }
                LaunchedEffect(rows.size) {
                    if (rows.isNotEmpty() && !userScrolledUp) listState.animateScrollToItem(rows.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is DeckRow.Header -> DateHeader(dateLabel(row.epochMs))
                            is DeckRow.Unread -> UnreadDivider()
                            is DeckRow.Msg -> when (val item = row.item) {
                                is ChatItem.Final -> if (item.entry.alert) {
                                    AlertCard(
                                        entry = item.entry,
                                        durationSeconds = voiceNoteDurations[item.entry.id],
                                        isFresh = item.entry.id in freshAlertIds,
                                        onReplay = { TalkService.instance?.onReplay(item.entry.id) },
                                    )
                                } else {
                                    MessageCard(
                                        entry = item.entry,
                                        metrics = metrics.find { it.id == item.entry.id && it.direction == item.entry.direction },
                                        speaking = speakingId == item.entry.id,
                                        durationSeconds = voiceNoteDurations[item.entry.id],
                                        rttMs = rttMs,
                                        onReplay = { TalkService.instance?.onReplay(item.entry.id) },
                                    )
                                }
                                is ChatItem.Live -> LivePartialRow(item.entry)
                            }
                        }
                    }
                }
            }
        }

        Composer(
            mode = mode,
            language = language,
            alertNext = alertNext,
            pttHeld = pttHeld,
            peerTalking = peerTalking,
            ttsPlaying = ttsPlaying,
            micLevel = micLevel,
            onSosClick = { sosSheetOpen = true },
        )
    }

    if (nearbySheetOpen) {
        NearbyDevicesSheet(linkState = linkState, rttMs = rttMs, via = linkVia, onDismiss = { nearbySheetOpen = false })
    }
    if (sosSheetOpen) {
        SosSheet(onDismiss = { sosSheetOpen = false })
    }
}

@Composable
private fun dateLabel(epochMs: Long): String =
    if (dayKeyOf(epochMs) == dayKeyOf(System.currentTimeMillis())) {
        stringResource(R.string.time_today)
    } else {
        SimpleDateFormat("d MMM", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(Date(epochMs))
    }

@Composable
private fun EngineWarningBanner(sttReady: Boolean, ttsReady: Boolean, onOpenModels: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onOpenModels() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Medium)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            MonoText(
                buildString {
                    if (!sttReady) append(stringResource(R.string.stt_missing) + "  ")
                    if (!ttsReady) append(stringResource(R.string.tts_missing))
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(stringResource(R.string.tap_open_models), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
}

@Composable
private fun PeerTalkingBanner() {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(stringResource(R.string.label_peer_talking), color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AlertCard(entry: LogEntry, durationSeconds: Float?, isFresh: Boolean, onReplay: () -> Unit) {
    val flashAlpha = rememberAlertFlash(play = isFresh && entry.direction == Direction.RECEIVED)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .alpha(flashAlpha)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.cd_alert_icon), tint = Color.White)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(Labels.alertOf(entry.lang), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(entry.text, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
        }
        if (entry.direction == Direction.RECEIVED && durationSeconds != null) {
            ReplayButton(durationSeconds, tint = Color.White, onClick = onReplay)
        }
    }
}

@Composable
private fun ReplayButton(durationSeconds: Float, tint: Color, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 10.dp)
            .size(width = 84.dp, height = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClickLabel = stringResource(R.string.cd_replay), onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        MonoText(" %.1fs".format(durationSeconds), color = tint.copy(alpha = 0.9f))
    }
}

private fun timeOf(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))

private fun fmtMs(ms: Long): String = if (ms >= 1000) "%.1fs".format(ms / 1000f) else "${ms}ms"

/** Applies the "Message text size" setting (see [LocalMessageTextScale]/Settings) to message-card
 * body text and live partial captions — the only two places that setting is meant to reach. */
@Composable
private fun TextStyle.scaledByMessageTextSize(): TextStyle {
    val scale = LocalMessageTextScale.current
    return copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)
}

private fun telemetryLine(m: MessageMetrics): String = buildString {
    m.sttLatencyMs?.let { append("STT ${fmtMs(it)}") }
    m.rtf?.let { if (isNotEmpty()) append(" · "); append("RTF %.2f".format(it)) }
    m.networkMs?.let { if (isNotEmpty()) append(" · "); append("NET ${fmtMs(it)}") }
    m.ttsStartMs?.let { if (isNotEmpty()) append(" · "); append("TTS ${fmtMs(it)}") }
    m.endToEndMs?.let { if (isNotEmpty()) append(" · "); append("E2E ${fmtMs(it)}") }
}

/** Friendly message card: sender-initials avatar, native-script language chip, time, delivery
 * ticks on my own messages, an optional replay control, and a long-press menu (copy / replay /
 * latency) — replaces the old field-radio-instrument transcript row. */
@Composable
private fun MessageCard(
    entry: LogEntry,
    metrics: MessageMetrics?,
    speaking: Boolean,
    durationSeconds: Float?,
    rttMs: Long?,
    onReplay: () -> Unit,
) {
    val sent = entry.direction == Direction.SENT
    val accent = if (sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    var expanded by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val copiedLabel = stringResource(R.string.copied_text)
    val toastContext = LocalContext.current

    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 6.dp),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start,
    ) {
        if (!sent) Avatar(sent = false, accent = accent)
        Box(Modifier.padding(horizontal = 8.dp).weight(1f, fill = false)) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (sent) accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer)
                    .combinedClickable(
                        onClick = { if (metrics != null) expanded = !expanded },
                        onLongClick = { menuOpen = true },
                        onClickLabel = stringResource(R.string.cd_message_options),
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LangChip(entry.lang, accent)
                    Spacer(Modifier.width(8.dp))
                    Text(timeOf(entry.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (speaking) {
                        Spacer(Modifier.width(8.dp))
                        LevelGlyph(color = accent)
                        Text(
                            stringResource(R.string.disabled_playing),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (sent) {
                        Spacer(Modifier.width(8.dp))
                        DeliveryTicksRow(
                            sentAtMs = entry.timestamp,
                            metrics = metrics,
                            rttMs = rttMs,
                            onRetry = {
                                val prev = AppRepository.alertNext.value
                                AppRepository.alertNext.value = entry.alert
                                TalkService.instance?.onTextSend(entry.text, entry.lang, entry.ssml)
                                AppRepository.alertNext.value = prev
                            },
                        )
                    }
                }
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodyLarge.scaledByMessageTextSize(),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!sent && durationSeconds != null) {
                    Spacer(Modifier.height(4.dp))
                    ReplayButton(durationSeconds, tint = MaterialTheme.colorScheme.secondary, onClick = onReplay)
                }
                if (expanded && metrics != null) {
                    MonoText(telemetryLine(metrics), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            MessageOptionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                showReplay = !sent && durationSeconds != null,
                showLatency = metrics != null,
                onCopy = {
                    clipboard.setText(AnnotatedString(entry.text))
                    android.widget.Toast.makeText(toastContext, copiedLabel, android.widget.Toast.LENGTH_SHORT).show()
                },
                onReplay = onReplay,
                onShowLatency = { expanded = true },
            )
        }
        if (sent) Avatar(sent = true, accent = accent)
    }
}

@Composable
private fun Avatar(sent: Boolean, accent: Color) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center,
    ) {
        MonoText(if (sent) "Y" else "P", color = accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LangChip(lang: Lang, accent: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(lang.label, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LivePartialRow(entry: PartialEntry) {
    val sent = entry.direction == Direction.SENT
    val ruleColor = if (sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(entry.id) {
        while (true) {
            delay(500)
            caretOn = !caretOn
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (sent) 40.dp else 0.dp, end = if (sent) 0.dp else 40.dp, top = 10.dp, bottom = 10.dp)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(ruleColor.copy(alpha = 0.5f)))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            MonoText("${if (sent) "YOU" else "PEER"} · ${entry.lang.code.uppercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Text(
                    entry.text.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge.scaledByMessageTextSize(),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.alpha(0.85f),
                )
                MonoText("▌", color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (caretOn) 0.8f else 0f))
            }
        }
    }
}

@Composable
private fun LevelGlyph(color: Color) {
    val infinite = rememberInfiniteTransition(label = "lvl")
    val h1 by infinite.animateFloat(0.35f, 1f, infiniteRepeatable(tween(280, easing = LinearEasing), RepeatMode.Reverse), label = "h1")
    val h2 by infinite.animateFloat(1f, 0.4f, infiniteRepeatable(tween(340, easing = LinearEasing), RepeatMode.Reverse), label = "h2")
    val h3 by infinite.animateFloat(0.5f, 0.9f, infiniteRepeatable(tween(220, easing = LinearEasing), RepeatMode.Reverse), label = "h3")
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(12.dp)) {
        listOf(h1, h2, h3).forEach { h -> Box(Modifier.width(3.dp).fillMaxHeight(h.coerceIn(0.1f, 1f)).background(color)) }
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom deck
// ---------------------------------------------------------------------------------------------

private val DECK_GUTTER = 16.dp
private val DECK_GAP = 8.dp

/**
 * The bottom deck, laid out as a strict 3-row grid with [DECK_GUTTER] side margins and [DECK_GAP]
 * gaps throughout (fixes the old ad-hoc `SpaceBetween`/`Spacer` layout, which left the talk button
 * at ~60% width with a big empty gap before the keyboard icon, a cramped mode segment, an
 * Alert toggle floating far right, and SOS chips clipped off-screen), sized compactly so the
 * message list keeps most of the screen:
 *  - Row 1: Talk|Call segment (weight 1f) + Alert toggle (weight ~0.45f), 40dp tall visually, with
 *    a [minimumInteractiveComponentSize] touch target so the smaller pill doesn't shrink tappability.
 *  - Row 2 (PTT mode, composer closed only): quick SOS chips, one per line-fraction (equal-width,
 *    [Arrangement.spacedBy]) rather than clipping/scrolling off-screen — always exactly one line.
 *  - Row 3: round SOS button (52dp), the main talk button (56dp tall, fills the remaining width),
 *    and a square keyboard button (52dp) — replaces the old floating SOS FAB, which used to overlap
 *    the message list.
 */
@Composable
private fun Composer(
    mode: Mode,
    language: Lang,
    alertNext: Boolean,
    pttHeld: Boolean,
    peerTalking: Boolean,
    ttsPlaying: Boolean,
    micLevel: Float,
    onSosClick: () -> Unit,
) {
    var composerOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Column(Modifier.padding(horizontal = DECK_GUTTER, vertical = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(DECK_GAP),
                ) {
                    ModeSegment(mode, Modifier.weight(1f))
                    AlertToggle(alertNext, Modifier.weight(0.45f))
                }
                if (mode == Mode.PTT && !composerOpen) {
                    Spacer(Modifier.height(DECK_GAP))
                    SosChipsRow(language)
                }
                Spacer(Modifier.height(DECK_GAP))

                when {
                    mode == Mode.PHONE -> CallBar(
                        ttsPlaying = ttsPlaying,
                        peerTalking = peerTalking,
                        micLevel = micLevel,
                        onHangUp = { TalkService.instance?.onModeChanged(Mode.PTT) },
                    )
                    composerOpen -> TextComposerRow(language = language, onClose = { composerOpen = false })
                    else -> Row(
                        Modifier.fillMaxWidth().height(56.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(DECK_GAP),
                    ) {
                        SosButton(onClick = onSosClick, size = 52.dp)
                        TalkButton(
                            language = language,
                            held = pttHeld,
                            peerTalking = peerTalking,
                            ttsPlaying = ttsPlaying,
                            micLevel = micLevel,
                            onPress = { TalkService.instance?.onPttPressed() },
                            onRelease = { TalkService.instance?.onPttReleased() },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                        KeyboardButton(onClick = { composerOpen = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
            .clickable(onClickLabel = stringResource(R.string.cd_type_message), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Keyboard, contentDescription = stringResource(R.string.cd_type_message), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
    }
}

/** Quick-reply SOS chips — always one line, equal-width ([Arrangement.spacedBy] + `weight(1f)`
 * each), with short display labels ([R.string.sos_chip_need_help] etc., translated per language)
 * so long phrasings ("Medical emergency") don't force wrapping or a scrollable row. The *sent*
 * message text is still the full phrase from [Labels.sosOf] — only the chip's own caption is
 * shortened. "Need help"/"Medical emergency" go out as ALERT priority, "Safe"/"Water‑food needed"
 * as a normal message, restoring whatever the alert-arm toggle was set to beforehand. */
@Composable
private fun SosChipsRow(language: Lang) {
    val set = Labels.sosOf(language)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SosChip(stringResource(R.string.sos_chip_need_help), sendText = set.needHelp, urgent = true, language = language, modifier = Modifier.weight(1f))
        SosChip(stringResource(R.string.sos_chip_medical), sendText = set.medical, urgent = true, language = language, modifier = Modifier.weight(1f))
        SosChip(stringResource(R.string.sos_chip_safe), sendText = set.safe, urgent = false, language = language, modifier = Modifier.weight(1f))
        SosChip(stringResource(R.string.sos_chip_supplies), sendText = set.supplies, urgent = false, language = language, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SosChip(displayLabel: String, sendText: String, urgent: Boolean, language: Lang, modifier: Modifier = Modifier) {
    val color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .clickable {
                val prev = AppRepository.alertNext.value
                AppRepository.alertNext.value = urgent
                TalkService.instance?.onTextSend(sendText, language, false)
                AppRepository.alertNext.value = prev
            }
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            displayLabel,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Row 1 of the bottom deck's grid (see [Composer]): equal Talk/Call halves + the Alert toggle, 40dp
 * tall visually — [minimumInteractiveComponentSize] pads the actual touch/layout target back up to
 * the platform's 48dp minimum (extra invisible space around the pill) without growing what's drawn. */
@Composable
private fun ModeSegment(mode: Mode, modifier: Modifier = Modifier) {
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        SegmentButton(
            stringResource(R.string.mode_ptt),
            selected = mode == Mode.PTT,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { TalkService.instance?.onModeChanged(Mode.PTT) }
        Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline))
        SegmentButton(
            stringResource(R.string.mode_call),
            selected = mode == Mode.PHONE,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { TalkService.instance?.onModeChanged(Mode.PHONE) }
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        MonoText(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun AlertToggle(armed: Boolean, modifier: Modifier = Modifier) {
    val color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(if (armed) MaterialTheme.colorScheme.error.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { AppRepository.alertNext.value = !armed }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        MonoText(stringResource(R.string.alert_toggle), color = color, fontWeight = if (armed) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun TextComposerRow(language: Lang, onClose: () -> Unit) {
    var typedText by remember { mutableStateOf("") }
    var ssml by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = typedText,
                onValueChange = { typedText = it },
                placeholder = { Text(if (ssml) stringResource(R.string.composer_placeholder_ssml) else stringResource(R.string.composer_placeholder)) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            IconButton(onClick = {
                if (typedText.isNotBlank()) {
                    TalkService.instance?.onTextSend(typedText, language, ssml)
                    typedText = ""
                }
            }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.cd_send), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close_composer), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MonoText(stringResource(R.string.label_ssml), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(
                checked = ssml,
                onCheckedChange = { ssml = it },
                modifier = Modifier.padding(start = 8.dp),
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun CallBar(ttsPlaying: Boolean, peerTalking: Boolean, micLevel: Float, onHangUp: () -> Unit) {
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSec++
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MonoText("${stringResource(R.string.mode_call)} %02d:%02d".format(elapsedSec / 60, elapsedSec % 60), fontWeight = FontWeight.Medium)
        VuBars(level = if (ttsPlaying) 0f else micLevel, color = if (ttsPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
        MonoText(
            if (ttsPlaying || peerTalking) stringResource(R.string.label_rx) else stringResource(R.string.label_tx),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onHangUp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.error).size(48.dp),
        ) {
            Icon(Icons.Filled.CallEnd, contentDescription = stringResource(R.string.cd_hang_up), tint = Color.White)
        }
    }
}

@Composable
private fun VuBars(level: Float, color: Color, bars: Int = 12) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(18.dp)) {
        repeat(bars) { i ->
            val threshold = (i + 1) / bars.toFloat()
            val active = level >= threshold * 0.9f
            val h by animateFloatAsState(if (active) 1f else 0.22f, label = "bar$i")
            Box(Modifier.width(3.dp).fillMaxHeight(h).background(color.copy(alpha = if (active) 1f else 0.3f)))
        }
    }
}
