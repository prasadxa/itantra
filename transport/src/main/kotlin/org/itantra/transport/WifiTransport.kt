package org.itantra.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.itantra.core.Frame
import org.itantra.core.LinkKind
import org.itantra.core.LinkState
import org.itantra.core.Transport
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "WifiTransport"
internal const val NSD_SERVICE_TYPE = "_itantra._tcp."

/**
 * Same-LAN/hotspot transport (kind [LinkKind.WIFI]): each phone runs a [ServerSocket] advertised
 * via NSD (mDNS) under [NSD_SERVICE_TYPE], discovers the other, and the lexicographically smaller
 * `deviceId` dials so exactly one TCP connection forms. [WifiDirectHelper] can hand this class a
 * pre-connected socket (Wi-Fi Direct fallback) through [attach] — same [TcpLink] socket handling.
 */
class WifiTransport(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val scope: CoroutineScope,
) : Transport {

    override val kind: LinkKind = LinkKind.WIFI

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Frame> = _incoming.asSharedFlow()

    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    private var serverSocket: ServerSocket? = null
    private var link: TcpLink? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val running = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)

    override suspend fun start() {
        if (!running.compareAndSet(false, true)) return
        _state.value = LinkState.Searching
        withContext(Dispatchers.IO) {
            try {
                val server = ServerSocket(0)
                serverSocket = server
                acceptLoop(server)
                registerService(server.localPort)
                startDiscovery()
            } catch (e: Exception) {
                Log.w(TAG, "start failed: ${e.message}")
                _state.value = LinkState.Failed(e.message ?: "wifi start failed")
            }
        }
    }

    override suspend fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } }
        runCatching { registrationListener?.let { nsdManager.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        link?.close()
        link = null
        _state.value = LinkState.Idle
    }

    override suspend fun send(frame: Frame): Boolean = link?.send(frame) ?: false

    override fun close() {
        scope.launch { stop() }
    }

    /** Attaches an already-connected socket (e.g. from [WifiDirectHelper]) as the active link. */
    fun attach(socket: Socket, peerName: String) {
        link?.close()
        link = TcpLink(
            socket = socket,
            scope = scope,
            onFrame = { frame -> scope.launch { _incoming.emit(frame) } },
            onClosed = {
                link = null
                if (running.get()) {
                    _state.value = LinkState.Searching
                    startDiscovery()
                }
            },
        )
        _state.value = LinkState.Connected(LinkKind.WIFI, peerName)
    }

    private fun acceptLoop(server: ServerSocket) {
        scope.launch(Dispatchers.IO) {
            while (running.get()) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                if (link != null) {
                    runCatching { socket.close() }
                    continue
                }
                attach(socket, peerName = "wifi-peer")
            }
        }
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = deviceId
            serviceType = NSD_SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD register failed: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun startDiscovery() {
        if (!running.get() || discoveryListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {
                discoveryListener = null
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discoveryListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(found: NsdServiceInfo) {
                val remoteId = found.serviceName ?: return
                if (remoteId == deviceId) return
                if (deviceId > remoteId) return // we're not the dialer; peer will connect to us
                if (link != null || !connecting.compareAndSet(false, true)) return
                nsdManager.resolveService(found, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        connecting.set(false)
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        connecting.set(false)
                        scope.launch(Dispatchers.IO) { dial(info) }
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun dial(info: NsdServiceInfo) {
        if (link != null || !running.get()) return
        try {
            val socket = Socket()
            socket.connect(InetSocketAddress(info.host, info.port), 5000)
            attach(socket, peerName = info.serviceName ?: "wifi-peer")
        } catch (e: Exception) {
            Log.w(TAG, "dial failed: ${e.message}")
        }
    }
}
