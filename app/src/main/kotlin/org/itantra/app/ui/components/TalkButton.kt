package org.itantra.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.itantra.app.R
import org.itantra.app.ui.theme.MonoText
import org.itantra.app.ui.theme.Labels
import org.itantra.core.Lang

private val TALK_BUTTON_CORNER = 20.dp

/**
 * The big hold-to-talk key — filled saffron (primary/transmit colour per brand rule) at rest, so
 * it unambiguously reads as *the* main action rather than a secondary/outlined control, captioned
 * in the *conversation* language via [Labels.pttOf] with a mic glyph. No idle animation — it only
 * moves in response to an actual state change (held/gated). While held it shows a live mic
 * waveform driven by [micLevel] (itself only updated while [org.itantra.app.MicCapture] is
 * running, so this costs nothing while idle) plus a TX timer, and haptics fire on press/release.
 * When gated (peer talking / our own TTS playing) it goes visibly flat/disabled and explains why.
 * Sizing (width/height) is the caller's job (see the bottom deck's row 3 in [org.itantra.app.ui.Composer]).
 */
@Composable
fun TalkButton(
    language: Lang,
    held: Boolean,
    peerTalking: Boolean,
    ttsPlaying: Boolean,
    micLevel: Float,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !peerTalking && !ttsPlaying
    val haptics = LocalHapticFeedback.current
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(held) {
        if (held) {
            elapsedSec = 0
            while (true) {
                delay(1000)
                elapsedSec++
            }
        }
    }
    val bg = when {
        held -> MaterialTheme.colorScheme.primary // saffron = transmit
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.primary // filled saffron at rest — the main action
    }
    val restContent = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val pttLabel = Labels.pttOf(language)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(TALK_BUTTON_CORNER))
            .background(bg)
            .semantics { contentDescription = pttLabel }
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPress()
                                tryAwaitRelease()
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onRelease()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        when {
            held -> {
                MonoText("TX %02d:%02d".format(elapsedSec / 60, elapsedSec % 60), color = Color.White, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                MicWaveform(level = micLevel, color = Color.White)
            }
            ttsPlaying -> GatedLabel(stringResource(R.string.disabled_playing))
            peerTalking -> GatedLabel(stringResource(R.string.label_peer_talking))
            else -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = restContent, modifier = Modifier.size(22.dp))
                Text(
                    pttLabel,
                    color = restContent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GatedLabel(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * Live mic-level waveform: bars driven directly by [level] (updated on every mic chunk while held
 * — see [org.itantra.app.TalkService.startMic]), with a small deterministic per-bar phase offset
 * so it reads as an organic waveform rather than a flat equalizer ladder. No [rememberInfiniteTransition]
 * here: it only ever recomposes because [level] itself changes, i.e. only while actively recording.
 */
@Composable
private fun MicWaveform(level: Float, color: Color, bars: Int = 16) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(22.dp)) {
        repeat(bars) { i ->
            val phase = sin((i * 0.9f) + level * 6f).let { (it + 1f) / 2f } // 0..1 organic jitter
            val target = (level * (0.5f + 0.5f * phase)).coerceIn(0.08f, 1f)
            val h by animateFloatAsState(target, label = "wave$i")
            WaveformBar(color = color, height = h)
        }
    }
}

@Composable
private fun WaveformBar(color: Color, height: Float) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .width(3.dp)
            .fillMaxHeight(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = 0.55f + 0.45f * height)),
    )
}
