package org.itantra.transport.crypto

import java.security.SecureRandom

/**
 * A negotiated per-peer encryption context for one link: a 32-byte AEAD key (from
 * [DeviceIdentity.deriveSharedKey], i.e. only exists once both devices are paired) plus a
 * [ReplayGuard] for the receiving direction. "No session" (`null` in the caller) means
 * unencrypted binary frames — matching "encryption optional, off until paired".
 */
class SecureSession internal constructor(
    private val aeadKey: ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    private val replayGuard = ReplayGuard()

    /** Encrypts [plaintext], authenticating [aad] alongside it. Returns `nonce ‖ ciphertext ‖ tag`. */
    fun encrypt(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ChaChaPoly1305.randomNonce(random)
        val sealed = ChaChaPoly1305.seal(aeadKey, nonce, aad, plaintext)
        return nonce + sealed
    }

    /**
     * Splits [wire] (`nonce ‖ ciphertext ‖ tag`) and verifies+decrypts it, checking [aad] and
     * rejecting a reused nonce from [senderShortId] as a replay. Null on tamper, forgery, replay,
     * or malformed input — callers should silently drop the frame.
     */
    fun decrypt(senderShortId: Int, aad: ByteArray, wire: ByteArray): ByteArray? {
        val headerLen = ChaChaPoly1305.NONCE_BYTES
        if (wire.size < headerLen + ChaChaPoly1305.TAG_BYTES) return null
        val nonce = wire.copyOfRange(0, headerLen)
        val sealed = wire.copyOfRange(headerLen, wire.size)
        if (!replayGuard.accept(senderShortId, nonce)) return null
        return ChaChaPoly1305.open(aeadKey, nonce, aad, sealed)
    }

    companion object {
        /** Establishes a session for [myIdentity] with a paired peer's [PairingInfo]. */
        fun establish(myIdentity: DeviceIdentity, peer: PairingInfo): SecureSession =
            SecureSession(myIdentity.deriveSharedKey(peer.x25519PublicKey))

        /** For tests / advanced callers that already have a 32-byte key (e.g. from a KAT). */
        fun withRawKey(key: ByteArray, random: SecureRandom = SecureRandom()): SecureSession =
            SecureSession(key, random)
    }
}
