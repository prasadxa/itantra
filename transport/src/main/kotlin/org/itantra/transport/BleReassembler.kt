package org.itantra.transport

import org.itantra.core.Frame
import org.itantra.transport.crypto.AlertTrustStore
import org.itantra.transport.crypto.SecureSession
import java.io.ByteArrayOutputStream

/**
 * Reassembles BLE packets produced by [FrameCodec.chunkForBle] (3-byte header `[seq, index,
 * count]` + payload) back into [Frame]s. Not thread-safe; feed packets from a single reader.
 *
 * If a packet with a new sequence number arrives before the previous frame's chunks are all in,
 * the incomplete frame is dropped and reassembly restarts from the new sequence.
 */
class BleReassembler {
    private var seq: Int = -1
    private var count: Int = 0
    private var received: Int = 0
    private var parts: Array<ByteArray?> = arrayOf()

    /**
     * Feed one BLE packet. Returns the decoded [Frame] once all its chunks have arrived, or null
     * while still reassembling. A frame whose reassembled bytes fail to decode (JSON or binary,
     * see [WireCodec]) is also reported as null rather than throwing, so one corrupt/tampered frame
     * can't take down the reader loop.
     */
    fun accept(packet: ByteArray, session: SecureSession? = null, trustStore: AlertTrustStore? = null): Frame? {
        if (packet.size < 3) return null
        val pSeq = packet[0].toInt() and 0xFF
        val pIndex = packet[1].toInt() and 0xFF
        val pCount = (packet[2].toInt() and 0xFF).coerceAtLeast(1)

        if (pSeq != seq) {
            // New frame started (or seq wrapped back around): drop whatever was incomplete.
            seq = pSeq
            count = pCount
            received = 0
            parts = arrayOfNulls(pCount)
        }
        if (pIndex !in parts.indices) return null
        if (parts[pIndex] == null) received++
        parts[pIndex] = packet.copyOfRange(3, packet.size)
        if (received < count) return null

        val buf = ByteArrayOutputStream()
        for (part in parts) {
            part ?: return null
            buf.write(part)
        }
        seq = -1 // reset so a repeated/wrapped seq is treated as a fresh frame
        return WireCodec.decode(buf.toByteArray(), session, trustStore)
    }
}
