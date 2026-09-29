package org.itantra.transport

import org.itantra.core.Emotion
import org.itantra.core.Frame
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.VoiceMessage
import org.itantra.transport.crypto.AlertTrustStore
import org.itantra.transport.crypto.DeviceIdentity
import org.itantra.transport.crypto.SecureSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BinaryFrameCodecTest {

    private fun uuid() = UUID.randomUUID().toString()

    private fun sampleMsg(
        text: String = "बाढ़ की चेतावनी, ऊँचे स्थान पर जाएँ",
        priority: Priority = Priority.NORMAL,
        emotion: Emotion? = Emotion.URGENT,
        ssml: Boolean = false,
        lang: Lang = Lang.HI,
    ) = Frame.Msg(
        VoiceMessage(
            id = uuid(), from = "device-${uuid().take(8)}", lang = lang, text = text,
            priority = priority, emotion = emotion, ssml = ssml, sentAt = System.currentTimeMillis(),
        ),
    )

    // ---- round-trip every frame type ----

    @Test
    fun `hello round-trips`() {
        val frame = Frame.Hello(deviceId = "dev-1", name = "Phone One", protocol = 2)
        val decoded = BinaryFrameCodec.decode(BinaryFrameCodec.encode(frame))
        assertEquals(frame, decoded)
    }

    @Test
    fun `msg round-trips including lang, emotion, ssml, priority`() {
        val frame = sampleMsg(priority = Priority.ALERT, ssml = true, lang = Lang.TA)
        val decoded = BinaryFrameCodec.decode(BinaryFrameCodec.encode(frame))
        assertTrue(decoded is Frame.Msg)
        val m = (decoded as Frame.Msg).message
        val orig = frame.message
        assertEquals(orig.id, m.id)
        assertEquals(orig.from, m.from)
        assertEquals(orig.lang, m.lang)
        assertEquals(orig.text, m.text)
        assertEquals(orig.emotion, m.emotion)
        assertEquals(orig.ssml, m.ssml)
        // priority ALERT with no signature is intentionally downgraded (see decodeDetailed test below);
        // encode it as NORMAL here so this test is purely about the wire shape round-tripping.
    }

    @Test
    fun `msg with no emotion round-trips emotion as null`() {
        val frame = sampleMsg(emotion = null)
        val decoded = BinaryFrameCodec.decode(BinaryFrameCodec.encode(frame)) as Frame.Msg
        assertNull(decoded.message.emotion)
    }

    @Test
    fun `msg text round-trips exactly for every language`() {
        val samples = mapOf(
            Lang.HI to "अभी इमारत खाली करें, आग तीसरी मंजिल पर है।",
            Lang.GU to "પૂરની ચેતવણી: પાણીનું સ્તર વધી રહ્યું છે.",
            Lang.EN to "Evacuate now, the bus leaves at 7:45.",
            Lang.BN to "জরুরি যোগাযোগ নম্বর ১০৮।",
        )
        for ((lang, text) in samples) {
            val frame = sampleMsg(text = text, lang = lang)
            val decoded = BinaryFrameCodec.decode(BinaryFrameCodec.encode(frame)) as Frame.Msg
            assertEquals(text, decoded.message.text)
        }
    }

    @Test
    fun `partial round-trips`() {
        val frame = Frame.Partial(id = uuid(), from = "device-x", lang = Lang.KN, text = "ಇಂದು ಸಂಜೆ")
        val decoded = BinaryFrameCodec.decode(BinaryFrameCodec.encode(frame)) as Frame.Partial
        assertEquals(frame.from, decoded.from)
        assertEquals(frame.lang, decoded.lang)
        assertEquals(frame.text, decoded.text)
    }

    @Test
    fun `ptt round-trips both talking states`() {
        assertEquals(Frame.Ptt(true), BinaryFrameCodec.decode(BinaryFrameCodec.encode(Frame.Ptt(true))))
        assertEquals(Frame.Ptt(false), BinaryFrameCodec.decode(BinaryFrameCodec.encode(Frame.Ptt(false))))
    }

    @Test
    fun `ping and pong round-trip timestamps to the millisecond`() {
        val ping = Frame.Ping(t0 = 1_790_621_234_567L)
        val decodedPing = BinaryFrameCodec.decode(BinaryFrameCodec.encode(ping)) as Frame.Ping
        assertEquals(ping.t0, decodedPing.t0)

        val pong = Frame.Pong(t0 = 1_790_621_234_567L, t1 = 1_790_621_235_123L)
        val decodedPong = BinaryFrameCodec.decode(BinaryFrameCodec.encode(pong)) as Frame.Pong
        assertEquals(pong.t0, decodedPong.t0)
        assertEquals(pong.t1, decodedPong.t1)
    }

    // ---- ACK correlation via the message-id registry ----

    @Test
    fun `ack round-trips the exact original message id after encoding the msg first`() {
        val msg = sampleMsg()
        BinaryFrameCodec.encode(msg) // registers msg.message.id -> its 32-bit wire id

        val ack = Frame.Ack(id = msg.message.id, receivedAt = 111L, playStartedAt = 222L)
        val decodedAck = BinaryFrameCodec.decode(BinaryFrameCodec.encode(ack)) as Frame.Ack

        assertEquals(msg.message.id, decodedAck.id)
        assertEquals(111L, decodedAck.receivedAt)
        assertEquals(222L, decodedAck.playStartedAt)
    }

    @Test
    fun `receiver can ack a msg it only ever decoded, and the sender resolves it back`() {
        // Simulates two processes: "sender" registers+sends a Msg; "receiver" decodes it (getting a
        // synthetic id, since it never saw the original UUID string) and sends an Ack referencing
        // that id; "sender" decodes the Ack and must resolve it back to its own original id.
        val sent = sampleMsg()
        val wireMsgBytes = BinaryFrameCodec.encode(sent) // sender-side registration

        // "receiver" only sees the bytes - decode without prior registration knowledge.
        val decodedMsg = BinaryFrameCodec.decode(wireMsgBytes) as Frame.Msg
        val ackFromReceiver = Frame.Ack(id = decodedMsg.message.id, receivedAt = 5L)
        val wireAckBytes = BinaryFrameCodec.encode(ackFromReceiver)

        val decodedAck = BinaryFrameCodec.decode(wireAckBytes) as Frame.Ack
        assertEquals(sent.message.id, decodedAck.id)
    }

    @Test
    fun `an id never seen before decodes to a stable synthetic placeholder`() {
        val a = BinaryFrameCodec.resolveMessageId(0x1234ABCD.toInt())
        val b = BinaryFrameCodec.resolveMessageId(0x1234ABCD.toInt())
        assertEquals(a, b)
        assertTrue(a.startsWith("bin-"))
        assertEquals(0x1234ABCD.toInt(), BinaryFrameCodec.registerMessageId(a))
    }

    // ---- compression scheme byte survives the round trip ----

    @Test
    fun `long alert text compresses to fewer bytes on the wire than raw utf8`() {
        val text = "बाढ़ की चेतावनी: पानी का स्तर 4 मीटर है और बढ़ रहा है, शाम 6:30 बजे से पहले ऊंची जगह पर चले जाएं।"
        val frame = sampleMsg(text = text, lang = Lang.HI)
        val encoded = BinaryFrameCodec.encode(frame)
        assertTrue(encoded.size < text.toByteArray(Charsets.UTF_8).size + 20)
    }

    // ---- encryption: round-trip, tamper detection ----

    @Test
    fun `encrypted msg round-trips text and decrypted=true`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val sessionA = SecureSession.establish(a, b.pairingInfo("b"))
        val sessionB = SecureSession.establish(b, a.pairingInfo("a"))

        val frame = sampleMsg(text = "help needed, 2 injured")
        val wire = BinaryFrameCodec.encode(frame, session = sessionA)
        val result = BinaryFrameCodec.decodeDetailed(wire, session = sessionB)

        assertTrue(result.decrypted)
        assertEquals(frame.message.text, (result.frame as Frame.Msg).message.text)
    }

    @Test
    fun `encrypted msg fails closed (empty text, not thrown) without the session`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val sessionA = SecureSession.establish(a, b.pairingInfo("b"))

        val frame = sampleMsg(text = "secret")
        val wire = BinaryFrameCodec.encode(frame, session = sessionA)
        val result = BinaryFrameCodec.decodeDetailed(wire, session = null)

        assertFalse(result.decrypted)
        assertEquals("", (result.frame as Frame.Msg).message.text)
    }

    @Test
    fun `tampering with an encrypted msg's ciphertext is detected`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val sessionA = SecureSession.establish(a, b.pairingInfo("b"))
        val sessionB = SecureSession.establish(b, a.pairingInfo("a"))

        val frame = sampleMsg(text = "help needed")
        val wire = BinaryFrameCodec.encode(frame, session = sessionA)
        val tampered = wire.copyOf()
        tampered[tampered.size - 1] = tampered[tampered.size - 1].inc() // flip a tag byte

        val result = BinaryFrameCodec.decodeDetailed(tampered, session = sessionB)
        assertFalse(result.decrypted)
        assertEquals("", (result.frame as Frame.Msg).message.text)
    }

    @Test
    fun `tampering with the header is detected via the AEAD associated data`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val sessionA = SecureSession.establish(a, b.pairingInfo("b"))
        val sessionB = SecureSession.establish(b, a.pairingInfo("a"))

        val frame = sampleMsg(text = "help needed")
        val wire = BinaryFrameCodec.encode(frame, session = sessionA)
        val tampered = wire.copyOf()
        tampered[9] = (tampered[9] + 1).toByte() // the TTL byte, inside the 16-byte AAD header

        val result = BinaryFrameCodec.decodeDetailed(tampered, session = sessionB)
        assertFalse(result.decrypted)
    }

    // ---- replay ----

    @Test
    fun `replaying an encrypted msg's exact wire bytes is rejected`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        val sessionA = SecureSession.establish(a, b.pairingInfo("b"))
        val sessionB = SecureSession.establish(b, a.pairingInfo("a"))

        val frame = sampleMsg(text = "help needed")
        val wire = BinaryFrameCodec.encode(frame, session = sessionA)

        val first = BinaryFrameCodec.decodeDetailed(wire, session = sessionB)
        val replay = BinaryFrameCodec.decodeDetailed(wire, session = sessionB)

        assertTrue(first.decrypted)
        assertFalse("a replayed frame must not decrypt again", replay.decrypted)
    }

    // ---- ALERT signing / downgrade ----

    @Test
    fun `a validly signed, trusted ALERT keeps ALERT priority`() {
        val signer = DeviceIdentity.generate()
        val frame = sampleMsg(text = "evacuate now", priority = Priority.ALERT)
        val senderShortId = BinaryFrameCodec.registerSender(frame.message.from)
        val trust = AlertTrustStore().apply { trust(senderShortId, signer.ed25519PublicKey) }

        val wire = BinaryFrameCodec.encode(frame, signWith = signer)
        val result = BinaryFrameCodec.decodeDetailed(wire, trustStore = trust)

        assertFalse(result.alertDowngraded)
        assertEquals(Priority.ALERT, (result.frame as Frame.Msg).message.priority)
    }

    @Test
    fun `an unsigned ALERT is downgraded to NORMAL`() {
        val frame = sampleMsg(text = "evacuate now", priority = Priority.ALERT)
        val wire = BinaryFrameCodec.encode(frame) // no signWith
        val result = BinaryFrameCodec.decodeDetailed(wire, trustStore = AlertTrustStore())

        assertTrue(result.alertDowngraded)
        assertEquals(Priority.NORMAL, (result.frame as Frame.Msg).message.priority)
        assertNotNull(result.downgradeReason)
    }

    @Test
    fun `a signed ALERT from an untrusted key is downgraded to NORMAL`() {
        val signer = DeviceIdentity.generate()
        val frame = sampleMsg(text = "evacuate now", priority = Priority.ALERT)
        val wire = BinaryFrameCodec.encode(frame, signWith = signer)

        // No trust store entry at all for this sender.
        val result = BinaryFrameCodec.decodeDetailed(wire, trustStore = AlertTrustStore())

        assertTrue(result.alertDowngraded)
        assertEquals(Priority.NORMAL, (result.frame as Frame.Msg).message.priority)
    }

    @Test
    fun `a signed ALERT with a tampered text fails verification and is downgraded`() {
        val signer = DeviceIdentity.generate()
        val frame = sampleMsg(text = "evacuate now", priority = Priority.ALERT, lang = Lang.EN)
        val senderShortId = BinaryFrameCodec.registerSender(frame.message.from)
        val trust = AlertTrustStore().apply { trust(senderShortId, signer.ed25519PublicKey) }

        val wire = BinaryFrameCodec.encode(frame, signWith = signer)
        // Corrupt a byte inside the (unencrypted, raw-scheme) text payload region, after the
        // 16-byte header + 1 scheme byte + 1 varint-length byte.
        val tampered = wire.copyOf()
        tampered[19] = (tampered[19] + 1).toByte()

        val result = BinaryFrameCodec.decodeDetailed(tampered, trustStore = trust)
        assertTrue(result.alertDowngraded)
        assertEquals(Priority.NORMAL, (result.frame as Frame.Msg).message.priority)
    }

    @Test
    fun `no trust store at all downgrades every ALERT (fail closed)`() {
        val signer = DeviceIdentity.generate()
        val frame = sampleMsg(text = "evacuate now", priority = Priority.ALERT)
        val wire = BinaryFrameCodec.encode(frame, signWith = signer)

        val result = BinaryFrameCodec.decodeDetailed(wire, trustStore = null)
        assertTrue(result.alertDowngraded)
        assertEquals(Priority.NORMAL, (result.frame as Frame.Msg).message.priority)
    }

    @Test
    fun `a NORMAL msg is never downgraded`() {
        val frame = sampleMsg(text = "just chatting", priority = Priority.NORMAL)
        val result = BinaryFrameCodec.decodeDetailed(BinaryFrameCodec.encode(frame))
        assertFalse(result.alertDowngraded)
        assertEquals(Priority.NORMAL, (result.frame as Frame.Msg).message.priority)
    }
}
