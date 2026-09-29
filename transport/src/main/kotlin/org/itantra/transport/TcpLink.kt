package org.itantra.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.itantra.core.Frame
import org.itantra.transport.crypto.AlertTrustStore
import org.itantra.transport.crypto.DeviceIdentity
import org.itantra.transport.crypto.SecureSession
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

/**
 * Shared socket-handling code for one connected TCP link: framed reads/writes via [FrameCodec]
 * (bytes) + [WireCodec] (JSON vs [BinaryFrameCodec] choice/auto-detect), TCP_NODELAY, a reader
 * coroutine that pushes decoded frames to [onFrame]. Used by both [WifiTransport] (same-LAN/NSD
 * connections) and Wi-Fi Direct connections handed off from [WifiDirectHelper] so both paths share
 * identical wire handling.
 *
 * [useBinary]/[session]/[signWith]/[trustStore] are lambdas (not fixed values) so the owning
 * [WifiTransport] can update peer-capability/pairing state after this link is already connected.
 */
internal class TcpLink(
    private val socket: Socket,
    scope: CoroutineScope,
    private val useBinary: () -> Boolean = { false },
    private val session: () -> SecureSession? = { null },
    private val signWith: () -> DeviceIdentity? = { null },
    private val trustStore: () -> AlertTrustStore? = { null },
    private val onFrame: (Frame) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val writeLock = Mutex()
    private val output = socket.getOutputStream()
    private val input = DataInputStream(socket.getInputStream())
    @Volatile private var closed = false

    init {
        runCatching { socket.tcpNoDelay = true }
    }

    val readerJob: Job = scope.launch(Dispatchers.IO) {
        try {
            while (isActive) {
                val bytes = FrameCodec.readTcpFrameBytes(input) ?: break
                // A frame that fails to decode (corrupt, tampered, or an AEAD tag that doesn't
                // verify - see BinaryFrameCodec) is dropped rather than killing the reader loop.
                val frame = WireCodec.decode(bytes, session(), trustStore()) ?: continue
                onFrame(frame)
            }
        } catch (e: IOException) {
            // link dropped; fall through to cleanup
        } finally {
            close()
        }
    }

    suspend fun send(frame: Frame): Boolean = withContext(Dispatchers.IO) {
        if (closed) return@withContext false
        try {
            val bytes = WireCodec.encode(frame, binary = useBinary(), session = session(), signWith = signWith())
            writeLock.withLock { FrameCodec.writeTcpFrameBytes(output, bytes) }
            true
        } catch (e: IOException) {
            close()
            false
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        onClosed()
    }
}
