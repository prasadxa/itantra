package org.itantra.transport

import org.itantra.core.Emotion
import org.itantra.core.Frame
import org.itantra.core.Lang
import org.itantra.core.Priority
import org.itantra.core.VoiceMessage
import org.itantra.transport.crypto.AlertTrustStore
import org.itantra.transport.crypto.DeviceIdentity
import org.itantra.transport.crypto.SecureSession
import java.nio.charset.StandardCharsets

/**
 * Compact binary wire layout for every [Frame] type (see docs/sih-idea-pack.html "Planned compact
 * binary frame" and docs/design.md), for slow/low-bitrate links (BLE, a future LoRa bridge) where
 * the ~150-300 byte JSON [FrameCodec] frame is too big. [FrameCodec] (JSON) stays the format used
 * for debugging and `tools/peer_sim.py`; [WireCodec] picks between the two per negotiated peer
 * capability and auto-detects which one a received frame uses.
 *
 * `Msg` layout (the one the compact-frame table specifies) — 16-byte fixed header, then a
 * variable body:
 * ```
 * byte 0      version(4b) | type(4b)
 * byte 1      flags: 0x01 ALERT priority, 0x02 SSML, 0x04 ENCRYPTED, 0x08 fragment (reserved),
 *             0x10 emotion nibble present, 0x40 Ed25519 signature appended
 * bytes 2-5   message id (int32) — stable hash of VoiceMessage.id, see [registerMessageId]
 * bytes 6-7   sender short id (uint16) — stable hash of VoiceMessage.from, see [registerSender]
 * byte 8      lang (high nibble) | emotion (low nibble, 0 if not present)
 * byte 9      hop / TTL
 * bytes 10-15 timestamp: 4-byte epoch seconds + 2-byte milliseconds (VoiceMessage.sentAt)
 * byte 16     text compression scheme (see [TextDictionaryCodec])
 * varint      payload length
 * ...         payload: compressed/raw text bytes, or (if ENCRYPTED) 12-byte nonce + ciphertext +
 *             8-byte truncated AEAD tag (see [org.itantra.transport.crypto.ChaChaPoly1305]) wrapping
 *             those same compressed/raw text bytes — the 16-byte header above is the AEAD associated
 *             data, so tampering with it (message id, sender, priority flag, ...) is also detected.
 * [64 bytes]  Ed25519 signature over header ‖ plaintext text, present iff the 0x40 flag is set —
 *             required for an ALERT to survive decode as ALERT, see [decodeDetailed].
 * ```
 * `Hello`/`Partial`/`Ack`/`Ptt`/`Ping`/`Pong` use a smaller subset of the same envelope; see the
 * corresponding `encode*`/`decode*` functions below.
 *
 * Message/sender ids are 32/16-bit hashes, not the original strings, so a receiver that decodes a
 * `Msg` for the first time can't recover [VoiceMessage.id]/[VoiceMessage.from] verbatim — it gets a
 * synthetic `bin-xxxxxxxx`/`peer-xxxx` placeholder instead, registered so that a *reply* (e.g. the
 * `Ack` this process later sends back) round-trips to the exact same wire id. The registries are
 * process-lifetime, bounded caches, not a distributed lookup.
 */
object BinaryFrameCodec {
    const val VERSION = 1

    private const val TYPE_HELLO = 0
    private const val TYPE_MSG = 1
    private const val TYPE_PARTIAL = 2
    private const val TYPE_ACK = 3
    private const val TYPE_PTT = 4
    private const val TYPE_PING = 5
    private const val TYPE_PONG = 6

    const val FLAG_ALERT = 0x01
    const val FLAG_SSML = 0x02
    const val FLAG_ENCRYPTED = 0x04
    const val FLAG_FRAGMENT = 0x08
    const val FLAG_EMOTION_PRESENT = 0x10
    const val FLAG_TALKING = 0x20
    const val FLAG_SIGNED = 0x40

    const val DEFAULT_TTL = 8
    private const val HEADER_LEN_MSG = 16
    private const val SIGNATURE_BYTES = 64

    /** Result of [decodeDetailed]: the app-visible [Frame] plus wire-security metadata that has no
     * home on [Frame]/[VoiceMessage] (their shapes are shared with other modules and aren't changed
     * here) — e.g. an ALERT that got downgraded to NORMAL for lacking a valid signature. */
    data class BinaryDecodeResult(
        val frame: Frame,
        val alertDowngraded: Boolean = false,
        val downgradeReason: String? = null,
        val decrypted: Boolean = false,
    )

    // ---- id registries (see class doc) ----

    private const val SYNTH_ID_PREFIX = "bin-"
    private const val SYNTH_SENDER_PREFIX = "peer-"
    private const val REGISTRY_CAPACITY = 4096

