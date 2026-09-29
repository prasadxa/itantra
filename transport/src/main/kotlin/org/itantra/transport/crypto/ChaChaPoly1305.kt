package org.itantra.transport.crypto

import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.Arrays as BcArrays
import java.security.SecureRandom

/**
 * RFC 8439 ChaCha20-Poly1305 AEAD with a **truncated 8-byte wire tag** (the compact binary frame
 * spec's "8-byte truncated tag in binary mode"), composed from Bouncy Castle's tested low-level
 * primitives ([ChaCha7539Engine] stream cipher, [Poly1305] one-time MAC).
 *
 * Bouncy Castle's own [org.bouncycastle.crypto.modes.ChaCha20Poly1305] AEAD engine hard-requires a
 * 128-bit tag (`init()` throws for any other `macSizeInBits`), so it can't be used directly for a
 * truncated tag. This reimplements the exact RFC 8439 §2.8 recipe instead: the first 64-byte
 * ChaCha20 keystream block (counter 0) is the one-time Poly1305 key, data is enciphered from
 * counter 1, and the MAC covers `AAD ‖ pad ‖ ciphertext ‖ pad ‖ len(AAD) ‖ len(ciphertext)`.
 * [ChaChaPoly1305Test] cross-checks the *full* 16-byte tag byte-for-byte against Bouncy Castle's
 * own engine so this composition is verified against a trusted, spec-conformant implementation
 * rather than a hand-transcribed test vector; the 8-byte wire tag is simply its first 8 bytes.
 *
 * Trade-off (documented in transport/README.md): truncating the tag to 8 bytes reduces forgery
 * resistance from 2^-128 to 2^-64 per attempt. Given per-packet random nonces and short-lived
 * sessions this is judged acceptable for the wire-size budget; callers wanting full-strength
 * authentication should keep messages small enough that the JSON path's larger MAC is affordable,
 * or extend [TAG_BYTES] back to 16.
 */
object ChaChaPoly1305 {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val FULL_TAG_BYTES = 16
    const val TAG_BYTES = 8

    fun randomNonce(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(NONCE_BYTES).also { random.nextBytes(it) }

    /** Returns `ciphertext ‖ tag` ([TAG_BYTES] bytes), i.e. [plaintext].size + [TAG_BYTES] bytes total. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        requireKeyNonce(key, nonce)
        val ciphertext = xorKeystream(key, nonce, plaintext)
        val tag = poly1305Tag(key, nonce, aad, ciphertext)
        val out = ByteArray(ciphertext.size + TAG_BYTES)
        System.arraycopy(ciphertext, 0, out, 0, ciphertext.size)
        System.arraycopy(tag, 0, out, ciphertext.size, TAG_BYTES)
        return out
    }

    /** Verifies+decrypts `ciphertext ‖ tag` from [sealed]. Returns null on any tamper/forgery/short input. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        requireKeyNonce(key, nonce)
        if (sealed.size < TAG_BYTES) return null
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_BYTES)
        val wireTag = sealed.copyOfRange(sealed.size - TAG_BYTES, sealed.size)
        val expected = poly1305Tag(key, nonce, aad, ciphertext)
        if (!BcArrays.constantTimeAreEqual(wireTag, expected.copyOfRange(0, TAG_BYTES))) return null
        return xorKeystream(key, nonce, ciphertext)
    }

    private fun requireKeyNonce(key: ByteArray, nonce: ByteArray) {
        require(key.size == KEY_BYTES) { "key must be $KEY_BYTES bytes" }
        require(nonce.size == NONCE_BYTES) { "nonce must be $NONCE_BYTES bytes" }
    }

    private fun chacha(key: ByteArray, nonce: ByteArray): ChaCha7539Engine =
        ChaCha7539Engine().apply { init(true, ParametersWithIV(KeyParameter(key), nonce)) }

    /** XORs [data] with the ChaCha20 keystream starting at block counter 1 (block 0 is reserved for the Poly1305 key, see [polyKey]). Symmetric: same call encrypts or decrypts. */
    internal fun xorKeystream(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        val engine = chacha(key, nonce)
        val discard = ByteArray(64)
        engine.processBytes(ByteArray(64), 0, 64, discard, 0) // consume block 0, advances counter to 1
        val out = ByteArray(data.size)
        engine.processBytes(data, 0, data.size, out, 0)
        return out
    }

    /** The one-time Poly1305 key: the first 32 bytes of the ChaCha20 keystream's block 0. */
    private fun polyKey(key: ByteArray, nonce: ByteArray): ByteArray {
        val engine = chacha(key, nonce)
        val block0 = ByteArray(64)
        engine.processBytes(ByteArray(64), 0, 64, block0, 0)
        return block0.copyOfRange(0, 32)
    }

    /** Full 16-byte Poly1305 tag over `aad`/`ciphertext` per RFC 8439 §2.8. */
    internal fun poly1305Tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(polyKey(key, nonce)))
        updatePadded(mac, aad)
        updatePadded(mac, ciphertext)
        val lengths = ByteArray(16)
        writeLongLE(lengths, 0, aad.size.toLong())
        writeLongLE(lengths, 8, ciphertext.size.toLong())
        mac.update(lengths, 0, 16)
        val tag = ByteArray(FULL_TAG_BYTES)
        mac.doFinal(tag, 0)
        return tag
    }

    private fun updatePadded(mac: Poly1305, data: ByteArray) {
        mac.update(data, 0, data.size)
        val rem = data.size % 16
        if (rem != 0) mac.update(ByteArray(16 - rem), 0, 16 - rem)
    }

    private fun writeLongLE(dst: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) dst[off + i] = (value ushr (8 * i)).toByte()
    }
}
