package org.itantra.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.itantra.core.Frame
import org.itantra.core.Lang
import org.itantra.core.LinkKind
import org.itantra.core.LinkState
import org.itantra.core.Transport
import org.itantra.stt.CaptureMode

/**
 * Foreground service holding the engines, transport and [Orchestrator]. Must be started from a visible
 * Activity (Android 14 foreground-service-from-background rule) — see [MainActivity].
 */
class TalkService : LifecycleService() {

    companion object {
        private const val CHANNEL_ID = "talk_service"
        private const val NOTIF_ID = 1

        @Volatile
        var instance: TalkService? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TalkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TalkService::class.java))
        }
    }

    private lateinit var engines: EngineBundle
    private lateinit var orchestrator: Orchestrator
    private lateinit var transport: Transport
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
        acquireLocks()

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "itantra-device"
        val deviceName = Build.MODEL ?: "iTantra"

        engines = EngineFactory.create(applicationContext, deviceId, deviceName, lifecycleScope)
        AppRepository.engineStatus.value = engines.status
        transport = engines.transport ?: NullTransport("transport module unavailable")

        orchestrator = Orchestrator(
            deviceId = deviceId,
            stt = engines.stt,
            textPipeline = engines.textPipeline,
            speechOutput = engines.speechOutput,
            transport = transport,
            scope = lifecycleScope,
            onLog = AppRepository::addLog,
            onMetrics = AppRepository::addOrUpdateMetrics,
            onPeerTalking = { AppRepository.peerTalking.value = it },
            onRtt = { rtt, offset -> AppRepository.rttMs.value = rtt; AppRepository.clockOffsetMs.value = offset },
            alertProvider = { AppRepository.alertNext.value },
        ).also { it.mode = AppRepository.mode.value; it.language = AppRepository.language.value }

        lifecycleScope.launch { transport.state.collect { AppRepository.linkState.value = it } }
        lifecycleScope.launch { transport.start() }
        orchestrator.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    fun onModeChanged(mode: Mode) {
        orchestrator.mode = mode
        AppRepository.mode.value = mode
        stopMic()
        if (mode == Mode.PHONE) startMic(CaptureMode.PHONE)
    }

    fun onLanguageChanged(lang: Lang) {
        orchestrator.language = lang
        AppRepository.language.value = lang
    }

    fun onPttPressed() {
        AppRepository.pttHeld.value = true
        orchestrator.onPttPressed()
        startMic(CaptureMode.PTT)
    }

    fun onPttReleased() {
        AppRepository.pttHeld.value = false
        orchestrator.onPttReleased()
        stopMic()
    }

    fun onTextSend(text: String, lang: Lang, ssml: Boolean) {
        if (text.isBlank()) return
        orchestrator.sendText(text, lang, ssml)
    }

    private fun startMic(mode: CaptureMode) {
        val mic = engines.micCapture ?: return
        if (!mic.isRunning) mic.start(mode) { samples -> orchestrator.onAudioSamples(samples) }
    }

    private fun stopMic() {
        val mic = engines.micCapture ?: return
        if (mic.isRunning) mic.stop()
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "iTantra talk link", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iTantra")
            .setContentText("Voice link active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        AppRepository.serviceRunning.value = true
    }

    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "itantra:talk")?.apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wm?.createWifiLock(lockType, "itantra:wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        orchestrator.stop()
        stopMic()
        lifecycleScope.launch { transport.stop() }
        runCatching { engines.stt?.close() }
        runCatching { engines.speechOutput?.close() }
        runCatching { engines.transport?.close() }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        instance = null
        AppRepository.serviceRunning.value = false
        super.onDestroy()
    }
}

/** Stand-in used when :transport failed to construct, so the rest of the app degrades gracefully. */
private class NullTransport(reason: String) : Transport {
    override val kind = LinkKind.WIFI
    override val state = MutableStateFlow<LinkState>(LinkState.Failed(reason))
    override val incoming = MutableSharedFlow<Frame>()
    override suspend fun start() {}
    override suspend fun stop() {}
    override suspend fun send(frame: Frame): Boolean = false
    override fun close() {}
}