    private val idLock = Any()
    private val senderLock = Any()
    private val idToUuid = BoundedMap<Int, String>(REGISTRY_CAPACITY)
    private val uuidToId = BoundedMap<String, Int>(REGISTRY_CAPACITY)
    private val shortIdToSender = BoundedMap<Int, String>(REGISTRY_CAPACITY)
    private val senderToShortId = BoundedMap<String, Int>(REGISTRY_CAPACITY)

    /** Stable 32-bit id for [uuid] (a [VoiceMessage.id]), cached both ways for [Frame.Ack] correlation. */
    fun registerMessageId(uuid: String): Int = synchronized(idLock) {
        uuidToId[uuid]?.let { return it }
        val id = decodeSynthetic(uuid, SYNTH_ID_PREFIX, 8)?.toInt() ?: fnv1a32(uuid)
        uuidToId[uuid] = id
        if (idToUuid[id] == null) idToUuid[id] = uuid
        id
    }

    /** Reverses [registerMessageId]: the original string if known on this process, else a synthetic placeholder. */
    fun resolveMessageId(id: Int): String = synchronized(idLock) {
        idToUuid[id]?.let { return it }
        val synth = SYNTH_ID_PREFIX + "%08x".format(id)
        idToUuid[id] = synth
        if (uuidToId[synth] == null) uuidToId[synth] = id
        synth
    }

    /** Stable 16-bit id for [deviceId] (a [VoiceMessage.from]/`Frame.Hello.deviceId`). */
    fun registerSender(deviceId: String): Int = synchronized(senderLock) {
        senderToShortId[deviceId]?.let { return it }
        val id = decodeSynthetic(deviceId, SYNTH_SENDER_PREFIX, 4)?.toInt()
            ?: run { val h = fnv1a32(deviceId); (h xor (h ushr 16)) and 0xFFFF }
        senderToShortId[deviceId] = id
        if (shortIdToSender[id] == null) shortIdToSender[id] = deviceId
        id
    }

    /** Reverses [registerSender]. Public so [AlertTrustStore] callers can key trust by the same short id. */
    fun resolveSender(id: Int): String = synchronized(senderLock) {
        shortIdToSender[id]?.let { return it }
        val synth = SYNTH_SENDER_PREFIX + "%04x".format(id)
        shortIdToSender[id] = synth
        if (senderToShortId[synth] == null) senderToShortId[synth] = id
        synth
    }

    private fun decodeSynthetic(s: String, prefix: String, hexLen: Int): Long? {
        if (!s.startsWith(prefix) || s.length != prefix.length + hexLen) return null
        return s.substring(prefix.length).toLongOrNull(16)
    }

    private fun fnv1a32(s: String): Int {
        var hash = -0x7ee3623b // 0x811c9dc5, the FNV-1a 32-bit offset basis
        for (b in s.toByteArray(StandardCharsets.UTF_8)) {
            hash = hash xor (b.toInt() and 0xFF)
            hash *= 0x01000193 // FNV prime
        }
        return hash
    }

    // ---- encode ----

    /**
     * @param session when non-null, the `Msg` payload is encrypted (see class doc); null = plaintext
     *   binary, matching "encryption optional, off until paired".
     * @param signWith when non-null and the message is an ALERT, appends an Ed25519 signature so
     *   the receiver (given a matching [AlertTrustStore] entry) can keep it as ALERT rather than
     *   downgrading it — see [decodeDetailed].
     */
    fun encode(
        frame: Frame,
        ttl: Int = DEFAULT_TTL,
        session: SecureSession? = null,
        signWith: DeviceIdentity? = null,
    ): ByteArray {
        val w = ByteWriter()
        when (frame) {
            is Frame.Hello -> {
                w.writeU8(typeByte(TYPE_HELLO)).writeU8(0)
                writeString(w, frame.deviceId)
                writeString(w, frame.name)
                w.writeVarInt(frame.protocol)
            }
            is Frame.Msg -> encodeMsg(w, frame, ttl, session, signWith)
            is Frame.Partial -> encodePartial(w, frame)
            is Frame.Ack -> {
                w.writeU8(typeByte(TYPE_ACK)).writeU8(0)
                w.writeI32(registerMessageId(frame.id))
                w.writeTimestampMs(frame.receivedAt)
                w.writeTimestampMs(frame.playStartedAt)
            }
            is Frame.Ptt -> {
                w.writeU8(typeByte(TYPE_PTT))
                w.writeU8(if (frame.talking) FLAG_TALKING else 0)
            }
            is Frame.Ping -> {
                w.writeU8(typeByte(TYPE_PING)).writeU8(0)
                w.writeTimestampMs(frame.t0)
            }
            is Frame.Pong -> {
                w.writeU8(typeByte(TYPE_PONG)).writeU8(0)
                w.writeTimestampMs(frame.t0)
                w.writeTimestampMs(frame.t1)
            }
        }
        return w.toByteArray()
    }

