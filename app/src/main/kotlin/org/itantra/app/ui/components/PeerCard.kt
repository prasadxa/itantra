package org.itantra.app.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.itantra.app.R
import org.itantra.app.ui.theme.KolamMotif
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState

/**
 * Compact peer card for the top of the chat deck: who we're talking to, over what link, at what
 * RTT, in what language — the "who am I talking to" glance the plain top-bar status pill doesn't
 * give. Tapping it opens [NearbyDevicesSheet]. Complements (does not replace) [AppRoot]'s
 * StatusCard, which is owned by a different agent.
 */
@Composable
fun PeerCard(linkState: LinkState, rttMs: Long?, language: Lang, onTap: () -> Unit) {
    val connected = linkState is LinkState.Connected
    val searching = linkState is LinkState.Searching

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
                    Icon(
                        if (kind == LinkKind.WIFI) Icons.Filled.Wifi else Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(linkState.peerName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            stringResource(if (kind == LinkKind.WIFI) R.string.link_wifi_direct else R.string.link_bluetooth),
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
                else -> Text(
                    stringResource(if (linkState is LinkState.Failed) R.string.status_failed else R.string.status_idle),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
fun NearbyDevicesSheet(linkState: LinkState, rttMs: Long?, onDismiss: () -> Unit) {
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
                            stringResource(if (linkState.kind == LinkKind.WIFI) R.string.link_wifi_direct else R.string.link_bluetooth) +
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
