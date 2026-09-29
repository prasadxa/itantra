package org.itantra.transport

/**
 * `Frame.Hello.protocol` values. `Frame.Hello`'s shape can't change (other modules depend on it —
 * see [WireCodec]'s class doc for why Hello itself always stays JSON), so "supported formats" is
 * negotiated through this existing field instead of a new one:
 *  - [PROTOCOL_JSON_ONLY] (1, the pre-existing default): JSON only — legacy peers and
 *    `tools/peer_sim.py`.
 *  - [PROTOCOL_BINARY_CAPABLE] (2): this build; also understands [BinaryFrameCodec]'s compact wire
 *    format. [WifiTransport]/[BleTransport] send this value and switch a link to binary once the
 *    peer's Hello reports it too — see [WireCodec].
 */
const val PROTOCOL_JSON_ONLY = 1
const val PROTOCOL_BINARY_CAPABLE = 2

/** True if a peer whose Hello reported [protocol] also understands [BinaryFrameCodec]. */
fun peerSupportsBinary(protocol: Int): Boolean = protocol >= PROTOCOL_BINARY_CAPABLE
