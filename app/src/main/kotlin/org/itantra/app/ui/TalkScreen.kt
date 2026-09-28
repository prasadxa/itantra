package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.itantra.app.AppRepository
import org.itantra.app.Direction
import org.itantra.app.LogEntry
import org.itantra.app.MessageMetrics
import org.itantra.app.Mode
import org.itantra.app.TalkService
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TalkScreen() {
    val linkState by AppRepository.linkState.collectAsState()
    val mode by AppRepository.mode.collectAsState()
    val language by AppRepository.language.collectAsState()
    val alertNext by AppRepository.alertNext.collectAsState()
    val peerTalking by AppRepository.peerTalking.collectAsState()
    val pttHeld by AppRepository.pttHeld.collectAsState()
    val messages by AppRepository.messages.collectAsState()
    val metrics by AppRepository.metrics.collectAsState()
    val engineStatus by AppRepository.engineStatus.collectAsState()

    var langMenuExpanded by remember { mutableStateOf(false) }
    var typedText by remember { mutableStateOf("") }
    var ssmlChecked by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LinkStatusChip(linkState)

        if (engineStatus?.sttReady == false || engineStatus?.ttsReady == false) {
            Text(
                text = buildString {
                    if (engineStatus?.sttReady == false) append("STT unavailable (models missing). ")
                    if (engineStatus?.ttsReady == false) append("TTS unavailable (models missing). ")
                    append("Typed text still works.")
                },
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (peerTalking) {
            Text("Peer talking…", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(Modifier.height(8.dp))

        ExposedDropdownMenuBox(expanded = langMenuExpanded, onExpandedChange = { langMenuExpanded = it }) {
            OutlinedTextField(
                readOnly = true,
                value = language.label,
                onValueChange = {},
                label = { Text("Language") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langMenuExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
            )
            ExposedDropdownMenu(expanded = langMenuExpanded, onDismissRequest = { langMenuExpanded = false }) {
                Lang.entries.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text("${lang.label} (${lang.code})") },
                        onClick = {
                            langMenuExpanded = false
                            TalkService.instance?.onLanguageChanged(lang)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mode: ${if (mode == Mode.PTT) "Push-to-talk" else "Phone (full duplex)"}")
            Switch(
                checked = mode == Mode.PHONE,
                onCheckedChange = { on -> TalkService.instance?.onModeChanged(if (on) Mode.PHONE else Mode.PTT) },
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Alert (next messages)", color = if (alertNext) Color.Red else MaterialTheme.colorScheme.onSurface)
            Switch(checked = alertNext, onCheckedChange = { AppRepository.alertNext.value = it })
        }

        Spacer(Modifier.height(16.dp))

        HoldToTalkButton(
            enabled = mode == Mode.PTT,
            held = pttHeld,
            onPress = { TalkService.instance?.onPttPressed() },
            onRelease = { TalkService.instance?.onPttReleased() },
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = typedText,
            onValueChange = { typedText = it },
            label = { Text(if (ssmlChecked) "SSML" else "Text") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = ssmlChecked, onCheckedChange = { ssmlChecked = it })
                Text("SSML")
            }
            Button(onClick = {
                TalkService.instance?.onTextSend(typedText, language, ssmlChecked)
                typedText = ""
            }) { Text("Send") }
        }

        Spacer(Modifier.height(12.dp))
        Text("Log", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxSize()) {
            items(messages.asReversed()) { entry ->
                LogRow(entry, metrics.find { it.id == entry.id && it.direction == entry.direction })
            }
        }
    }
}

@Composable
private fun LinkStatusChip(state: LinkState) {
    val (text, color) = when (state) {
        is LinkState.Idle -> "Idle" to Color.Gray
        is LinkState.Searching -> "Searching…" to Color(0xFFFFA000)
        is LinkState.Connected -> "Connected: ${state.peerName} (${if (state.kind == LinkKind.WIFI) "Wi-Fi" else "BLE"})" to Color(0xFF2E7D32)
        is LinkState.Failed -> "Link failed: ${state.reason}" to Color.Red
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = color)
    }
}

@Composable
private fun HoldToTalkButton(enabled: Boolean, held: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val bg = when {
        !enabled -> Color.LightGray
        held -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(bg, RoundedCornerShape(24.dp))
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPress()
                                tryAwaitRelease()
                                onRelease()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Text(
            text = if (!enabled) "Phone mode: mic always on" else if (held) "Release to send" else "Hold to talk",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
private fun LogRow(entry: LogEntry, m: MessageMetrics?) {
    val sent = entry.direction == Direction.SENT
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                if (entry.alert) Color.Red.copy(alpha = 0.1f) else if (sent) Color.Blue.copy(alpha = 0.06f) else Color.Green.copy(alpha = 0.06f),
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (sent) "Sent →" else "← Received", style = MaterialTheme.typography.labelMedium)
            Text(entry.lang.label, style = MaterialTheme.typography.labelMedium)
            if (entry.alert) Text("ALERT", color = Color.Red, style = MaterialTheme.typography.labelMedium)
        }
        Text(entry.text)
        if (m != null) {
            Text(
                buildString {
                    m.sttLatencyMs?.let { append("stt=${it}ms ") }
                    m.rtf?.let { append("rtf=%.2f ".format(it)) }
                    m.networkMs?.let { append("net=${it}ms ") }
                    m.ttsStartMs?.let { append("tts=${it}ms ") }
                    m.endToEndMs?.let { append("e2e=${it}ms") }
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
