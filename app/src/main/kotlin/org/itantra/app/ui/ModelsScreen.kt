package org.itantra.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import org.itantra.app.ExpectedModelFiles
import org.itantra.app.ModelFileEntry
import org.itantra.app.ui.theme.MonoText
import org.itantra.core.ModelPaths

@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val modelsRoot = remember { File(context.getExternalFilesDir(null), "models") }
    val entries = remember(modelsRoot) { ExpectedModelFiles.list(ModelPaths(modelsRoot)) }
    val devicePushRoot = "/sdcard/Android/data/org.itantra.app/files/models"

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        MonoText(modelsRoot.absolutePath, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonoText("adb push <local> $devicePushRoot/<relative>", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        val missing = entries.count { !it.present }
        MonoText(
            "${entries.size - missing}/${entries.size} PRESENT",
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { entry -> ModelRow(entry, devicePushRoot) }
        }
    }
}

@Composable
private fun ModelRow(entry: ModelFileEntry, devicePushRoot: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            MonoText(if (entry.present) "✓" else "—", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            MonoText(entry.relativePath, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (entry.present) MonoText("${entry.sizeBytes / 1024}KB", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!entry.present) {
            MonoText(
                "adb push <local> $devicePushRoot/${entry.relativePath}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
    }
}
