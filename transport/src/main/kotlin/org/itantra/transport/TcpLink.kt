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
import java.io.DataInputStream
import java.io.IOException
import java.net.Socket

/**
 * Shared socket-handling code for one connected TCP link: framed reads/writes via [FrameCodec],
 * TCP_NODELAY, a reader coroutine that pushes decoded frames to [onFrame]. Used by both
 * [WifiTransport] (same-LAN/NSD connections) and Wi-Fi Direct connections handed off from
 * [WifiDirectHelper] so both paths share identical wire handling.
 */
internal class TcpLink(
    private val socket: Socket,
    scope: CoroutineScope,
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
                val frame = FrameCodec.readTcpFrame(input) ?: break
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
            writeLock.withLock { FrameCodec.writeTcpFrame(output, frame) }
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
