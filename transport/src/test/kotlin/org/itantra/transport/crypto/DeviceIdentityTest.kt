package org.itantra.transport.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun `X25519 agreement derives the same shared key on both sides`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()

        val keyFromA = a.deriveSharedKey(b.x25519PublicKey)
        val keyFromB = b.deriveSharedKey(a.x25519PublicKey)

        assertArrayEquals(keyFromA, keyFromB)
        assertEqualsInt(ChaChaPoly1305.KEY_BYTES, keyFromA.size)
    }

    @Test
    fun `different peers derive different shared keys`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val c = DeviceIdentity.generate()

        assertNotEquals(
            a.deriveSharedKey(b.x25519PublicKey).toList(),
            a.deriveSharedKey(c.x25519PublicKey).toList(),
        )
    }

    @Test
    fun `different info binds to different derived keys`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val k1 = a.deriveSharedKey(b.x25519PublicKey, info = "purpose-1".toByteArray())
        val k2 = a.deriveSharedKey(b.x25519PublicKey, info = "purpose-2".toByteArray())
        assertNotEquals(k1.toList(), k2.toList())
    }

    @Test
    fun `Ed25519 sign then verify accepts a genuine signature`() {
        val identity = DeviceIdentity.generate()
        val message = "evacuate now: flood warning".toByteArray()
        val sig = identity.sign(message)
        assertTrue(DeviceIdentity.verify(identity.ed25519PublicKey, message, sig))
    }

    @Test
    fun `Ed25519 verify rejects a signature from a different key`() {
        val signer = DeviceIdentity.generate()
        val other = DeviceIdentity.generate()
        val message = "evacuate now".toByteArray()
        val sig = signer.sign(message)
        assertFalse(DeviceIdentity.verify(other.ed25519PublicKey, message, sig))
    }

    @Test
    fun `Ed25519 verify rejects a tampered message`() {
        val identity = DeviceIdentity.generate()
        val sig = identity.sign("evacuate now".toByteArray())
        assertFalse(DeviceIdentity.verify(identity.ed25519PublicKey, "evacuate later".toByteArray(), sig))
    }

    @Test
    fun `Ed25519 verify never throws on garbage input`() {
        assertFalse(DeviceIdentity.verify(ByteArray(3), ByteArray(0), ByteArray(5)))
    }

    private fun assertEqualsInt(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected.toLong(), actual.toLong())
    }
}
