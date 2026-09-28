package org.itantra.transport

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
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
 * A caller (typically [WifiTransport] or the app) drives the Wi-Fi P2P broadcast lifecycle and
 * calls [discoverPeers]/[connect]/[onConnectionInfoAvailable]; once a group forms, the group
 * owner listens on [WIFI_DIRECT_PORT] and the client dials `groupOwnerAddress` there. The
 * resulting connected [Socket] is handed to [onConnected] so callers reuse the same [TcpLink]
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

    fun start() {
        channel = manager.initialize(context, context.mainLooper, null)
        discoverPeers()
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
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        manager.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                Log.w(TAG, "connect failed: $reason")
            }
        })
    }

    /** Feed the `WIFI_P2P_CONNECTION_CHANGED_ACTION` broadcast's connection info here. */
    fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (!info.groupFormed) return
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

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        channel?.let { manager.stopPeerDiscovery(it, null) }
    }
}
