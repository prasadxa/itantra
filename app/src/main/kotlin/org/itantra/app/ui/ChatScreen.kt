package org.itantra.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.itantra.app.AppRepository
import org.itantra.app.Direction
import org.itantra.app.LogEntry
import org.itantra.app.MessageMetrics
import org.itantra.app.Mode
import org.itantra.app.PartialEntry
import org.itantra.app.TalkService
import org.itantra.app.ui.theme.MonoText
import org.itantra.core.Lang

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

    val finalIds = remember(messages) { messages.map { it.id }.toHashSet() }
    val items = remember(messages, partials) {
        (messages.map { ChatItem.Final(it) } + partials.values.filter { it.id !in finalIds }.map { ChatItem.Live(it) })
            .sortedBy { it.timestamp }
    }

    Column(Modifier.fillMaxSize()) {
        if (engineStatus != null && (engineStatus!!.sttReady == false || engineStatus!!.ttsReady == false)) {
            EngineWarningBanner(engineStatus!!.sttReady, engineStatus!!.ttsReady, onOpenModels)
        }
        if (peerTalking) PeerTalkingBanner()

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MonoText(
                    "Hold the key below to talk, or type a message.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(items.size) {
                if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    when (item) {
                        is ChatItem.Final -> if (item.entry.alert) {
                            AlertRow(item.entry)
                        } else {
                            TranscriptRow(
                                entry = item.entry,
                                metrics = metrics.find { it.id == item.entry.id && it.direction == item.entry.direction },
                                speaking = speakingId == item.entry.id,
                            )
                        }
                        is ChatItem.Live -> LivePartialRow(item.entry)
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
        )
    }
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
                    if (!sttReady) append("STT MISSING  ")
                    if (!ttsReady) append("TTS MISSING")
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text("Tap to open Models", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        MonoText("PEER TALKING", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AlertRow(entry: LogEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        MonoText("ALERT", color = Color.White, fontWeight = FontWeight.Medium)
        Text(entry.text, color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun timeOf(epochMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(epochMs))

private fun fmtMs(ms: Long): String = if (ms >= 1000) "%.1fs".format(ms / 1000f) else "${ms}ms"

private fun telemetryLine(m: MessageMetrics): String = buildString {
    m.sttLatencyMs?.let { append("STT ${fmtMs(it)}") }
    m.rtf?.let { if (isNotEmpty()) append(" · "); append("RTF %.2f".format(it)) }
    m.networkMs?.let { if (isNotEmpty()) append(" · "); append("NET ${fmtMs(it)}") }
    m.ttsStartMs?.let { if (isNotEmpty()) append(" · "); append("TTS ${fmtMs(it)}") }
    m.endToEndMs?.let { if (isNotEmpty()) append(" · "); append("E2E ${fmtMs(it)}") }
}

@Composable
private fun TranscriptRow(entry: LogEntry, metrics: MessageMetrics?, speaking: Boolean) {
    val sent = entry.direction == Direction.SENT
    val ruleColor = if (sent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (sent) 40.dp else 0.dp, end = if (sent) 0.dp else 40.dp, top = 10.dp, bottom = 10.dp)
            .height(IntrinsicSize.Min)
            .then(if (metrics != null) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(ruleColor))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoText(
                    "${if (sent) "YOU" else "PEER"} · ${entry.lang.code.uppercase()} · ${timeOf(entry.timestamp)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (speaking) {
                    Spacer(Modifier.width(8.dp))
                    LevelGlyph(color = ruleColor)
                }
            }
            Text(entry.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (expanded && metrics != null) {
                MonoText(telemetryLine(metrics), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
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
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(ruleColor.copy(alpha = 0.5f)))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            MonoText("${if (sent) "YOU" else "PEER"} · ${entry.lang.code.uppercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Text(
                    entry.text.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge,
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

@Composable
private fun Composer(
    mode: Mode,
    language: Lang,
    alertNext: Boolean,
    pttHeld: Boolean,
    peerTalking: Boolean,
    ttsPlaying: Boolean,
    micLevel: Float,
) {
    var composerOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ModeSegment(mode)
                    AlertToggle(alertNext)
                }
                Spacer(Modifier.height(10.dp))

                when {
                    mode == Mode.PHONE -> CallBar(
                        ttsPlaying = ttsPlaying,
                        peerTalking = peerTalking,
                        micLevel = micLevel,
                        onHangUp = { TalkService.instance?.onModeChanged(Mode.PTT) },
                    )
                    composerOpen -> TextComposerRow(language = language, onClose = { composerOpen = false })
                    else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        PttKey(
                            held = pttHeld,
                            enabled = !ttsPlaying && !peerTalking,
                            disabledLabel = if (ttsPlaying) "RX · SPEAKING" else if (peerTalking) "RX · SPEAKING" else null,
                            micLevel = micLevel,
                            onPress = { TalkService.instance?.onPttPressed() },
                            onRelease = { TalkService.instance?.onPttReleased() },
                        )
                        IconButton(onClick = { composerOpen = true }) {
                            Icon(Icons.Filled.Keyboard, contentDescription = "Type a message", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeSegment(mode: Mode) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
    ) {
        SegmentButton("PTT", selected = mode == Mode.PTT) { TalkService.instance?.onModeChanged(Mode.PTT) }
        Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.outline))
        SegmentButton("CALL", selected = mode == Mode.PHONE) { TalkService.instance?.onModeChanged(Mode.PHONE) }
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        MonoText(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun AlertToggle(armed: Boolean) {
    val color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .background(if (armed) MaterialTheme.colorScheme.error.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { AppRepository.alertNext.value = !armed }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        MonoText("ALERT", color = color, fontWeight = if (armed) FontWeight.Medium else FontWeight.Normal)
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
                placeholder = { Text(if (ssml) "SSML…" else "Message…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            IconButton(onClick = {
                if (typedText.isNotBlank()) {
                    TalkService.instance?.onTextSend(typedText, language, ssml)
                    typedText = ""
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close composer", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            MonoText("SSML", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        MonoText("CALL %02d:%02d".format(elapsedSec / 60, elapsedSec % 60), fontWeight = FontWeight.Medium)
        VuBars(level = if (ttsPlaying) 0f else micLevel, color = if (ttsPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
        MonoText(
            if (ttsPlaying) "RX" else if (peerTalking) "RX" else "TX",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = onHangUp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.error).size(40.dp),
        ) {
            Icon(Icons.Filled.CallEnd, contentDescription = "Hang up", tint = Color.White)
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

@Composable
private fun PttKey(
    held: Boolean,
    enabled: Boolean,
    disabledLabel: String?,
    micLevel: Float,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(held) {
        if (held) {
            elapsedSec = 0
            while (true) {
                delay(1000)
                elapsedSec++
            }
        }
    }
    val corner by animateDpAsState(if (held) 26.dp else 14.dp, label = "corner")
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh
        held -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val border = if (!held) MaterialTheme.colorScheme.outline else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth(0.44f)
            .height(96.dp)
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(corner))
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPress()
                                tryAwaitRelease()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onRelease()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        if (held) {
            MonoText("TX %02d:%02d".format(elapsedSec / 60, elapsedSec % 60), color = Color.White, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            VuBars(level = micLevel, color = Color.White)
        } else if (disabledLabel != null) {
            MonoText(disabledLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        } else {
            Text(
                "HOLD TO TALK",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
