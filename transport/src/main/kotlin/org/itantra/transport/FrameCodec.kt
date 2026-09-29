package org.itantra.transport

import kotlinx.serialization.json.Json
import org.itantra.core.Frame
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * JSON codec + wire framing for [Frame].
 *
 * TCP: 4-byte big-endian length prefix + UTF-8 JSON, rejecting anything over [MAX_FRAME_BYTES].
 * BLE: [chunkForBle] splits the encoded JSON into packets carrying a 3-byte `[seq, index, count]`
 * header; [BleReassembler] rebuilds them.
 */
object FrameCodec {
    const val MAX_FRAME_BYTES = 64 * 1024
    private const val BLE_HEADER_BYTES = 3

    val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(frame: Frame): ByteArray =
        json.encodeToString(Frame.serializer(), frame).toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): Frame =
        json.decodeFromString(Frame.serializer(), String(bytes, StandardCharsets.UTF_8))

    // ---- TCP framing: 4-byte big-endian length + UTF-8 JSON ----

    @Throws(IOException::class)
    fun writeTcpFrame(out: OutputStream, frame: Frame) = writeTcpFrameBytes(out, encode(frame))

    /** Same framing as [writeTcpFrame] but for pre-encoded bytes (e.g. from [BinaryFrameCodec] via [WireCodec]). */
    @Throws(IOException::class)
    fun writeTcpFrameBytes(out: OutputStream, bytes: ByteArray) {
        if (bytes.size > MAX_FRAME_BYTES) throw IOException("frame too large: ${bytes.size} bytes")
        val dout = out as? DataOutputStream ?: DataOutputStream(out)
        dout.writeInt(bytes.size)
        dout.write(bytes)
        dout.flush()
    }

    /** Blocks for one full frame. Returns null on clean EOF before a new frame starts. */
    @Throws(IOException::class)
    fun readTcpFrame(input: InputStream): Frame? = readTcpFrameBytes(input)?.let { decode(it) }

    /** Same framing as [readTcpFrame] but returns the raw payload undecoded (see [WireCodec.decode]). */
    @Throws(IOException::class)
    fun readTcpFrameBytes(input: InputStream): ByteArray? {
        val din = input as? DataInputStream ?: DataInputStream(input)
        val len = try {
            din.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (len < 0 || len > MAX_FRAME_BYTES) throw IOException("invalid/oversize frame length: $len")
        val buf = ByteArray(len)
        din.readFully(buf)
        return buf
    }

    // ---- BLE chunking: 3-byte header [seq, index, count] + up to (mtu-5) payload bytes ----

    /**
     * Splits one encoded [frame] into BLE packets, each `[seq, index, count]` (1 byte each,
     * wrapping at 256) followed by up to `mtu - 5` bytes of payload. [seq] identifies this frame
     * among interleaved chunks; callers should increment it per frame sent.
     */
    fun chunkForBle(frame: Frame, seq: Int, mtu: Int): List<ByteArray> = chunkBytesForBle(encode(frame), seq, mtu)

    /** Same chunking as [chunkForBle] but for pre-encoded bytes (e.g. from [BinaryFrameCodec] via [WireCodec]). */
    fun chunkBytesForBle(payload: ByteArray, seq: Int, mtu: Int): List<ByteArray> {
        val chunkSize = (mtu - 5).coerceAtLeast(1)
        val count = ((payload.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        require(count <= 255) { "frame too large to chunk for BLE mtu=$mtu (${payload.size} bytes)" }
        val seqByte = (seq and 0xFF).toByte()
        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, payload.size)
            val out = ByteArray(BLE_HEADER_BYTES + (end - start))
            out[0] = seqByte
            out[1] = index.toByte()
            out[2] = count.toByte()
            System.arraycopy(payload, start, out, BLE_HEADER_BYTES, end - start)
            out
        }
    }
}
