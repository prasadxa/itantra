package org.itantra.transport.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingInfoTest {

    @Test
    fun `toQrString then fromQrString round-trips`() {
        val identity = DeviceIdentity.generate()
        val info = identity.pairingInfo("phone-A-1234")

        val qr = info.toQrString()
        val decoded = PairingInfo.fromQrString(qr)

        assertEquals(info, decoded)
    }

    @Test
    fun `qr string is url-safe (no plus, slash or padding)`() {
        val identity = DeviceIdentity.generate()
        val qr = identity.pairingInfo("device-x").toQrString()
        assertTrue(qr.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `fromQrString rejects malformed input`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingInfo.fromQrString("not-valid-base64url-pairing-data!!")
        }
    }

    @Test
    fun `different device ids are not equal`() {
        val identity = DeviceIdentity.generate()
        val a = identity.pairingInfo("device-a")
        val b = identity.pairingInfo("device-b")
        assertTrue(a != b)
    }
}
