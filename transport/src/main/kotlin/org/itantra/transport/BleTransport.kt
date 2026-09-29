package org.itantra.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
import org.itantra.transport.crypto.AlertTrustStore
import org.itantra.transport.crypto.DeviceIdentity
import org.itantra.transport.crypto.SecureSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "BleTransport"

/** Nordic-UART-style service: RX (write, client -> server) and TX (notify, server -> client). */
val BLE_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
val BLE_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
val BLE_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val REQUESTED_MTU = 517

// Legacy BLE advertising PDUs (what BluetoothLeAdvertiser.startAdvertising sends) are capped at
// 31 bytes *per packet*. A "Service Data - 128-bit UUID" AD structure alone costs
// 1(len) + 1(type) + 16(UUID) = 18 bytes, leaving <= 13 bytes for the payload even with nothing
// else in that packet - so deviceId has to be truncated and split into its own scan-response
// packet (see startAdvertising()) rather than sharing the primary packet with the service UUID.
private const val DEVICE_ID_BYTES = 12

/**
 * BLE fallback transport (kind [LinkKind.BLE]). Every phone runs both roles at once: a GATT
 * server advertising [BLE_SERVICE_UUID] (peripheral), and a scanner filtering on it (central).
 * Discovered peers publish their `deviceId` as BLE service data; the lexicographically smaller
 * `deviceId` becomes the GATT client (dials, writes RX, subscribes to TX notifications) while the
 * other simply waits for that connection on its GATT server. Frames are fragmented per
 * [FrameCodec.chunkForBle] once `requestMtu(517)` completes.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
    private val scope: CoroutineScope,
) : Transport {

    override val kind: LinkKind = LinkKind.BLE

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Frame>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Frame> = _incoming.asSharedFlow()

    private val bluetoothManager by lazy { context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter get() = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var clientGatt: BluetoothGatt? = null

    private var running = false
    private var mtu = 23 // default ATT MTU until requestMtu completes
    private val seqCounter = AtomicInteger(0)
    private val reassembler = BleReassembler()

    // Server (peripheral) side of the active link, when we're the one being dialed.
    private var serverPeerDevice: BluetoothDevice? = null
    private var serverTxChar: BluetoothGattCharacteristic? = null
    // Client (central) side of the active link, when we're the dialer.
    private var clientRxChar: BluetoothGattCharacteristic? = null

    @Volatile private var connected = false

    // Binary-frame negotiation (see Negotiation.kt) and end-to-end security state for the active
    // link, mirroring WifiTransport. All off/unset by default.
    @Volatile private var binaryNegotiated = false
    var secureSession: SecureSession? = null
    var alertSigner: DeviceIdentity? = null
    var alertTrustStore: AlertTrustStore? = null

    override suspend fun start() {
        if (running) return
        running = true
        connected = false
        binaryNegotiated = false
        _state.value = LinkState.Searching
        startGattServer()
        startAdvertising()
        startScanning()
    }

    override suspend fun stop() {
        running = false
        connected = false
        binaryNegotiated = false
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { clientGatt?.disconnect() }
        runCatching { clientGatt?.close() }
        runCatching { gattServer?.close() }
        clientGatt = null
        gattServer = null
        serverPeerDevice = null
        serverTxChar = null
        clientRxChar = null
        _state.value = LinkState.Idle
    }

    override suspend fun send(frame: Frame): Boolean {
        if (!connected) return false
        val seq = seqCounter.getAndIncrement()
        val chunks = try {
            val bytes = WireCodec.encode(frame, binary = binaryNegotiated, session = secureSession, signWith = alertSigner)
            FrameCodec.chunkBytesForBle(bytes, seq, mtu)
        } catch (e: Exception) {
            Log.w(TAG, "chunking failed: ${e.message}")
            return false
        }
        return try {
            for (chunk in chunks) {
                val ok = writeClient(chunk) || notifyServer(chunk)
                if (!ok) return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun close() {
        scope.launch { stop() }
    }

    // ---- GATT server (peripheral) role: accepts the connection from the dialing peer ----

    private fun startGattServer() {
        val rx = BluetoothGattCharacteristic(
            BLE_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            BLE_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        val service = BluetoothGattService(BLE_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(rx)
            addCharacteristic(tx)
        }
        val server = bluetoothManager.openGattServer(context, gattServerCallback)
        server?.addService(service)
        gattServer = server
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && serverPeerDevice?.address == device.address) {
                serverPeerDevice = null
                serverTxChar = null
                if (connected) onLinkLost()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BLE_RX_CHAR_UUID) {
                if (!connected) onLinkEstablished(device.name ?: "ble-peer")
                reassembler.accept(value, secureSession, alertTrustStore)?.let { handleIncoming(it) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                serverTxChar = descriptor.characteristic
                serverPeerDevice = device
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            mtu = newMtu
        }
    }

    @Suppress("DEPRECATION")
    private fun notifyServer(chunk: ByteArray): Boolean {
        val server = gattServer ?: return false
        val device = serverPeerDevice ?: return false
        val char = serverTxChar ?: return false
        char.value = chunk
        return server.notifyCharacteristicChanged(device, char, false)
    }

    // ---- Advertising: broadcasts BLE_SERVICE_UUID + our deviceId so peers can tie-break ----

    private fun startAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // Primary packet: just the service UUID (18 bytes), so central devices can filter-scan
        // for us. deviceId (needed by the scanning side to tie-break who dials, see
        // scanCallback) goes in the scan-response packet instead - splitting it out is what
        // keeps each individual packet under the 31-byte legacy advertising limit.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BLE_SERVICE_UUID))
            .build()
        val idBytes = deviceId.toByteArray(Charsets.UTF_8).copyOf(DEVICE_ID_BYTES)
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(ParcelUuid(BLE_SERVICE_UUID), idBytes)
            .build()
        adv.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "BLE advertise failed: $errorCode")
        }
    }

    // ---- Scanning / client role: dials the peer iff we have the smaller deviceId ----

    private fun startScanning() {
        val sc = adapter?.bluetoothLeScanner ?: return
        scanner = sc
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        sc.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (connected || clientGatt != null) return
            val data = result.scanRecord?.getServiceData(ParcelUuid(BLE_SERVICE_UUID)) ?: return
            val remoteId = String(data, Charsets.UTF_8).trimEnd('\u0000')
            if (remoteId.isEmpty() || remoteId == deviceId) return
            if (deviceId >= remoteId) return // not the dialer; peer connects to our GATT server
            runCatching { scanner?.stopScan(this) }
            clientGatt = result.device.connectGatt(context, false, clientCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(REQUESTED_MTU)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clientGatt = null
                    clientRxChar = null
                    if (connected) onLinkLost()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(BLE_SERVICE_UUID) ?: return
            clientRxChar = service.getCharacteristic(BLE_RX_CHAR_UUID)
            val tx = service.getCharacteristic(BLE_TX_CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD_UUID) ?: return
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
            onLinkEstablished(gatt.device.name ?: "ble-peer")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BLE_TX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                reassembler.accept(characteristic.value, secureSession, alertTrustStore)?.let { handleIncoming(it) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeClient(chunk: ByteArray): Boolean {
        val gatt = clientGatt ?: return false
        val char = clientRxChar ?: return false
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        char.value = chunk
        return gatt.writeCharacteristic(char)
    }

    // ---- shared connect/disconnect bookkeeping ----

    /** Peeks at [Frame.Hello] for binary-capability negotiation (see Negotiation.kt), then forwards. */
    private fun handleIncoming(frame: Frame) {
        if (frame is Frame.Hello && peerSupportsBinary(frame.protocol)) binaryNegotiated = true
        scope.launch { _incoming.emit(frame) }
    }

    private fun onLinkEstablished(peerName: String) {
        connected = true
        runCatching { scanner?.stopScan(scanCallback) }
        _state.value = LinkState.Connected(LinkKind.BLE, peerName)
    }

    private fun onLinkLost() {
        connected = false
        binaryNegotiated = false
        if (running) {
            _state.value = LinkState.Searching
            startScanning()
        }
    }
}
