package org.itantra.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "WifiDirectHelper"
internal const val WIFI_DIRECT_PORT = 8988

/**
 * Wi-Fi Direct fallback for when both phones share no LAN/hotspot for NSD to find each other on.
 * Owns the full `WIFI_P2P_*` broadcast lifecycle itself: [start] initializes the P2P channel,
 * registers a [BroadcastReceiver] for peer/connection/state changes and kicks off
 * [discoverPeers]; peer changes auto-[connect] to the lowest-address not-yet-connected peer
 * (mirrors [WifiTransport]'s "lower id dials" rule so both sides don't race `connect()`), and
 * connection changes feed [onConnectionInfoAvailable]. Once a group forms, the group owner
 * listens on [WIFI_DIRECT_PORT] and the client dials `groupOwnerAddress` there. The resulting
 * connected [Socket] is handed to [onConnected] so callers reuse the same [TcpLink]
 * socket-handling code as the NSD path (e.g. `WifiTransport.attach`).
 */
class WifiDirectHelper(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onConnected: (Socket) -> Unit,
) {
    private val manager by lazy { context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager }
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile private var myAddress: String? = null
    @Volatile private var connecting = false
    @Volatile private var groupFormed = false

    fun start() {
        channel = manager.initialize(context, context.mainLooper, null)
        registerReceiver()
        discoverPeers()
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        channel?.let { manager.stopPeerDiscovery(it, null) }
        unregisterReceiver()
        connecting = false
        groupFormed = false
    }

    fun discoverPeers() {
        val ch = channel ?: return
        manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                Log.w(TAG, "discoverPeers failed: $reason")
            }
        })
    }

    fun connect(device: WifiP2pDevice) {
        val ch = channel ?: return
        connecting = true
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                connecting = false
                Log.w(TAG, "connect failed: $reason")
            }
        })
    }

    /** Feed the `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast's connection info here. */
    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        groupFormed = true
        connecting = false
        scope.launch(Dispatchers.IO) {
            try {
                if (info.isGroupOwner) {
                    val server = ServerSocket(WIFI_DIRECT_PORT)
                    serverSocket = server
                    val socket = server.accept()
                    onConnected(socket)
                } else {
                    val address = info.groupOwnerAddress ?: return@launch
                    val socket = Socket()
                    socket.connect(InetSocketAddress(address, WIFI_DIRECT_PORT), 8000)
                    onConnected(socket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "P2P socket setup failed: ${e.message}")
            }
        }
    }

    // ---- WIFI_P2P_* broadcast lifecycle glue ----

    private fun registerReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeersChanged()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnectionChanged()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> onThisDeviceChanged(intent)
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                            WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        Log.d(TAG, "P2P state changed: enabled=$enabled")
                        if (enabled) discoverPeers()
                    }
                }
            }
        }
        receiver = r
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun onThisDeviceChanged(intent: Intent) {
        val device: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
        }
        myAddress = device?.deviceAddress
    }

    private fun onPeersChanged() {
        val ch = channel ?: return
        if (groupFormed || connecting) return
        manager.requestPeers(ch) { peers: WifiP2pDeviceList ->
            if (groupFormed || connecting) return@requestPeers
            val candidate = peers.deviceList
                .filter { it.status != WifiP2pDevice.CONNECTED }
                .minByOrNull { it.deviceAddress }
                ?: return@requestPeers
            // Deterministic dial, mirroring WifiTransport's NSD rule: only the side whose own
            // address sorts lower initiates connect(), so both peers don't race each other.
            val mine = myAddress
            if (mine != null && mine > candidate.deviceAddress) return@requestPeers
            connect(candidate)
        }
    }

    private fun onConnectionChanged() {
        val ch = channel ?: return
        manager.requestConnectionInfo(ch) { info -> onConnectionInfoAvailable(info) }
    }
}
