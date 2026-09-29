package org.itantra.app.ui.sos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.itantra.app.AppRepository
import org.itantra.app.Direction
import org.itantra.app.R
import org.itantra.app.TalkService
import org.itantra.app.location.LocationFix
import org.itantra.app.location.LocationProvider
import org.itantra.app.ui.theme.Labels
import org.itantra.core.Lang
import org.itantra.text.LocationSpeller

/**
 * One-tap SOS bottom sheet: six big localized emergency templates + an optional note. Tapping a
 * template sends immediately (that single tap *is* the send — no separate confirm step) as an
 * ALERT [org.itantra.core.VoiceMessage] carrying the current GPS fix, via
 * [TalkService.onSendSos]; a status line then tracks delivery (an [org.itantra.app.MessageMetrics]
 * with a non-null `endToEndMs` for the sent id means the peer's Ack came back).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val language by AppRepository.language.collectAsState()
    val metrics by AppRepository.metrics.collectAsState()

    val locationProvider = remember { LocationProvider(context) }
    var fix by remember { mutableStateOf<LocationFix?>(locationProvider.lastKnown()) }
    var fetching by remember { mutableStateOf(false) }
    val constellations by locationProvider.constellationsInUse.collectAsState()

    var note by remember { mutableStateOf("") }
    var sentId by remember { mutableStateOf<String?>(null) }

    fun refreshFix() {
        scope.launch {
            fetching = true
            fix = locationProvider.getFix(8_000L) ?: fix
            fetching = false
        }
    }

    DisposableEffect(Unit) {
        locationProvider.startGnssStatusUpdates()
        onDispose { locationProvider.stopGnssStatusUpdates() }
    }
    LaunchedEffect(Unit) { refreshFix() }

    fun send(template: String, safe: Boolean) {
        val f = fix
        val text = buildString {
            append(template)
            if (note.isNotBlank()) append(". ").append(note.trim())
        }
        sentId = TalkService.instance?.onSendSos(text, language, f?.lat, f?.lon, f?.accuracyOrNull, safe)
    }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.sos_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                stringResource(R.string.sos_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )

            LocationStatusCard(fix, fetching, constellations, language, onRefresh = ::refreshFix)
            Spacer(Modifier.height(14.dp))

            val t = Labels.sosTemplatesOf(language)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TemplateButton(t.rescue, urgent = true, modifier = Modifier.weight(1f)) { send(t.rescue, safe = false) }
                    TemplateButton(t.medical, urgent = true, modifier = Modifier.weight(1f)) { send(t.medical, safe = false) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TemplateButton(t.fire, urgent = true, modifier = Modifier.weight(1f)) { send(t.fire, safe = false) }
                    TemplateButton(t.flood, urgent = true, modifier = Modifier.weight(1f)) { send(t.flood, safe = false) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TemplateButton(t.trapped, urgent = true, modifier = Modifier.weight(1f)) { send(t.trapped, safe = false) }
                    TemplateButton(t.safe, urgent = false, modifier = Modifier.weight(1f)) { send(t.safe, safe = true) }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text(stringResource(R.string.sos_note_hint)) },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 2,
            )

            if (!locationProvider.hasPermission()) {
                Text(
                    stringResource(R.string.sos_permission_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            sentId?.let { id ->
                val delivered = metrics.any { it.id == id && it.direction == Direction.SENT && it.endToEndMs != null }
                Text(
                    stringResource(if (delivered) R.string.sos_status_delivered else R.string.sos_status_sent),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (delivered) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun LocationStatusCard(
    fix: LocationFix?,
    fetching: Boolean,
    constellations: Set<String>,
    language: Lang,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                when {
                    fix != null -> Text(
                        LocationSpeller.phrase(fix.lat, fix.lon, fix.accuracyOrNull, language),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    fetching -> Text(stringResource(R.string.sos_location_fetching), style = MaterialTheme.typography.bodyMedium)
                    else -> Text(stringResource(R.string.sos_location_unknown), style = MaterialTheme.typography.bodyMedium)
                }
                if (constellations.isNotEmpty()) {
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            "${stringResource(R.string.sos_satellites_label)}: ${constellations.sorted().joinToString(", ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if ("NavIC" in constellations) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if ("NavIC" in constellations) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            if (fetching) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.sos_refresh_location))
                }
            }
        }
    }
}

@Composable
private fun TemplateButton(label: String, urgent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(
        modifier
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}

