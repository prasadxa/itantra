package org.itantra.transport

import org.itantra.core.Frame
import org.itantra.transport.crypto.AlertTrustStore
import org.itantra.transport.crypto.DeviceIdentity
import org.itantra.transport.crypto.SecureSession

/**
 * Picks JSON ([FrameCodec]) vs compact binary ([BinaryFrameCodec]) per frame, and auto-detects
 * which one a received byte buffer uses — so the two links (Wi-Fi/[TcpLink], [BleTransport]) don't
 * need to track "am I mid-negotiation" on the receive path at all.
 *
 * Every [FrameCodec]-encoded frame is a single-line JSON object, so it always starts with `{`
 * (`0x7B`). Every [BinaryFrameCodec] frame's first byte is `version(4b) | type(4b)` with
 * [BinaryFrameCodec.VERSION] = 1, i.e. always in `0x10..0x1F` — which can never collide with
 * `0x7B`. [Frame.Hello] is always sent as JSON (see [WifiTransport]/[BleTransport]) so the very
 * first frame on a fresh link is decodable before any capability negotiation has happened; that
 * also keeps `tools/peer_sim.py` (JSON-only) able to receive it.
 */
object WireCodec {
    private const val JSON_FIRST_BYTE = '{'.code.toByte()

    fun looksLikeJson(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == JSON_FIRST_BYTE

    fun encode(frame: Frame, binary: Boolean, session: SecureSession? = null, signWith: DeviceIdentity? = null): ByteArray =
        if (binary && frame !is Frame.Hello) {
            BinaryFrameCodec.encode(frame, session = session, signWith = signWith)
        } else {
            FrameCodec.encode(frame)
        }

    /** Auto-detects JSON vs binary. Returns null (rather than throwing) on a malformed/corrupt buffer. */
    fun decode(bytes: ByteArray, session: SecureSession? = null, trustStore: AlertTrustStore? = null): Frame? = try {
        if (looksLikeJson(bytes)) FrameCodec.decode(bytes) else BinaryFrameCodec.decode(bytes, session, trustStore)
    } catch (e: Exception) {
        null
    }
}
