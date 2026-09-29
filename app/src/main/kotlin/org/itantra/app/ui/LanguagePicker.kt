package org.itantra.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import org.itantra.app.R
import org.itantra.core.Lang
import org.itantra.core.ModelPaths

/** Cheap on-disk check ("does this language have a usable voice yet") for the readiness dot on
 * each [LanguageCard] — not a functional gate, just a UI hint; the real engine-selection logic
 * lives in :tts's FallbackTtsEngine. STT (SraVaani) is one shared model across all languages, so
 * readiness here is really "is this language's TTS voice installed". */
fun isLanguageVoiceReady(context: Context, lang: Lang): Boolean {
    val modelsRoot = File(context.getExternalFilesDir(null), "models")
    val paths = ModelPaths(modelsRoot)
    val sttReady = File(paths.sttDir, "encoder.int8.onnx").exists()
    val voiceReady = paths.mioVoice(lang).exists() || File(paths.vitsDir, "model.onnx").exists()
    return sttReady && voiceReady
}

/**
 * Grid of language cards — native name large, English name small, a readiness dot (green =
 * voice installed, outline = not yet). Used both on onboarding step 1 and the main-screen
 * language-change sheet, so language selection always looks and behaves the same way.
 */
@Composable
fun LanguagePickerGrid(
    modifier: Modifier = Modifier,
    selected: Lang?,
    onSelect: (Lang) -> Unit,
) {
    val context = LocalContext.current
    val readiness = remember { Lang.entries.associateWith { isLanguageVoiceReady(context, it) } }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(Lang.entries.toList()) { lang ->
            LanguageCard(
                lang = lang,
                isSelected = lang == selected,
                isReady = readiness[lang] == true,
                onClick = { onSelect(lang) },
            )
        }
    }
}

@Composable
private fun LanguageCard(lang: Lang, isSelected: Boolean, isReady: Boolean, onClick: () -> Unit) {
    val readyLabel = stringResource(if (isReady) R.string.language_ready else R.string.language_not_ready)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable(onClickLabel = "${lang.label}, $readyLabel", onClick = onClick)
            .semantics { contentDescription = "${lang.label}, $readyLabel" }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline),
        )
        Column {
            Text(
                lang.label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                lang.englishName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** English name per [Lang], for the small caption under the native name — [Lang.label] is only
 * the native-script name. */
val Lang.englishName: String get() = when (this) {
    Lang.HI -> "Hindi"
    Lang.GU -> "Gujarati"
    Lang.MR -> "Marathi"
    Lang.KN -> "Kannada"
    Lang.ML -> "Malayalam"
    Lang.TA -> "Tamil"
    Lang.TE -> "Telugu"
    Lang.OR -> "Odia"
    Lang.BN -> "Bengali"
    Lang.EN -> "English"
}
