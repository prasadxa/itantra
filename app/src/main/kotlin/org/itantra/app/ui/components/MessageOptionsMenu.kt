package org.itantra.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.itantra.app.R

/** Long-press menu on a [org.itantra.app.LogEntry] card: copy text, replay (received voice notes
 * only), show latency telemetry (only when metrics exist). */
@Composable
fun MessageOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    showReplay: Boolean,
    showLatency: Boolean,
    onCopy: () -> Unit,
    onReplay: () -> Unit,
    onShowLatency: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_copy_text)) },
            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
            onClick = { onDismiss(); onCopy() },
        )
        if (showReplay) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.cd_replay)) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = { onDismiss(); onReplay() },
            )
        }
        if (showLatency) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_show_latency)) },
                leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                onClick = { onDismiss(); onShowLatency() },
            )
        }
    }
}
