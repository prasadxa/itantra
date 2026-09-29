package org.itantra.transport.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecureSessionTest {

    @Test
    fun `establish derives a usable session for both paired devices`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val sessionA = SecureSession.establish(a, b.pairingInfo("device-b"))
        val sessionB = SecureSession.establish(b, a.pairingInfo("device-a"))

        val aad = "header".toByteArray()
        val plaintext = "help needed at sector 4".toByteArray()
        val wire = sessionA.encrypt(aad, plaintext)
        val opened = sessionB.decrypt(senderShortId = 1, aad = aad, wire = wire)

        assertArrayEquals(plaintext, opened)
    }

    @Test
    fun `a replayed wire message is rejected the second time`() {
        val key = ByteArray(32) { it.toByte() }
        val sender = SecureSession.withRawKey(key)
        val receiver = SecureSession.withRawKey(key)

        val aad = "header".toByteArray()
        val wire = sender.encrypt(aad, "evacuate now".toByteArray())

        val first = receiver.decrypt(senderShortId = 7, aad = aad, wire = wire)
        val replay = receiver.decrypt(senderShortId = 7, aad = aad, wire = wire)

        assertArrayEquals("evacuate now".toByteArray(), first)
        assertNull("a replayed nonce must be rejected", replay)
    }

    @Test
    fun `the same nonce from two different senders is tracked independently`() {
        val key = ByteArray(32) { it.toByte() }
        val sender = SecureSession.withRawKey(key)
        val receiver = SecureSession.withRawKey(key)
        val aad = "header".toByteArray()
        val wire = sender.encrypt(aad, "hi".toByteArray())

        // Same wire bytes "arriving" from two different logical senders is not a replay of either.
        val fromSenderA = receiver.decrypt(senderShortId = 1, aad = aad, wire = wire)
        val fromSenderB = receiver.decrypt(senderShortId = 2, aad = aad, wire = wire)

        assertArrayEquals("hi".toByteArray(), fromSenderA)
        assertArrayEquals("hi".toByteArray(), fromSenderB)
    }

    @Test
    fun `decrypt rejects a tampered wire message`() {
        val key = ByteArray(32) { it.toByte() }
        val sender = SecureSession.withRawKey(key)
        val receiver = SecureSession.withRawKey(key)
        val aad = "header".toByteArray()
        val wire = sender.encrypt(aad, "hi".toByteArray()).copyOf()
        wire[wire.size - 1] = wire[wire.size - 1].inc()

        assertNull(receiver.decrypt(senderShortId = 1, aad = aad, wire = wire))
    }

    @Test
    fun `decrypt rejects too-short input instead of throwing`() {
        val receiver = SecureSession.withRawKey(ByteArray(32))
        assertNull(receiver.decrypt(senderShortId = 1, aad = ByteArray(0), wire = ByteArray(4)))
    }
}
