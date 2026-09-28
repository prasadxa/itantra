package org.itantra.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import org.itantra.app.ExpectedModelFiles
import org.itantra.app.ModelFileEntry
import org.itantra.app.Profile
import org.itantra.app.ProfileManager
import org.itantra.app.ProfileOverride
import org.itantra.app.TalkService
import org.itantra.app.ui.theme.MonoText
import org.itantra.core.ModelPaths

@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val modelsRoot = remember { File(context.getExternalFilesDir(null), "models") }
    val entries = remember(modelsRoot) { ExpectedModelFiles.list(ModelPaths(modelsRoot)) }
    val devicePushRoot = "/sdcard/Android/data/org.itantra.app/files/models"

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        ProfileRow(context)
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)
        Spacer(Modifier.height(8.dp))

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

/**
 * Auto/Full/Lite override for [Profile] (see [ProfileManager]) — lets testers force LITE on a
 * high-RAM phone (or FULL on a low-RAM one) to validate both paths. Takes effect on the next
 * service start, so switching restarts [TalkService] if it's already running.
 */
@Composable
private fun ProfileRow(context: android.content.Context) {
    var override by remember { mutableStateOf(ProfileManager.getOverride(context)) }
    val autoDetected = remember { ProfileManager.detect(context) }
    val effective = if (override == ProfileOverride.AUTO) autoDetected else if (override == ProfileOverride.FULL) Profile.FULL else Profile.LITE

    Column {
        MonoText("PROFILE · running ${effective.name}", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        MonoText(
            "auto-detected ${autoDetected.name} from device RAM (< 6GB -> LITE)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(Modifier.padding(top = 8.dp)) {
            ProfileOverride.entries.forEach { opt ->
                ProfileChip(opt.name, selected = override == opt) {
                    override = opt
                    ProfileManager.setOverride(context, opt)
                    val running = TalkService.instance != null
                    TalkService.stop(context)
                    if (running) TalkService.start(context)
                }
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun ProfileChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
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
