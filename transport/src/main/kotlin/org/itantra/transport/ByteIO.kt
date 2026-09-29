package org.itantra.transport

import java.io.ByteArrayOutputStream

/** Little helpers shared by [BinaryFrameCodec]: unsigned LEB128 varints + a cursor over a byte buffer. */
internal object Varint {
    fun write(out: ByteArrayOutputStream, valueIn: Int) {
        var value = valueIn
        require(value >= 0) { "varint must be non-negative: $value" }
        while (true) {
            val b = value and 0x7F
            value = value ushr 7
            if (value == 0) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }
}

/** Sequential little-endian/big-endian reader over a fixed [ByteArray], throwing on underrun. */
internal class ByteReader(private val data: ByteArray) {
    var pos: Int = 0
        private set

    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (pos + n > data.size) throw IllegalArgumentException("truncated binary frame (need $n more bytes at $pos, have ${data.size})")
    }

    fun readU8(): Int {
        need(1)
        return data[pos++].toInt() and 0xFF
    }

    fun readU16(): Int {
        need(2)
        val v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    fun readI32(): Int {
        need(4)
        val v = ((data[pos].toInt() and 0xFF) shl 24) or
            ((data[pos + 1].toInt() and 0xFF) shl 16) or
            ((data[pos + 2].toInt() and 0xFF) shl 8) or
            (data[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    /** Reads a 4-byte second count + 2-byte millisecond count as one epoch-ms [Long]. */
    fun readTimestampMs(): Long {
        val seconds = readI32().toLong() and 0xFFFFFFFFL
        val millis = readU16()
        return seconds * 1000L + millis
    }

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = readU8()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            require(shift < 35) { "varint too long" }
        }
    }

    fun readBytes(n: Int): ByteArray {
        need(n)
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun readUtf8(len: Int): String = String(readBytes(len), Charsets.UTF_8)
}

/** Companion writer; mirrors [ByteReader]'s formats. */
internal class ByteWriter {
    val out = ByteArrayOutputStream()

    fun writeU8(v: Int): ByteWriter {
        out.write(v and 0xFF)
        return this
    }

    fun writeU16(v: Int): ByteWriter {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
        return this
    }

    fun writeI32(v: Int): ByteWriter {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
        return this
    }

    /** Writes an epoch-ms [Long] as a 4-byte second count + 2-byte millisecond count. */
    fun writeTimestampMs(epochMs: Long): ByteWriter {
        val seconds = (epochMs / 1000L)
        val millis = (epochMs % 1000L).toInt()
        writeI32(seconds.toInt())
        writeU16(millis)
        return this
    }

    fun writeVarInt(v: Int): ByteWriter {
        Varint.write(out, v)
        return this
    }

    fun writeBytes(b: ByteArray): ByteWriter {
        out.write(b)
        return this
    }

    fun writeUtf8(s: String): ByteWriter = writeBytes(s.toByteArray(Charsets.UTF_8))

    fun toByteArray(): ByteArray = out.toByteArray()
}
