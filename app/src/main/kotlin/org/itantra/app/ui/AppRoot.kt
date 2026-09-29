package org.itantra.app.ui

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.itantra.app.AppRepository
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.app.ui.history.HistoryScreen
import org.itantra.app.ui.pairing.PairingScreen
import org.itantra.app.ui.settings.SettingsPrefs
import org.itantra.app.ui.settings.SettingsScreen
import org.itantra.app.ui.theme.MonoText
import org.itantra.app.ui.theme.TricolourHairline
import org.itantra.app.ui.theme.rememberReducedMotion
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState

/** [TALK]/[HISTORY]/[METRICS]/[SETTINGS] are the four bottom-nav destinations; [ABOUT]/[MODELS]/
 * [PAIRING] are detail screens pushed from Settings (back returns to Settings — see [AppRoot]'s
 * two [BackHandler]s). A plain enum round-trips through [rememberSaveable] (it implements
 * `Serializable`) with no custom `Saver`, so the current tab survives process death/recreation. */
private enum class Screen { TALK, HISTORY, METRICS, SETTINGS, ABOUT, MODELS, PAIRING }

private fun Screen.isDetail() = this == Screen.ABOUT || this == Screen.MODELS || this == Screen.PAIRING

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.TALK) }
    val linkState by AppRepository.linkState.collectAsState()
    val rttMs by AppRepository.rttMs.collectAsState()
    val language by AppRepository.language.collectAsState()
    val context = LocalContext.current
    val largeText by SettingsPrefs.largeText(context).collectAsState()

    // Predictive/system back: a detail screen (About/Models/Pairing) returns to Settings; any
    // other non-Talk tab returns to Talk; Talk itself is left alone so back exits the app as usual.
    BackHandler(enabled = screen.isDetail()) { screen = Screen.SETTINGS }
    BackHandler(enabled = !screen.isDetail() && screen != Screen.TALK) { screen = Screen.TALK }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, largeText) {
        Density(baseDensity.density, fontScale = baseDensity.fontScale * (if (largeText) 1.25f else 1f))
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (!screen.isDetail()) {
                    BottomNavBar(screen = screen, onSelect = { screen = it })
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                TopBar(
                    screen = screen,
                    language = language,
                    linkState = linkState,
                    onBack = { screen = Screen.SETTINGS },
                )
                if (screen == Screen.TALK) {
                    StatusCard(linkState = linkState, rttMs = rttMs)
                }
                Box(Modifier.weight(1f)) {
                    when (screen) {
                        Screen.TALK -> ChatScreen(onOpenModels = { screen = Screen.MODELS })
                        Screen.HISTORY -> HistoryScreen(onBack = { screen = Screen.TALK })
                        Screen.METRICS -> MetricsScreen()
                        Screen.SETTINGS -> SettingsScreen(
                            onOpenPairing = { screen = Screen.PAIRING },
                            onOpenModels = { screen = Screen.MODELS },
                            onOpenAbout = { screen = Screen.ABOUT },
                        )
                        Screen.ABOUT -> AboutScreen()
                        Screen.MODELS -> ModelsScreen()
                        Screen.PAIRING -> PairingScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomNavBar(screen: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBarItem(
            selected = screen == Screen.TALK,
            onClick = { onSelect(Screen.TALK) },
            icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_talk)) },
        )
        NavigationBarItem(
            selected = screen == Screen.HISTORY,
            onClick = { onSelect(Screen.HISTORY) },
            icon = { Icon(Icons.Filled.History, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_history)) },
        )
        NavigationBarItem(
            selected = screen == Screen.METRICS,
            onClick = { onSelect(Screen.METRICS) },
            icon = { Icon(Icons.Filled.Insights, contentDescription = null) },
            label = { Text(stringResource(R.string.menu_metrics)) },
        )
        NavigationBarItem(
            selected = screen == Screen.SETTINGS,
            onClick = { onSelect(Screen.SETTINGS) },
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}

@Composable
private fun screenTitle(screen: Screen): String = when (screen) {
    Screen.TALK -> stringResource(R.string.app_name)
    Screen.HISTORY -> stringResource(R.string.nav_history)
    Screen.METRICS -> stringResource(R.string.menu_metrics)
    Screen.SETTINGS -> stringResource(R.string.nav_settings)
    Screen.ABOUT -> stringResource(R.string.menu_about)
    Screen.MODELS -> stringResource(R.string.menu_models)
    Screen.PAIRING -> stringResource(R.string.pairing_title)
}

/** Clean top app bar: logo (Talk tab only — a back arrow replaces it everywhere else), the
 * section title, a compact peer-status pill, and (bottom-nav tabs only) the UI-language switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    screen: Screen,
    language: Lang,
    linkState: LinkState,
    onBack: () -> Unit,
) {
    var langSheetOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val showBack = screen.isDetail()

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
                    Text(screenTitle(screen), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp).weight(1f))
                } else {
                    Image(
                        painter = painterResource(R.drawable.brand_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Text(
                        screenTitle(screen),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                }
                PeerStatusPill(linkState)
                if (!showBack) {
                    Spacer(Modifier.size(6.dp))
                    LangButton(language) { langSheetOpen = true }
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

/** Compact peer-connection indicator for the top bar (a coloured dot, +peer name once connected) —
 * always visible so "is anyone even nearby" is answerable from every screen, not just Talk (which
 * additionally shows the full [StatusCard]). */
@Composable
private fun PeerStatusPill(linkState: LinkState) {
    val connected = linkState is LinkState.Connected
    val failed = linkState is LinkState.Failed
    val dotColor = when {
        connected -> MaterialTheme.colorScheme.tertiary
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = if (connected) (linkState as LinkState.Connected).peerName else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun LangButton(language: Lang, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 4.dp)
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
        PulsingDot(color = dotColor, animate = searching && !reducedMotion)
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
    // Idle CPU is judged: an infinite transition keeps Compose and RenderThread drawing every frame
    // (~50% of a core on a Snapdragon 870). Pulse a few times when the state changes, then rest.
    val alpha = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(animate) {
        if (!animate) { alpha.snapTo(1f); return@LaunchedEffect }
        repeat(4) {
            alpha.animateTo(0.35f, tween(750))
            alpha.animateTo(1f, tween(750))
        }
    }
    Box(
        Modifier
            .size(14.dp)
            .alpha(alpha.value)
            .clip(CircleShape)
            .background(color),
    )
}
