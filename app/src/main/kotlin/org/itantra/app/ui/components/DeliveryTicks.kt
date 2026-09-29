package org.itantra.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.itantra.app.MessageMetrics
import org.itantra.app.R

/**
 * Delivery state for a message *I* sent, inferred from [MessageMetrics] the way the wire protocol
 * actually reports it: [Orchestrator][org.itantra.app.Orchestrator] records metrics the instant a
 * message is handed to the transport (SENDING/SENT), then patches in [MessageMetrics.endToEndMs]
 * only once the peer's [org.itantra.core.Frame.Ack] confirms audio actually started playing there
 * (PLAYED). The wire has no separate "received but not yet played" event to key off of — Ack and
 * play are the same instant on the peer — so DELIVERED here is an honest best-effort estimate
 * (roughly one RTT with no Ack yet), not a distinct protocol signal. FAILED is a client-side
 * timeout, since [org.itantra.app.Orchestrator] never surfaces a transport-level send failure.
 */
enum class DeliveryState { SENDING, SENT, DELIVERED, PLAYED, FAILED }

private const val DELIVERED_HEURISTIC_MULTIPLIER = 3L
private const val MIN_DELIVERED_WINDOW_MS = 350L
private const val FAIL_TIMEOUT_MS = 15_000L

fun deliveryStateOf(sentAtMs: Long, metrics: MessageMetrics?, rttMs: Long?, nowMs: Long): DeliveryState {
    val elapsed = nowMs - sentAtMs
    if (metrics?.endToEndMs != null) return DeliveryState.PLAYED
    if (elapsed >= FAIL_TIMEOUT_MS) return DeliveryState.FAILED
    if (metrics == null) return DeliveryState.SENDING
    val deliveredWindow = ((rttMs ?: 250L) * DELIVERED_HEURISTIC_MULTIPLIER).coerceAtLeast(MIN_DELIVERED_WINDOW_MS)
    return if (elapsed >= deliveredWindow) DeliveryState.DELIVERED else DeliveryState.SENT
}

/**
 * ✓ sent / ✓✓ delivered / ✓✓ (teal) played / retry-on-failure row for a SENT [org.itantra.app.LogEntry].
 * Ticks over a 1s timer only while the state is still pending (SENDING/SENT/DELIVERED) — resolved
 * states (PLAYED/FAILED) stop the timer so this costs nothing once a message has settled.
 */
@Composable
fun DeliveryTicksRow(sentAtMs: Long, metrics: MessageMetrics?, rttMs: Long?, onRetry: () -> Unit) {
    var now by remember(sentAtMs) { mutableStateOf(System.currentTimeMillis()) }
    val state = deliveryStateOf(sentAtMs, metrics, rttMs, now)
    LaunchedEffect(sentAtMs, metrics?.endToEndMs, state) {
        while (state == DeliveryState.SENDING || state == DeliveryState.SENT || state == DeliveryState.DELIVERED) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val playedColor = MaterialTheme.colorScheme.secondary // teal = played, per brand rule
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error

    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        when (state) {
            DeliveryState.SENDING -> Icon(Icons.Filled.Schedule, contentDescription = stringResource(R.string.status_sending), tint = mutedColor, modifier = Modifier.size(14.dp))
            DeliveryState.SENT -> Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.status_sent), tint = mutedColor, modifier = Modifier.size(14.dp))
            DeliveryState.DELIVERED -> Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.status_sent), tint = mutedColor, modifier = Modifier.size(14.dp))
            DeliveryState.PLAYED -> Icon(Icons.Filled.DoneAll, contentDescription = stringResource(R.string.status_played), tint = playedColor, modifier = Modifier.size(14.dp))
            DeliveryState.FAILED -> Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.clickable(onClickLabel = stringResource(R.string.action_retry), onClick = onRetry),
            ) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = stringResource(R.string.status_failed_send), tint = errorColor, modifier = Modifier.size(14.dp))
                Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelSmall, color = errorColor)
                Icon(Icons.Filled.Refresh, contentDescription = null, tint = errorColor, modifier = Modifier.size(12.dp))
            }
        }
    }
}
