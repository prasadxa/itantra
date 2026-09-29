package org.itantra.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import org.itantra.app.ui.theme.rememberReducedMotion

/** Short, sharp double-pulse waveform (ms): off, on, off, on, off, on-long — distinct from a plain
 * notification buzz so an incoming ALERT is felt as urgent. Needs `android.permission.VIBRATE`
 * (declared in AndroidManifest.xml — it's a normal permission, no runtime grant required). */
private val ALERT_WAVEFORM = longArrayOf(0, 140, 90, 140, 90, 320)

fun vibrateAlert(context: Context) {
    runCatching {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(ALERT_WAVEFORM, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ALERT_WAVEFORM, -1)
        }
    }
}

/**
 * Finite flash for a newly-arrived ALERT card: alpha pulses a handful of times then settles at 1,
 * same "pulse a few times then rest" discipline as [org.itantra.app.ui.AppRoot]'s PulsingDot — idle
 * CPU is judged, so this must not keep animating forever. [play] should be true only once, the
 * first time a given alert id is composed (caller tracks which ids have already been announced).
 */
@Composable
fun rememberAlertFlash(play: Boolean): Float {
    val reducedMotion = rememberReducedMotion()
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(play) {
        if (!play || reducedMotion) {
            alpha.snapTo(1f)
            return@LaunchedEffect
        }
        repeat(3) {
            alpha.animateTo(0.45f, tween(180))
            alpha.animateTo(1f, tween(180))
        }
    }
    return alpha.value
}
