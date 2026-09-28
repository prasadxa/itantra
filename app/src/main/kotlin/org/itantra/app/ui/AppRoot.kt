package org.itantra.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.itantra.app.AppRepository
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.app.ui.theme.MonoText
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState

private enum class Screen { CHAT, METRICS, MODELS }

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    val linkState by AppRepository.linkState.collectAsState()
    val rttMs by AppRepository.rttMs.collectAsState()
    val language by AppRepository.language.collectAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (screen == Screen.CHAT) {
                StatusStrip(
                    linkState = linkState,
                    rttMs = rttMs,
                    language = language,
                    onOpenMetrics = { screen = Screen.METRICS },
                    onOpenModels = { screen = Screen.MODELS },
                )
            } else {
                ScreenHeader(
                    title = if (screen == Screen.METRICS) "Metrics" else "Models",
                    onBack = { screen = Screen.CHAT },
                )
            }
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.CHAT -> ChatScreen(onOpenModels = { screen = Screen.MODELS })
                    Screen.METRICS -> MetricsScreen()
                    Screen.MODELS -> ModelsScreen()
                }
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
                MonoText(title.uppercase(), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusStrip(
    linkState: LinkState,
    rttMs: Long?,
    language: Lang,
    onOpenMetrics: () -> Unit,
    onOpenModels: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var langSheetOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.brand_logo),
                    contentDescription = "iTantra",
                    modifier = Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)),
                )
                Spacer(Modifier.width(10.dp))
                StatusDot(linkState)
                Spacer(Modifier.width(8.dp))
                MonoText(statusLine(linkState, rttMs), modifier = Modifier.weight(1f))
                LangButton(language) { langSheetOpen = true }
                Box {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                        DropdownMenuItem(text = { Text("Metrics") }, onClick = { overflowExpanded = false; onOpenMetrics() })
                        DropdownMenuItem(text = { Text("Models") }, onClick = { overflowExpanded = false; onOpenModels() })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
        }
    }

    if (langSheetOpen) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { langSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Lang.entries.forEach { lang ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                TalkService.instance?.onLanguageChanged(lang)
                                langSheetOpen = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(lang.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        MonoText(lang.code.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(state: LinkState) {
    val linked = state is LinkState.Connected
    val searching = state is LinkState.Searching
    val infinite = rememberInfiniteTransition(label = "dot")
    val pulse by infinite.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val color = if (linked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(8.dp)
            .alpha(if (searching) pulse else 1f)
            .clip(CircleShape)
            .background(color),
    )
}

private fun statusLine(state: LinkState, rttMs: Long?): String = when (state) {
    is LinkState.Idle -> "IDLE"
    is LinkState.Searching -> "SEARCHING…"
    is LinkState.Connected -> buildString {
        append("LINKED  ")
        append(if (state.kind == LinkKind.WIFI) "WI-FI" else "BLE")
        append("  ")
        append(state.peerName)
        rttMs?.let { append("  RTT ${it}ms") }
    }
    is LinkState.Failed -> "LINK FAILED"
}

@Composable
private fun LangButton(language: Lang, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        MonoText(language.code.uppercase(), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        MonoText(" ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
