package org.itantra.transport.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.SecureRandom

/**
 * This device's long-term key pair: X25519 for per-link key agreement, Ed25519 for signing ALERT
 * frames. Generate once per install and persist the raw bytes (persistence/QR UI is the app
 * layer's job — see [PairingInfo] for the exchange format); everything here is pure/offline.
 *
 * Uses Bouncy Castle's lightweight (non-JCE) `org.bouncycastle.math.ec.rfc7748.X25519` /
 * `rfc8032.Ed25519` static APIs directly rather than registering a `Provider`, so behaviour is
 * identical on the JVM (unit tests) and on-device at minSdk 26 — Android's own `Conscrypt`
 * `XDH`/`Ed25519` `Provider` support only arrived in API 31, too late for this app's floor.
 */
class DeviceIdentity private constructor(
    private val x25519PrivateKey: ByteArray,
    val x25519PublicKey: ByteArray,
    private val ed25519PrivateKey: ByteArray,
    val ed25519PublicKey: ByteArray,
) {
    /** This device's shareable identity for pairing (QR code / NFC / paste — see [PairingInfo]). */
    fun pairingInfo(deviceId: String): PairingInfo =
        PairingInfo(deviceId, x25519PublicKey.copyOf(), ed25519PublicKey.copyOf())

    /**
     * X25519 ECDH with [peerX25519PublicKey] then HKDF-SHA256 into a 32-byte AEAD key. [info]
     * binds the derived key to its purpose so unrelated sub-channels never reuse one key.
     */
    fun deriveSharedKey(peerX25519PublicKey: ByteArray, info: ByteArray = DEFAULT_AEAD_INFO): ByteArray {
        require(peerX25519PublicKey.size == X25519.POINT_SIZE) { "peer X25519 key must be ${X25519.POINT_SIZE} bytes" }
        val shared = ByteArray(X25519.POINT_SIZE)
        val ok = X25519.calculateAgreement(x25519PrivateKey, 0, peerX25519PublicKey, 0, shared, 0)
        require(ok) { "X25519 agreement failed (invalid or low-order peer public key)" }
        return try {
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(shared, null, info))
            val out = ByteArray(ChaChaPoly1305.KEY_BYTES)
            hkdf.generateBytes(out, 0, out.size)
            out
        } finally {
            java.util.Arrays.fill(shared, 0)
        }
    }

    /** Signs [message] with this device's Ed25519 key (64-byte signature). Used for ALERT frames. */
    fun sign(message: ByteArray): ByteArray {
        val sig = ByteArray(Ed25519.SIGNATURE_SIZE)
        Ed25519.sign(ed25519PrivateKey, 0, ed25519PublicKey, 0, message, 0, message.size, sig, 0)
        return sig
    }

    companion object {
        private val DEFAULT_AEAD_INFO = "itantra-v1-aead".toByteArray(Charsets.UTF_8)

        fun generate(random: SecureRandom = SecureRandom()): DeviceIdentity {
            val xPriv = ByteArray(X25519.SCALAR_SIZE)
            X25519.generatePrivateKey(random, xPriv)
            val xPub = ByteArray(X25519.POINT_SIZE)
            X25519.generatePublicKey(xPriv, 0, xPub, 0)

            val edPriv = ByteArray(Ed25519.SECRET_KEY_SIZE)
            Ed25519.generatePrivateKey(random, edPriv)
            val edPub = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
            Ed25519.generatePublicKey(edPriv, 0, edPub, 0)

            return DeviceIdentity(xPriv, xPub, edPriv, edPub)
        }

        /** Verifies an Ed25519 [signature] over [message] by [ed25519PublicKey]. Never throws. */
        fun verify(ed25519PublicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
            if (signature.size != Ed25519.SIGNATURE_SIZE || ed25519PublicKey.size != Ed25519.PUBLIC_KEY_SIZE) return false
            return try {
                Ed25519.verify(signature, 0, ed25519PublicKey, 0, message, 0, message.size)
            } catch (e: Exception) {
                false
            }
        }
    }
}