    private fun typeByte(type: Int) = (VERSION shl 4) or type

    private fun encodeMsg(w: ByteWriter, frame: Frame.Msg, ttl: Int, session: SecureSession?, signWith: DeviceIdentity?) {
        val m = frame.message
        var flags = 0
        if (m.priority == Priority.ALERT) flags = flags or FLAG_ALERT
        if (m.ssml) flags = flags or FLAG_SSML
        if (m.emotion != null) flags = flags or FLAG_EMOTION_PRESENT
        val encrypt = session != null
        if (encrypt) flags = flags or FLAG_ENCRYPTED
        val sign = signWith != null && m.priority == Priority.ALERT
        if (sign) flags = flags or FLAG_SIGNED

        val msgId = registerMessageId(m.id)
        val senderShortId = registerSender(m.from)
        val langEmotion = (m.lang.ordinal shl 4) or (m.emotion?.ordinal ?: 0)

        val header = ByteWriter()
        header.writeU8(typeByte(TYPE_MSG))
        header.writeU8(flags)
        header.writeI32(msgId)
        header.writeU16(senderShortId)
        header.writeU8(langEmotion)
        header.writeU8(ttl.coerceIn(0, 255))
        header.writeTimestampMs(m.sentAt)
        val headerBytes = header.toByteArray()
        check(headerBytes.size == HEADER_LEN_MSG)
        w.writeBytes(headerBytes)

        val (scheme, compressed) = TextDictionaryCodec.compress(m.text, m.lang)
        w.writeU8(scheme)

        val payload = if (encrypt) session!!.encrypt(headerBytes, compressed) else compressed
        w.writeVarInt(payload.size)
        w.writeBytes(payload)

        if (sign) {
            val textBytes = m.text.toByteArray(StandardCharsets.UTF_8)
            val sigBuf = headerBytes + textBytes
            w.writeBytes(signWith!!.sign(sigBuf))
        }
    }

    private fun encodePartial(w: ByteWriter, frame: Frame.Partial) {
        w.writeU8(typeByte(TYPE_PARTIAL)).writeU8(0)
        w.writeI32(registerMessageId(frame.id))
        w.writeU16(registerSender(frame.from))
        w.writeU8(frame.lang.ordinal shl 4)
        val (scheme, compressed) = TextDictionaryCodec.compress(frame.text, frame.lang)
        w.writeU8(scheme)
        w.writeVarInt(compressed.size)
        w.writeBytes(compressed)
    }

    private fun writeString(w: ByteWriter, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        w.writeVarInt(bytes.size)
        w.writeBytes(bytes)
    }

    // ---- decode ----

    /** Convenience wrapper over [decodeDetailed] for callers that don't need the security metadata. */
    fun decode(bytes: ByteArray, session: SecureSession? = null, trustStore: AlertTrustStore? = null): Frame =
        decodeDetailed(bytes, session, trustStore).frame

    fun decodeDetailed(bytes: ByteArray, session: SecureSession? = null, trustStore: AlertTrustStore? = null): BinaryDecodeResult {
        val r = ByteReader(bytes)
        val versionType = r.readU8()
        val version = (versionType ushr 4) and 0xF
        val type = versionType and 0xF
        require(version == VERSION) { "unsupported binary frame version: $version" }
        val flags = r.readU8()
        return when (type) {
            TYPE_HELLO -> BinaryDecodeResult(decodeHello(r))
            TYPE_MSG -> decodeMsg(bytes, r, flags, session, trustStore)
            TYPE_PARTIAL -> BinaryDecodeResult(decodePartial(r))
            TYPE_ACK -> BinaryDecodeResult(decodeAck(r))
            TYPE_PTT -> BinaryDecodeResult(Frame.Ptt(talking = flags and FLAG_TALKING != 0))
            TYPE_PING -> BinaryDecodeResult(Frame.Ping(t0 = r.readTimestampMs()))
            TYPE_PONG -> BinaryDecodeResult(Frame.Pong(t0 = r.readTimestampMs(), t1 = r.readTimestampMs()))
            else -> throw IllegalArgumentException("unknown binary frame type: $type")
        }
    }

    private fun readString(r: ByteReader): String {
        val len = r.readVarInt()
        return r.readUtf8(len)
    }

    private fun decodeHello(r: ByteReader): Frame.Hello {
        val deviceId = readString(r)
        val name = readString(r)
        val protocol = r.readVarInt()
        return Frame.Hello(deviceId, name, protocol)
    }

