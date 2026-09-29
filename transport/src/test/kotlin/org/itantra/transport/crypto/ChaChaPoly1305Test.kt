package org.itantra.transport.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305 as BcChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class ChaChaPoly1305Test {

    private fun key() = ByteArray(32) { (it * 7 + 1).toByte() }
    private fun nonce() = ByteArray(12) { (it * 3 + 2).toByte() }

    /**
     * Cross-checks our manual RFC 8439 composition's *full* 16-byte tag/ciphertext against Bouncy
     * Castle's own, trusted [BcChaCha20Poly1305] engine (which can't itself produce a truncated
     * tag - see ChaChaPoly1305.kt's class doc). This validates the composition against a
     * spec-conformant implementation rather than a hand-transcribed test vector.
     */
    @Test
    fun `full tag and ciphertext match Bouncy Castle's own ChaCha20Poly1305 engine`() {
        val key = key()
        val nonce = nonce()
        val aad = "itantra-header-16by".toByteArray()
        val plaintext = "बाढ़ का पानी बढ़ रहा है, ऊँचे स्थान पर जाएँ".toByteArray(Charsets.UTF_8)

        val bc = BcChaCha20Poly1305()
        bc.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val bcOut = ByteArray(bc.getOutputSize(plaintext.size))
        var len = bc.processBytes(plaintext, 0, plaintext.size, bcOut, 0)
        len += bc.doFinal(bcOut, len)
        val bcCiphertext = bcOut.copyOfRange(0, len - 16)
        val bcFullTag = bcOut.copyOfRange(len - 16, len)

        val myCiphertext = ChaChaPoly1305.xorKeystream(key, nonce, plaintext)
        val myFullTag = ChaChaPoly1305.poly1305Tag(key, nonce, aad, myCiphertext)

        assertArrayEquals(bcCiphertext, myCiphertext)
        assertArrayEquals(bcFullTag, myFullTag)

        val sealed = ChaChaPoly1305.seal(key, nonce, aad, plaintext)
        val wireTag = sealed.copyOfRange(sealed.size - ChaChaPoly1305.TAG_BYTES, sealed.size)
        assertArrayEquals(bcFullTag.copyOfRange(0, ChaChaPoly1305.TAG_BYTES), wireTag)
    }

    @Test
    fun `seal then open round-trips plaintext`() {
        val key = key()
        val nonce = ChaChaPoly1305.randomNonce(SecureRandom())
        val aad = "aad".toByteArray()
        val plaintext = "evacuate now, water level rising".toByteArray()

        val sealed = ChaChaPoly1305.seal(key, nonce, aad, plaintext)
        assertEqualsInt(plaintext.size + ChaChaPoly1305.TAG_BYTES, sealed.size)

        val opened = ChaChaPoly1305.open(key, nonce, aad, sealed)
        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `open rejects a tampered ciphertext byte`() {
        val key = key()
        val nonce = nonce()
        val aad = "aad".toByteArray()
        val sealed = ChaChaPoly1305.seal(key, nonce, aad, "hello world".toByteArray()).copyOf()
        sealed[0] = sealed[0].inc()
        assertNull(ChaChaPoly1305.open(key, nonce, aad, sealed))
    }

    @Test
    fun `open rejects a tampered tag byte`() {
        val key = key()
        val nonce = nonce()
        val aad = "aad".toByteArray()
        val sealed = ChaChaPoly1305.seal(key, nonce, aad, "hello world".toByteArray()).copyOf()
        sealed[sealed.size - 1] = sealed[sealed.size - 1].inc()
        assertNull(ChaChaPoly1305.open(key, nonce, aad, sealed))
    }

    @Test
    fun `open rejects tampered associated data`() {
        val key = key()
        val nonce = nonce()
        val sealed = ChaChaPoly1305.seal(key, nonce, "aad-1".toByteArray(), "hello world".toByteArray())
        assertNull(ChaChaPoly1305.open(key, nonce, "aad-2".toByteArray(), sealed))
    }

    @Test
    fun `open rejects the wrong key`() {
        val nonce = nonce()
        val aad = "aad".toByteArray()
        val sealed = ChaChaPoly1305.seal(key(), nonce, aad, "hello world".toByteArray())
        val wrongKey = ByteArray(32) { 0 }
        assertNull(ChaChaPoly1305.open(wrongKey, nonce, aad, sealed))
    }

    @Test
    fun `different nonces produce different ciphertext for the same plaintext`() {
        val key = key()
        val aad = ByteArray(0)
        val plaintext = "same message".toByteArray()
        val a = ChaChaPoly1305.seal(key, ByteArray(12) { 1 }, aad, plaintext)
        val b = ChaChaPoly1305.seal(key, ByteArray(12) { 2 }, aad, plaintext)
        assertTrue(!a.contentEquals(b))
    }

    private fun assertEqualsInt(expected: Int, actual: Int) {
        assertTrue("expected $expected but was $actual", expected == actual)
        assertNotNull(actual)
    }
}
