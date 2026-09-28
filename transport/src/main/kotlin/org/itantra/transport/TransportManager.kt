package org.itantra.transport

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.itantra.core.Frame
import org.itantra.core.LinkKind
import org.itantra.core.LinkState
import org.itantra.core.Transport

private const val WIFI_DIRECT_FALLBACK_DELAY_MS = 4_000L
private const val BLE_FALLBACK_DELAY_MS = 8_000L

/**
 * App-facing [Transport]: starts Wi-Fi ([WifiTransport], NSD/LAN); if it hasn't connected within
 * ~4s also starts Wi-Fi Direct ([WifiDirectHelper]) so two phones with no shared router/hotspot
 * can still find each other (a connected P2P socket is handed back into [WifiTransport.attach],
 * so it shows up as an ordinary [LinkKind.WIFI] link); if *neither* has connected within ~8s,
 * starts BLE ([BleTransport]) as a last-resort fallback. [kind]/[state] reflect whichever link is
 * active, [incoming] merges all of them, and [send] prefers Wi-Fi, falling back to BLE. Sends
 * [Frame.Hello] as soon as a link connects.
 */
class TransportManager(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val scope: CoroutineScope,
) : Transport {

    private val wifi = WifiTransport(context, deviceId, deviceName, scope)
    private val ble = BleTransport(context, deviceId, deviceName, scope)
    private val wifiDirect = WifiDirectHelper(context, scope) { socket ->
        wifi.attach(socket, peerName = "wifi-direct-peer")
    }

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Frame> = _incoming.asSharedFlow()

    @Volatile private var activeKind: LinkKind? = null
    @Volatile private var wifiDirectStarted = false
    override val kind: LinkKind get() = activeKind ?: LinkKind.WIFI

    private var supervisorJob: Job? = null
    private var wifiDirectFallbackJob: Job? = null
    private var bleFallbackJob: Job? = null

    override suspend fun start() {
        _state.value = LinkState.Searching
        supervisorJob = scope.launch {
            launch { wifi.incoming.collect { _incoming.emit(it) } }
            launch { ble.incoming.collect { _incoming.emit(it) } }
            launch { wifi.state.collect { onLinkState(LinkKind.WIFI, it) } }
            launch { ble.state.collect { onLinkState(LinkKind.BLE, it) } }
        }
        wifi.start()
        wifiDirectFallbackJob = scope.launch {
            delay(WIFI_DIRECT_FALLBACK_DELAY_MS)
            if (activeKind != LinkKind.WIFI) {
                wifiDirectStarted = true
                wifiDirect.start()
            }
        }
        bleFallbackJob = scope.launch {
            delay(BLE_FALLBACK_DELAY_MS)
            if (activeKind != LinkKind.WIFI) {
                ble.start()
            }
        }
    }

    override suspend fun stop() {
        wifiDirectFallbackJob?.cancel()
        wifiDirectFallbackJob = null
        bleFallbackJob?.cancel()
        bleFallbackJob = null
        supervisorJob?.cancel()
        supervisorJob = null
        wifi.stop()
        ble.stop()
        if (wifiDirectStarted) {
            wifiDirect.stop()
            wifiDirectStarted = false
        }
        activeKind = null
        _state.value = LinkState.Idle
    }

    override suspend fun send(frame: Frame): Boolean {
        if (wifi.send(frame)) return true
        return ble.send(frame)
    }

    override fun close() {
        scope.launch { stop() }
    }

    private suspend fun onLinkState(source: LinkKind, newState: LinkState) {
        if (newState is LinkState.Connected) {
            // Wi-Fi always wins the active slot if it connects, even if BLE connected first.
            if (activeKind == null || source == LinkKind.WIFI) {
                activeKind = source
                _state.value = newState
                send(Frame.Hello(deviceId, deviceName))
                // A LAN/NSD (or already-attached Wi-Fi Direct) socket is now the active link;
                // no need to keep hunting for a second Wi-Fi Direct peer.
                if (source == LinkKind.WIFI && wifiDirectStarted) {
                    wifiDirect.stop()
                    wifiDirectStarted = false
                }
            }
            // else: BLE connected while Wi-Fi is already active - ignore, Wi-Fi stays primary.
        } else if (source == activeKind) {
            activeKind = null
            _state.value = newState
        } else if (activeKind == null) {
            _state.value = newState
        }
    }
}
