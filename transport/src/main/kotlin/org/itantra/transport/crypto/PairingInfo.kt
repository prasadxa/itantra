package org.itantra.transport.crypto

import java.util.Base64

/**
 * A device's shareable public identity, exchanged once during pairing. [x25519PublicKey] agrees a
 * per-link AEAD key ([DeviceIdentity.deriveSharedKey]); [ed25519PublicKey] authenticates ALERT
 * frames signed by that device (see [AlertTrustStore]).
 *
 * [toQrString]/[fromQrString] give a compact QR-friendly string; rendering/scanning the QR code
 * itself is the app/UI layer's job — this only provides the data + encode/decode functions.
 */
data class PairingInfo(
    val deviceId: String,
    val x25519PublicKey: ByteArray,
    val ed25519PublicKey: ByteArray,
) {
    init {
        require(x25519PublicKey.size == 32) { "x25519 public key must be 32 bytes" }
        require(ed25519PublicKey.size == 32) { "ed25519 public key must be 32 bytes" }
        require(deviceId.toByteArray(Charsets.UTF_8).size <= 255) { "deviceId too long to encode" }
    }

    /** `[1-byte deviceId length][deviceId utf8][32-byte X25519 pub][32-byte Ed25519 pub]`, base64url (unpadded). */
    fun toQrString(): String {
        val idBytes = deviceId.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(1 + idBytes.size + 32 + 32)
        buf[0] = idBytes.size.toByte()
        System.arraycopy(idBytes, 0, buf, 1, idBytes.size)
        System.arraycopy(x25519PublicKey, 0, buf, 1 + idBytes.size, 32)
        System.arraycopy(ed25519PublicKey, 0, buf, 1 + idBytes.size + 32, 32)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    override fun equals(other: Any?): Boolean = other is PairingInfo &&
        deviceId == other.deviceId &&
        x25519PublicKey.contentEquals(other.x25519PublicKey) &&
        ed25519PublicKey.contentEquals(other.ed25519PublicKey)

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + x25519PublicKey.contentHashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        return result
    }

    companion object {
        /** Parses a string produced by [toQrString]. Throws [IllegalArgumentException] if malformed. */
        fun fromQrString(s: String): PairingInfo {
            val buf = try {
                Base64.getUrlDecoder().decode(s)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("malformed pairing string (not base64url)", e)
            }
            require(buf.isNotEmpty()) { "empty pairing string" }
            val idLen = buf[0].toInt() and 0xFF
            require(buf.size == 1 + idLen + 32 + 32) { "malformed pairing string (wrong length)" }
            val deviceId = String(buf, 1, idLen, Charsets.UTF_8)
            val x = buf.copyOfRange(1 + idLen, 1 + idLen + 32)
            val ed = buf.copyOfRange(1 + idLen + 32, 1 + idLen + 64)
            return PairingInfo(deviceId, x, ed)
        }
    }
}
