package org.itantra.transport.crypto

/**
 * Maps a peer's `sender short id` (see `BinaryFrameCodec`) to the Ed25519 public key it is
 * authorised to sign ALERT frames with. Populate via [trust] once a [PairingInfo] is accepted
 * (e.g. after scanning its QR code) using `BinaryFrameCodec.senderShortIdFor(pairingInfo.deviceId)`
 * as the key. An ALERT from an unknown or invalid-signature sender is downgraded to NORMAL by
 * `BinaryFrameCodec.decodeDetailed` rather than rejected outright, so the message still gets
 * through — just without the "trusted alert" treatment.
 */
class AlertTrustStore {
    private val keysByShortId = HashMap<Int, ByteArray>()

    @Synchronized
    fun trust(senderShortId: Int, ed25519PublicKey: ByteArray) {
        require(ed25519PublicKey.size == 32) { "ed25519 public key must be 32 bytes" }
        keysByShortId[senderShortId] = ed25519PublicKey.copyOf()
    }

    @Synchronized
    fun trust(pairingInfo: PairingInfo, senderShortId: Int) {
        trust(senderShortId, pairingInfo.ed25519PublicKey)
    }

    @Synchronized
    fun publicKeyFor(senderShortId: Int): ByteArray? = keysByShortId[senderShortId]?.copyOf()

    @Synchronized
    fun revoke(senderShortId: Int) {
        keysByShortId.remove(senderShortId)
    }
}
