package org.itantra.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.itantra.app.R
import org.itantra.app.ui.theme.KolamMotif
import org.itantra.app.ui.theme.rememberReducedMotion
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState
import org.itantra.transport.LinkVia

/**
 * The single connection card at the top of the chat deck: connection dot, who we're talking to,
 * over what link (icon + correct label — LAN/Wi‑Fi Direct/Bluetooth, from [via], see
 * [org.itantra.transport.TransportManager.via]), at what RTT, in what language. Replaces the old
 * pair of a top-level status card plus this peer card, which repeated the same peer/link — this is
 * the one place that info lives now. Tapping it opens [NearbyDevicesSheet]. Searching state keeps
 * the kolam motif instead of the dot (motion already reads as "in progress").
 */
@Composable
fun PeerCard(linkState: LinkState, rttMs: Long?, via: LinkVia?, language: Lang, onTap: () -> Unit) {
    val connected = linkState is LinkState.Connected
    val searching = linkState is LinkState.Searching
    val failed = linkState is LinkState.Failed
    val reducedMotion = rememberReducedMotion()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClickLabel = stringResource(R.string.cd_peer_card), onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                connected -> {
                    val kind = (linkState as LinkState.Connected).kind
                    ConnectionDot(color = MaterialTheme.colorScheme.tertiary, animate = false)
                    Icon(
                        if (kind == LinkKind.WIFI) Icons.Filled.Wifi else Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(linkState.peerName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            stringResource(linkLabelRes(kind, via)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                searching -> {
                    Box(Modifier.size(18.dp)) { KolamMotif(Modifier.fillMaxWidth().height(18.dp), alpha = 0.5f, spacing = 9.dp) }
                    Text(
                        stringResource(R.string.status_searching),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                else -> {
                    ConnectionDot(color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, animate = false)
                    Text(
                        stringResource(if (failed) R.string.status_failed else R.string.status_idle),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected && rttMs != null) {
                Text("${rttMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LangPill(language)
        }
    }
}

/** Which link a connected [LinkKind.WIFI]/[LinkKind.BLE] state should label itself with — LAN and
 * Wi‑Fi Direct both report as [LinkKind.WIFI] (see [org.itantra.transport.TransportManager]), so
 * [via] is what actually disambiguates; a missing/unknown [via] falls back to LAN, the common case. */
private fun linkLabelRes(kind: LinkKind, via: LinkVia?): Int = when {
    kind == LinkKind.BLE -> R.string.link_bluetooth
    via == LinkVia.WIFI_DIRECT -> R.string.link_wifi_direct
    else -> R.string.link_lan
}

/** Small static connection-status dot (the merged card's replacement for the old StatusCard's
 * pulsing dot) — a brief pulse on state change, then rest, per the app's "idle CPU is judged"
 * animation discipline (see [org.itantra.app.ui.AppRoot]'s former PulsingDot). */
@Composable
private fun ConnectionDot(color: androidx.compose.ui.graphics.Color, animate: Boolean) {
    val reducedMotion = rememberReducedMotion()
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(animate, reducedMotion) {
        if (!animate || reducedMotion) { alpha.snapTo(1f); return@LaunchedEffect }
        repeat(4) {
            alpha.animateTo(0.35f, tween(750))
            alpha.animateTo(1f, tween(750))
        }
    }
    Box(Modifier.size(10.dp).alpha(alpha.value).clip(CircleShape).background(color))
}

@Composable
private fun LangPill(language: Lang) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(language.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
    }
}

/**
 * Bottom sheet reachable by tapping [PeerCard]. There is no multi-peer discovery list in the
 * current transport layer (one link at a time — see [org.itantra.core.Transport]), so this shows
 * the single peer from existing [LinkState], honestly, rather than fabricating a device scan UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyDevicesSheet(linkState: LinkState, rttMs: Long?, via: LinkVia?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.nearby_devices_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            when (linkState) {
                is LinkState.Connected -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (linkState.kind == LinkKind.WIFI) Icons.Filled.Wifi else Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(linkState.peerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(linkLabelRes(linkState.kind, via)) +
                                (rttMs?.let { " · ${it}ms" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is LinkState.Searching -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp)) { KolamMotif(Modifier.fillMaxWidth().height(28.dp), alpha = 0.6f, spacing = 12.dp) }
                    Text(stringResource(R.string.status_searching), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is LinkState.Failed -> Text(stringResource(R.string.status_failed), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                is LinkState.Idle -> Text(stringResource(R.string.status_idle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
