package org.itantra.app.ui

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.itantra.app.AppRepository
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.app.ui.theme.MonoText
import org.itantra.app.ui.theme.TricolourHairline
import org.itantra.app.ui.theme.rememberReducedMotion
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState

private enum class Screen { CHAT, METRICS, MODELS, ABOUT }

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.CHAT) }
    val linkState by AppRepository.linkState.collectAsState()
    val rttMs by AppRepository.rttMs.collectAsState()
    val language by AppRepository.language.collectAsState()

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TopBar(
                language = language,
                onOpenMetrics = { screen = Screen.METRICS },
                onOpenModels = { screen = Screen.MODELS },
                onOpenAbout = { screen = Screen.ABOUT },
                showBack = screen != Screen.CHAT,
                onBack = { screen = Screen.CHAT },
                screenTitle = when (screen) {
                    Screen.METRICS -> stringResource(R.string.menu_metrics)
                    Screen.MODELS -> stringResource(R.string.menu_models)
                    Screen.ABOUT -> stringResource(R.string.menu_about)
                    Screen.CHAT -> null
                },
            )
            if (screen == Screen.CHAT) {
                StatusCard(linkState = linkState, rttMs = rttMs)
            }
            Box(Modifier.weight(1f)) {
                when (screen) {
                    Screen.CHAT -> ChatScreen(onOpenModels = { screen = Screen.MODELS })
                    Screen.METRICS -> MetricsScreen()
                    Screen.MODELS -> ModelsScreen()
                    Screen.ABOUT -> AboutScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    language: Lang,
    onOpenMetrics: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAbout: () -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    screenTitle: String?,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    var langSheetOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back), tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(screenTitle ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp).weight(1f))
                } else {
                    Image(
                        painter = painterResource(R.drawable.brand_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                    LangButton(language) { langSheetOpen = true }
                }
                Box {
                    IconButton(onClick = { overflowExpanded = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_change_language)) }, onClick = { overflowExpanded = false; langSheetOpen = true })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_metrics)) }, onClick = { overflowExpanded = false; onOpenMetrics() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_models)) }, onClick = { overflowExpanded = false; onOpenModels() })
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                            onClick = { overflowExpanded = false; onOpenAbout() },
                        )
                    }
                }
            }
            TricolourHairline()
        }
    }

    if (langSheetOpen) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { langSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.language_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    stringResource(R.string.language_picker_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                LanguagePickerGrid(
                    selected = language,
                    onSelect = { lang ->
                        TalkService.instance?.onLanguageChanged(lang)
                        AppRepository.language.value = lang
                        langSheetOpen = false
                        val activity = context.findActivity()
                        if (activity != null) LocaleManager.applyAndRecreate(activity, lang.code)
                    },
                )
            }
        }
    }
}

@Composable
private fun LangButton(language: Lang, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 4.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClickLabel = stringResource(R.string.menu_change_language), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(language.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        MonoText(" ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Big, friendly connection-status card — the "is this thing on" answer at a glance, replacing the
 * old instrument-style mono status line. Uses tertiary (India-green) only when actually connected,
 * per the design spec's "ready/connected only" rule for that colour.
 */
@Composable
private fun StatusCard(linkState: LinkState, rttMs: Long?) {
    val connected = linkState is LinkState.Connected
    val searching = linkState is LinkState.Searching
    val failed = linkState is LinkState.Failed
    val reducedMotion = rememberReducedMotion()

    val (title, subtitle) = when (linkState) {
        is LinkState.Connected -> {
            val kind = if (linkState.kind == LinkKind.WIFI) "Wi‑Fi Direct" else "Bluetooth"
            stringResource(R.string.status_connected, linkState.peerName, kind) to rttMs?.let { "RTT ${it}ms" }
        }
        is LinkState.Searching -> stringResource(R.string.status_searching) to null
        is LinkState.Idle -> stringResource(R.string.status_idle) to null
        is LinkState.Failed -> stringResource(R.string.status_failed) to null
    }

    val dotColor = when {
        connected -> MaterialTheme.colorScheme.tertiary
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color = dotColor, animate = (searching || connected) && !reducedMotion)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PulsingDot(color: androidx.compose.ui.graphics.Color, animate: Boolean) {
    val infinite = rememberInfiniteTransition(label = "dot")
    val pulse by infinite.animateFloat(0.35f, 1f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "pulse")
    Box(
        Modifier
            .size(14.dp)
            .alpha(if (animate) pulse else 1f)
            .clip(CircleShape)
            .background(color),
    )
}
