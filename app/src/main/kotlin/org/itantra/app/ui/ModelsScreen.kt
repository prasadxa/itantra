package org.itantra.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import org.itantra.app.ExpectedModelFiles
import org.itantra.app.ModelFileEntry
import org.itantra.core.ModelPaths

@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val modelsRoot = remember { File(context.getExternalFilesDir(null), "models") }
    val entries = remember(modelsRoot) { ExpectedModelFiles.list(ModelPaths(modelsRoot)) }
    val devicePushRoot = "/sdcard/Android/data/org.itantra.app/files/models"

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Models", style = MaterialTheme.typography.titleLarge)
        Text("Root: ${modelsRoot.absolutePath}", style = MaterialTheme.typography.labelSmall)
        Text("adb push <local-file> $devicePushRoot/<relative-path>", style = MaterialTheme.typography.labelSmall)
        val missing = entries.count { !it.present }
        Text("${entries.size - missing}/${entries.size} present", style = MaterialTheme.typography.labelMedium)

        LazyColumn(Modifier.fillMaxSize()) {
            items(entries) { entry -> ModelRow(entry, devicePushRoot) }
        }
    }
}

@Composable
private fun ModelRow(entry: ModelFileEntry, devicePushRoot: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(if (entry.present) "✓" else "✗", color = if (entry.present) Color(0xFF2E7D32) else Color.Red)
            Text(" ${entry.relativePath}", style = MaterialTheme.typography.bodyMedium)
        }
        if (entry.present) {
            Text("  ${entry.sizeBytes / 1024} KB", style = MaterialTheme.typography.labelSmall)
        } else {
            Text("  adb push <local> $devicePushRoot/${entry.relativePath}", style = MaterialTheme.typography.labelSmall)
        }
    }
}
