package org.itantra.transport

import org.itantra.core.Frame
import org.itantra.core.Lang
import org.itantra.core.VoiceMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException

class FrameCodecTest {

    private fun sampleFrame(id: String = "m1") = Frame.Hello(deviceId = "device-$id", name = "Phone $id")

    @Test
    fun `encode then decode round-trips`() {
        val frame = sampleFrame()
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertEquals(frame, decoded)
    }

    @Test
    fun `class discriminator field is 'type'`() {
        val json = String(FrameCodec.encode(Frame.Ptt(talking = true)))
        assertTrue(json.contains("\"type\":\"ptt\""))
    }

    // ---- TCP framing ----

    @Test
    fun `tcp write then read round-trips a single frame`() {
        val out = ByteArrayOutputStream()
        val frame = sampleFrame("tcp")
        FrameCodec.writeTcpFrame(out, frame)

        val decoded = FrameCodec.readTcpFrame(ByteArrayInputStream(out.toByteArray()))
        assertEquals(frame, decoded)
    }

    @Test
    fun `tcp framing round-trips multiple sequential frames on one stream`() {
        val out = ByteArrayOutputStream()
        val frames = listOf(sampleFrame("a"), sampleFrame("b"), Frame.Ping(t0 = 42L))
        frames.forEach { FrameCodec.writeTcpFrame(out, it) }

        val input = ByteArrayInputStream(out.toByteArray())
        val decoded = frames.map { FrameCodec.readTcpFrame(input) }
        assertEquals(frames, decoded)
        assertNull(FrameCodec.readTcpFrame(input)) // clean EOF after the last frame
    }

    @Test
    fun `tcp read returns null on clean eof before a new frame`() {
        val decoded = FrameCodec.readTcpFrame(ByteArrayInputStream(ByteArray(0)))
        assertNull(decoded)
    }

    @Test(expected = EOFException::class)
    fun `tcp read throws on truncated frame body`() {
        val out = ByteArrayOutputStream()
        FrameCodec.writeTcpFrame(out, sampleFrame())
        val bytes = out.toByteArray()
        // Keep the 4-byte length prefix but drop the last byte of the body.
        val truncated = bytes.copyOf(bytes.size - 1)
        FrameCodec.readTcpFrame(ByteArrayInputStream(truncated))
    }

    @Test(expected = IOException::class)
    fun `tcp write rejects frames over 64KB`() {
        val hugeText = "x".repeat(FrameCodec.MAX_FRAME_BYTES)
        val huge = Frame.Msg(
            VoiceMessage(
                id = "big",
                from = "d",
                lang = Lang.EN,
                text = hugeText,
            ),
        )
        FrameCodec.writeTcpFrame(ByteArrayOutputStream(), huge)
    }

    // ---- BLE chunking / reassembly ----

    @Test
    fun `ble chunk then reassemble round-trips a small frame in one chunk`() {
        val frame = sampleFrame("ble")
        val chunks = FrameCodec.chunkForBle(frame, seq = 3, mtu = 517)
        assertEquals(1, chunks.size)

        val reassembler = BleReassembler()
        val result = chunks.map { reassembler.accept(it) }.last { it != null }
        assertEquals(frame, result)
    }

    @Test
    fun `ble chunking splits a large frame across multiple small-mtu packets`() {
        val longText = "hello ".repeat(200)
        val frame = Frame.Msg(
            VoiceMessage(
                id = "long",
                from = "d",
                lang = Lang.EN,
                text = longText,
            ),
        )
        val mtu = 23 // minimum BLE ATT MTU -> chunkSize = mtu - 5 = 18 bytes
        val chunks = FrameCodec.chunkForBle(frame, seq = 7, mtu = mtu)
        assertTrue(chunks.size > 1)
        chunks.forEach { assertTrue(it.size <= mtu - 5 + 3) }

        val reassembler = BleReassembler()
        var result: Frame? = null
        for (chunk in chunks) {
            val r = reassembler.accept(chunk)
            if (r != null) result = r
        }
        assertEquals(frame, result)
    }

    @Test
    fun `ble reassembler drops an incomplete frame when a new seq arrives`() {
        val first = sampleFrame("first")
        val second = sampleFrame("second")
        val firstChunks = FrameCodec.chunkForBle(first, seq = 1, mtu = 26) // force multiple chunks
        check(firstChunks.size > 1) { "test needs a multi-chunk frame" }
        val secondChunks = FrameCodec.chunkForBle(second, seq = 2, mtu = 517)

        val reassembler = BleReassembler()
        // Only the first chunk of `first` ever arrives - it's abandoned.
        assertNull(reassembler.accept(firstChunks[0]))

        var result: Frame? = null
        for (chunk in secondChunks) {
            val r = reassembler.accept(chunk)
            if (r != null) result = r
        }
        assertEquals(second, result)
    }

    @Test
    fun `ble chunk header encodes seq index count as three bytes`() {
        val chunks = FrameCodec.chunkForBle(sampleFrame(), seq = 200, mtu = 517)
        val header = chunks[0]
        assertEquals(200, header[0].toInt() and 0xFF)
        assertEquals(0, header[1].toInt() and 0xFF)
        assertEquals(chunks.size, header[2].toInt() and 0xFF)
    }
}
