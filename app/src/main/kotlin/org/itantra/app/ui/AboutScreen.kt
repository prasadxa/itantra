package org.itantra.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.itantra.app.R

private data class Licence(val name: String, val licence: String, val note: String? = null)

/** Runtimes bundled in the APK have well-known SPDX licences; the model *weights* themselves
 * (SraVaani/Indic-Mio/Rasa/FLEURS) are credited with their source project so a reviewer can check
 * the upstream repo for the current terms, rather than this screen asserting a licence for
 * artefacts this app doesn't itself publish. */
private val runtimeLicences = listOf(
    Licence("sherpa-onnx (STT/VAD runtime)", "Apache License 2.0"),
    Licence("llama.cpp / mio-tts-cpp (TTS runtime)", "MIT License"),
    Licence("ONNX Runtime", "MIT License"),
    Licence("Kotlin & AndroidX Jetpack libraries", "Apache License 2.0"),
    Licence("Jetpack Compose", "Apache License 2.0"),
    Licence("IBM Plex Mono (telemetry font)", "SIL Open Font License 1.1"),
)

private val modelCredits = listOf(
    Licence("SraVaani (speech recognition)", "ARTPARK @ IISc Bengaluru", "see project repository for current licence"),
    Licence("Indic-Mio (voice synthesis)", "SPRING Lab @ IIT Madras", "see project repository for current licence"),
    Licence("Rasa voices (VITS fallback)", "AI4Bharat @ IIT Madras", "CC-BY-4.0 dataset (vits_rasa_13); see upstream repo"),
    Licence("FLEURS (reference audio for voice embeddings)", "Google Research", "CC-BY-4.0"),
)

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
        item {
            Image(
                painter = painterResource(R.drawable.brand_logo),
                contentDescription = stringResource(R.string.cd_brand_logo),
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).padding(bottom = 4.dp),
            )
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.about_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )
            Text(stringResource(R.string.about_credits_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.onboarding_step3_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            modelCredits.forEach { LicenceRow(it) }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outline)
            Text(stringResource(R.string.about_licences_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(runtimeLicences) { LicenceRow(it) }
    }
}

@Composable
private fun LicenceRow(l: Licence) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(l.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (l.note != null) "${l.licence} — ${l.note}" else l.licence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