    private fun decodeMsg(raw: ByteArray, r: ByteReader, flags: Int, session: SecureSession?, trustStore: AlertTrustStore?): BinaryDecodeResult {
        val msgId = r.readI32()
        val senderShortId = r.readU16()
        val langEmotion = r.readU8()
        val ttl = r.readU8() // consumed; not currently surfaced (no mesh relay yet, see class doc)
        val sentAt = r.readTimestampMs()
        val scheme = r.readU8()
        val payloadLen = r.readVarInt()
        val payload = r.readBytes(payloadLen)
        val headerBytes = raw.copyOfRange(0, HEADER_LEN_MSG)

        val lang = Lang.entries[(langEmotion ushr 4) and 0xF]
        val emotion = if (flags and FLAG_EMOTION_PRESENT != 0) Emotion.entries[langEmotion and 0xF] else null
        val id = resolveMessageId(msgId)
        val from = resolveSender(senderShortId)

        val encrypted = flags and FLAG_ENCRYPTED != 0
        val compressedBytes: ByteArray? = if (encrypted) {
            session?.decrypt(senderShortId, headerBytes, payload)
        } else {
            payload
        }

        if (compressedBytes == null) {
            // Can't recover the text (no/wrong session, or the AEAD tag didn't verify - tamper,
            // corruption, or a replayed nonce). Surface as an empty, non-alert message instead of
            // throwing, so one bad packet can't take down the receive loop.
            val msg = VoiceMessage(
                id = id, from = from, lang = lang, text = "",
                priority = Priority.NORMAL, emotion = emotion, ssml = false, sentAt = sentAt,
            )
            return BinaryDecodeResult(
                Frame.Msg(msg),
                alertDowngraded = flags and FLAG_ALERT != 0,
                downgradeReason = if (encrypted && session == null) "no session to decrypt" else "AEAD verification failed (tamper/corruption/replay)",
                decrypted = false,
            )
        }

        val text = TextDictionaryCodec.decompress(scheme, compressedBytes, lang)

        var priority = if (flags and FLAG_ALERT != 0) Priority.ALERT else Priority.NORMAL
        var downgraded = false
        var downgradeReason: String? = null

        if (priority == Priority.ALERT) {
            if (flags and FLAG_SIGNED != 0) {
                val sig = r.readBytes(SIGNATURE_BYTES)
                val trustedKey = trustStore?.publicKeyFor(senderShortId)
                val sigBuf = headerBytes + text.toByteArray(StandardCharsets.UTF_8)
                val ok = trustedKey != null && DeviceIdentity.verify(trustedKey, sigBuf, sig)
                if (!ok) {
                    priority = Priority.NORMAL
                    downgraded = true
                    downgradeReason = if (trustedKey == null) "ALERT signer not trusted/paired" else "ALERT signature invalid"
                }
            } else {
                priority = Priority.NORMAL
                downgraded = true
                downgradeReason = "ALERT not signed"
            }
        } else if (flags and FLAG_SIGNED != 0) {
            r.readBytes(SIGNATURE_BYTES) // keep the stream in sync; unused for a non-alert message
        }

        val message = VoiceMessage(
            id = id, from = from, lang = lang, text = text,
            priority = priority, emotion = emotion, ssml = flags and FLAG_SSML != 0, sentAt = sentAt,
        )
        return BinaryDecodeResult(Frame.Msg(message), alertDowngraded = downgraded, downgradeReason = downgradeReason, decrypted = encrypted)
    }

    private fun decodePartial(r: ByteReader): Frame.Partial {
        val msgId = r.readI32()
        val senderShortId = r.readU16()
        val langByte = r.readU8()
        val lang = Lang.entries[(langByte ushr 4) and 0xF]
        val scheme = r.readU8()
        val payloadLen = r.readVarInt()
        val payload = r.readBytes(payloadLen)
        val text = TextDictionaryCodec.decompress(scheme, payload, lang)
        return Frame.Partial(id = resolveMessageId(msgId), from = resolveSender(senderShortId), lang = lang, text = text)
    }

    private fun decodeAck(r: ByteReader): Frame.Ack {
        val msgId = r.readI32()
        val receivedAt = r.readTimestampMs()
        val playStartedAt = r.readTimestampMs()
        return Frame.Ack(id = resolveMessageId(msgId), receivedAt = receivedAt, playStartedAt = playStartedAt)
    }
}

/** Bounded `put`-order cache (not strictly LRU: eviction is FIFO by insertion, not by access). */
private class BoundedMap<K, V>(private val capacity: Int) {
    private val map = LinkedHashMap<K, V>(capacity, 0.75f, false)

    operator fun get(key: K): V? = map[key]

    operator fun set(key: K, value: V) {
        map[key] = value
        if (map.size > capacity) {
            val it = map.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
    }
}
