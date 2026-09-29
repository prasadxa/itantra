# :transport — compact binary frames + end-to-end security

Implements upgrades **B (compact binary packets)** and **I (security)** from
`docs/sih-idea-pack.html` "Useful upgrades beyond the current build", on top of the existing
JSON `Frame` wire protocol (`docs/design.md` "Wire framing").

## Why

The existing wire format is 4-byte length + single-line JSON (`FrameCodec`). That's fine over
Wi-Fi/BLE at 517-byte MTU, but the idea pack's "planned compact binary frame" targets slower links
(a LoRa bridge, low-MTU BLE) where every byte matters. This module adds a second, byte-compact
codec plus optional per-link encryption, and negotiates between the two per peer so **nothing about
the existing JSON path changes** — `tools/peer_sim.py` and any JSON-only peer keep working exactly
as before.

## Files

| File | What |
|---|---|
| `BinaryFrameCodec.kt` | Encodes/decodes every `Frame` type to the compact binary layout below |
| `TextDictionaryCodec.kt` | Per-script DEFLATE preset-dictionary text compression |
| `WireCodec.kt` | Picks JSON vs binary per send; auto-detects which one a received buffer uses |
| `Negotiation.kt` | `Frame.Hello.protocol` values used to negotiate binary support |
| `ByteIO.kt` | Varint + byte-cursor helpers |
| `crypto/DeviceIdentity.kt` | X25519 + Ed25519 key pair; ECDH→HKDF key derivation; signing |
| `crypto/PairingInfo.kt` | QR-friendly base64 pairing data + encode/decode |
| `crypto/ChaChaPoly1305.kt` | RFC 8439 ChaCha20-Poly1305 AEAD with an 8-byte truncated tag |
| `crypto/SecureSession.kt` | Per-link AEAD encrypt/decrypt + replay rejection |
| `crypto/ReplayGuard.kt` | Bounded per-sender seen-nonce cache |
| `crypto/AlertTrustStore.kt` | sender-short-id → trusted Ed25519 key, for ALERT verification |
| `tools/build_dicts.py` | Generates `src/main/resources/dict/<lang>.dict` from local FLEURS text |

Existing files touched (all additive — no existing public signature changed): `FrameCodec.kt`
(new `*Bytes` overloads for the pre-encoded-bytes path), `BleReassembler.kt`/`TcpLink.kt`/
`WifiTransport.kt`/`BleTransport.kt`/`TransportManager.kt` (wire in negotiation + optional
encryption/signing, all off by default). `core/` is untouched — every new type lives in
`:transport`, which `:app` already depends on.

## 1. Compact binary frame format

`version(4b)|type(4b)` (byte 0) selects the layout; every type shares the same small building
blocks (varint lengths, a 6-byte timestamp = 4-byte epoch seconds + 2-byte ms). `type` values:
`0` hello, `1` msg, `2` partial, `3` ack, `4` ptt, `5` ping, `6` pong.

### `Msg` (the layout `docs/sih-idea-pack.html`'s table specifies)

| Field | Bytes | Notes |
|---|---:|---|
| version + type | 1 | 4 bits each |
| flags | 1 | `0x01` ALERT priority · `0x02` SSML · `0x04` encrypted · `0x08` fragment (reserved) · `0x10` emotion nibble present · `0x40` Ed25519 signature appended |
| message id | 4 | stable hash of `VoiceMessage.id` (a UUID string) — see "id registries" below |
| sender short id | 2 | stable hash of `VoiceMessage.from` |
| lang + emotion | 1 | 4 bits each; emotion nibble is 0 (ignored) unless the `0x10` flag is set, so `emotion: Emotion?` round-trips `null` correctly |
| hop / TTL | 1 | carried on the wire for a future mesh relay; not yet surfaced to the app (see "Known limitations") |
| timestamp | 6 | `VoiceMessage.sentAt` only — see "what's dropped" below |
| compression scheme | 1 | `0` raw UTF-8, `1` DEFLATE + per-language preset dictionary |
| payload length | varint | |
| payload | N | compressed/raw text, or (if encrypted) `12-byte nonce ‖ ciphertext ‖ 8-byte truncated tag` |
| Ed25519 signature | 64 | present iff the `0x40` flag is set |

The 16-byte header (everything up to and including the timestamp) doubles as the AEAD associated
data when encrypted, and as the signature's prefix when signed — so tampering with the message id,
sender, language, TTL, or priority flag is detected even though those fields stay in the clear.

**What's dropped vs. `VoiceMessage`:** `speechEndAt`/`sttDoneAt` (local-only timing; not needed by
the peer) — a binary-mode message only carries `sentAt`. If full per-message latency breakdown is
needed end-to-end, use JSON for that link (or extend the format — the byte budget has room).

### Other types (smaller subsets of the same envelope)

- **Hello**: `deviceId` (varint len + UTF-8), `name` (varint len + UTF-8), `protocol` (varint).
  Always sent as **JSON**, never binary — see "Negotiation" below.
- **Partial**: id(4) + sender(2) + lang(1, high nibble) + compression scheme(1) + varint len + text.
- **Ack**: id(4) + receivedAt(6) + playStartedAt(6).
- **Ptt**: flags byte only (`0x20` = talking).
- **Ping**: t0(6). **Pong**: t0(6) + t1(6).

### id registries (`BinaryFrameCodec.registerMessageId`/`resolveMessageId`, `registerSender`/`resolveSender`)

The message id and sender id on the wire are 32-/16-bit hashes (FNV-1a), not the original strings —
that's most of the byte saving. `BinaryFrameCodec` keeps a small process-lifetime, bounded cache
both ways so:

- Encoding a `Msg` registers `VoiceMessage.id → wire id`; decoding a later `Ack` for that same id
  (on the sending phone) resolves back to the *exact original* `VoiceMessage.id`, not a lookalike.
- A receiver decoding a `Msg` for the first time (no prior registration) gets a synthetic
  `bin-xxxxxxxx` placeholder id instead — and registers it, so *its own* reply `Ack` for that
  message round-trips back to the same 4 bytes on the wire.

## 2. Text compression (per-script static dictionaries)

`TextDictionaryCodec` uses `java.util.zip.Deflater`/`Inflater` in raw mode (`nowrap=true`, no
zlib header/trailer — saves 6 bytes) primed with a preset dictionary per `Lang`
(`src/main/resources/dict/<lang>.dict`, one per `Lang.code`). A DEFLATE preset dictionary is
treated as bytes immediately preceding the input, so back-references into it are essentially free
even for a single short sentence — this is what gets a compact frame down to tens of bytes instead
of needing a whole corpus to build up its own compression state.

**Dictionary contents** (`tools/build_dicts.py`, stdlib-only, run once to regenerate): the most
frequent whitespace-tokenized words from `models/eval/stt/<lang>/manifest.tsv` (30 real FLEURS
transcripts per language) + `models/testclips/clips.tsv` (1 more), plus the 5 emergency/ALERT
sentences per language from `tools/eval/alert_sentences.tsv` appended verbatim (highest-value
content, placed last = cheapest back-reference distance). Capped at 12,000 bytes/language
(well under DEFLATE's 32 KB window); shipped dictionaries total **~100 KB** across all 10
languages — negligible next to the STT/TTS models this app already ships.

`compress()` always compares against raw UTF-8 and only uses the compressed form if it's actually
smaller (falls back to `SCHEME_RAW` otherwise), so compression can never make a frame *bigger*.

### Measured sizes (`CompressionMeasurementTest`, run against the real FLEURS manifests + clips.tsv — 310 sentences, 31/language)

| lang | n | JSON median | binary (raw text) median | binary + dict compression median |
|---|---:|---:|---:|---:|
| hi | 31 | 462 | 264 | 74 |
| gu | 31 | 503 | 305 | 69 |
| mr | 31 | 492 | 294 | 66 |
| kn | 31 | 487 | 289 | 55 |
| ml | 31 | 551 | 353 | 54 |
| ta | 31 | 528 | 330 | 61 |
| te | 31 | 505 | 307 | 58 |
| or | 31 | 485 | 287 | 63 |
| bn | 31 | 457 | 259 | 60 |
| en | 31 | 331 | 132 | 67 |
| **overall (pooled)** | **310** | **485** | **287** | **62** |

Binary framing alone is already ~1.7× smaller than JSON (shorter field names, no braces/quotes,
integer ids instead of UUID strings); adding dictionary compression brings every language's median
under the 80-byte target, with room to spare. Run it yourself:
`./gradlew :transport:testDebugUnitTest --tests "*CompressionMeasurementTest" -i`.

## 3. Negotiation

`Frame.Hello.protocol` (already existed, default `1`) now carries the capability signal instead of
a new field (`Frame`'s shape can't change — other modules depend on its exact constructor):

- `PROTOCOL_JSON_ONLY = 1` — legacy / `tools/peer_sim.py` (JSON-only, doesn't understand binary).
- `PROTOCOL_BINARY_CAPABLE = 2` — this build.

`TransportManager` sends `Hello(deviceId, deviceName, protocol = PROTOCOL_BINARY_CAPABLE)` on
connect. `WifiTransport`/`BleTransport` peek at every received `Hello` and flip a per-link
`binaryNegotiated` flag once the peer reports `protocol >= 2`; every later `send()` on that link
then uses `BinaryFrameCodec` instead of `FrameCodec`. **`Hello` itself is always sent as JSON** on
both sides (`WireCodec.encode` special-cases it) — so the very first frame on a fresh link is
always decodable before any negotiation has happened, and a JSON-only peer like `peer_sim.py` can
always read it (and, having sent back `protocol=1`, keeps the whole link on JSON afterwards).

Decoding doesn't need to track negotiation state at all: every `FrameCodec` (JSON) payload starts
with `{` (`0x7B`); every `BinaryFrameCodec` payload's first byte is `0x1X` (version 1, 4-bit type)
— the two can never collide, so `WireCodec.decode` auto-detects which codec to use per frame. A
buffer that fails to decode either way (corrupt, tampered, or a failed AEAD check) returns `null`
and is silently dropped by the reader loop instead of crashing it.

## 4. Security

**Key agreement — X25519.** **AEAD — ChaCha20-Poly1305, RFC 8439, 8-byte truncated tag in binary
mode.** **Alert signatures — Ed25519.**

### Crypto library choice

Considered: (a) Android's built-in `javax.crypto`/`java.security` providers, (b) Google Tink
(`tink-android`), (c) Bouncy Castle. **Went with Bouncy Castle's lightweight (non-JCE) API**
(`org.bouncycastle:bcprov-jdk18on`, static classes `math.ec.rfc7748.X25519`,
`math.ec.rfc8032.Ed25519`, `crypto.engines.ChaCha7539Engine`, `crypto.macs.Poly1305` — used
directly, no `Provider` registered):

- **Built-in providers don't cover minSdk 26.** Android's Conscrypt provider only gained
  `XDH`/`X25519` and `Ed25519` `KeyPairGenerator`/`Signature` support in **API 31**; this app's
  floor is API 26. Using them would mean either raising minSdk or a runtime-version-gated fallback
  — more risk than a small pure-Java library that behaves identically on every supported API level
  (and identically on the JVM in unit tests, vs. relying on a provider that isn't present on the
  test JVM at all without extra setup).
- **Tink vs. Bouncy Castle:** Tink's Android artifact plus its `protobuf-javalite` dependency is a
  comparable or larger footprint than Bouncy Castle for what's actually a handful of primitives,
  and Tink's `Aead`/`HybridEncrypt` abstractions don't expose a way to truncate the Poly1305 tag to
  8 bytes (the spec's explicit requirement) — same problem BC's own `ChaCha20Poly1305` AEAD class
  has (see below). Either way the truncation has to be done at a lower level, so the extra
  abstraction doesn't buy anything here.
- Bouncy Castle's lightweight classes are used **directly**, not through a JCE `Provider` — this
  avoids `Security.addProvider()` and pulls in only the specific algorithm classes referenced,
  which matters for what R8 can strip at release build time (see "APK size impact" below).

### Truncated-tag AEAD (`crypto/ChaChaPoly1305.kt`)

Bouncy Castle's own `org.bouncycastle.crypto.modes.ChaCha20Poly1305` AEAD engine **hard-requires a
128-bit tag** (its `init()` throws `IllegalArgumentException` for any other `macSizeInBits` —
checked against the library source before relying on it). So the spec's "8-byte truncated tag" is
implemented by directly composing Bouncy Castle's lower-level, well-tested primitives
(`ChaCha7539Engine` for the keystream, `Poly1305` for the one-time MAC) per the exact RFC 8439
§2.8 recipe, then keeping only the tag's first 8 bytes on the wire. **`ChaChaPoly1305Test`
cross-checks the *full* 16-byte tag and ciphertext byte-for-byte against Bouncy Castle's own AEAD
engine** (rather than a hand-transcribed test vector) — so the composition is verified against a
trusted, spec-conformant implementation before it's ever truncated.

Trade-off: truncating the tag from 16 to 8 bytes reduces forgery resistance from 2⁻¹²⁸ to 2⁻⁶⁴ per
forgery attempt. Given per-packet random 12-byte nonces and that this guards short-lived
walkie-talkie messages (not, say, a long-term stored ciphertext), this is judged an acceptable
trade for wire size on a low-bitrate link; extending `ChaChaPoly1305.TAG_BYTES` back to 16 is a
one-line change if that judgement changes.

### Pairing

`DeviceIdentity.generate()` creates a long-term X25519 + Ed25519 key pair (persist the raw bytes —
that's an app-layer concern, not done here). `DeviceIdentity.pairingInfo(deviceId)` returns a
`PairingInfo` (device id + both public keys); `PairingInfo.toQrString()`/`.fromQrString()` give a
compact base64url string for a QR code. **Rendering/scanning the QR code is the UI agent's job** —
this only provides the data type and the encode/decode functions, as scoped.

Once both sides have exchanged `PairingInfo` (however — QR, NFC, manual paste),
`SecureSession.establish(myIdentity, peerPairingInfo)` runs X25519 ECDH + HKDF-SHA256 into a
32-byte AEAD key. `TransportManager.configureSecurity(session, signWith, trustStore)` wires that
(plus an optional signing identity and `AlertTrustStore`) into both links; `null`/unset (the
default) means **unencrypted, unsigned binary frames — "encryption optional, off until paired"**
per the spec. Every packet gets a fresh random 12-byte nonce (`ChaChaPoly1305.randomNonce`); a
receiver rejects a repeated `(senderShortId, nonce)` pair as a replay (`ReplayGuard`, bounded to
512 recent nonces per session).

### ALERT signing and downgrade

`BinaryFrameCodec.encode(frame, signWith = identity)` signs `header ‖ plaintext text` with Ed25519
and appends the 64-byte signature, but **only when the message's priority is ALERT** — normal
messages are never signed. On decode, an ALERT is required to carry a signature that verifies
against a key the receiver has explicitly marked trusted for that sender
(`AlertTrustStore.trust(senderShortId, ed25519PublicKey)` — normally populated once a peer's
`PairingInfo` is accepted): missing signature, no trust-store entry for that sender, or a signature
that doesn't verify (including if the text was tampered with) all **downgrade the message to
`Priority.NORMAL`** rather than dropping it, so it still gets through — just without the "trusted
alert" treatment. `BinaryFrameCodec.decodeDetailed()` (vs. the plain `decode()`) surfaces
`alertDowngraded: Boolean` + a `downgradeReason: String` for this, since `Priority`/`VoiceMessage`
can't gain a third "downgraded ALERT" state without changing their shape — the UI can check this
when it's wired up; today the effect is simply that a forged/unsigned ALERT can never force the
urgent-audio/UI path (fail-closed: **no trust store at all downgrades every ALERT**, by design).

### APK size impact

`bcprov-jdk18on` (1.86) is a **7.2 MB** jar. Measured directly (`./gradlew :app:assembleDebug`,
before vs. after adding the dependency — nothing else changed between the two builds):

| | before | after | delta |
|---|---:|---:|---:|
| `app-debug.apk` | 57,361,782 B (54.7 MB) | 59,651,108 B (56.9 MB) | **+2,289,326 B (≈2.18 MB)** |

The delta (≈2.2 MB) is well under the raw 7.2 MB jar even though **this project currently builds
with `isMinifyEnabled = false` for *both* variants** (`app/build.gradle.kts` — not changed here) —
i.e. this isn't an R8-shrunk number, just DEX compilation + the APK's own zip compression already
discarding a fair amount of the jar's JVM-bytecode-level bulk (debug metadata, etc.). No JCE
`Provider` is registered anywhere in this module (the lightweight API is called directly), so
nothing is pulled in reflectively that R8 would otherwise have to keep. If APK size becomes a hard
constraint, turning `isMinifyEnabled` on (project-wide, not just for this dependency) is the next
lever — R8 would then strip everything except the handful of BC classes actually referenced
(`X25519`, `Ed25519`, `ChaCha7539Engine`, `Poly1305`, `HKDFBytesGenerator` + their direct
dependencies), which should shrink this further; not measured here since it'd require flipping
that project-wide flag, out of scope for this pass. Tink's footprint (`tink-android` +
`protobuf-javalite`) is comparable-to-larger for the same three primitives, for reference.

## 5. Testing

`./gradlew :transport:testDebugUnitTest` (all pure-JVM, no Android framework needed — matches the
existing `FrameCodecTest` pattern):

- `BinaryFrameCodecTest` — round-trips every `Frame` type; ACK id correlation (both "same process"
  and "receiver never saw the original id" cases); compression scheme survives the round trip;
  encrypted round-trip + tamper detection (ciphertext byte, tag byte, and header/AAD byte, each
  independently); replay rejection; ALERT signing (trusted/valid keeps ALERT, unsigned/untrusted/
  tampered/no-trust-store all downgrade to NORMAL with a reason).
- `ChaChaPoly1305Test` — cross-checks the full tag against Bouncy Castle's own AEAD engine; seal/
  open round-trip; rejects tampered ciphertext/tag/AAD/wrong key; different nonces → different
  ciphertext.
- `DeviceIdentityTest` — X25519 agreement is symmetric and peer-specific; HKDF `info` binds to
  different derived keys; Ed25519 sign/verify accepts genuine, rejects wrong-key/tampered/garbage.
- `PairingInfoTest` — QR string round-trips, is URL-safe, rejects malformed input.
- `SecureSessionTest` — end-to-end session establish + decrypt; replay rejection (including that
  two different `senderShortId`s don't false-positive each other); tamper rejection; short-input
  handling.
- `TextDictionaryCodecTest` — round-trips every language; a real dictionary actually shrinks a real
  sentence; raw-scheme and empty-text fallback paths.
- `CompressionMeasurementTest` — the sizes table above, computed from the real FLEURS manifests
  through the actual runtime codepath (not the Python generator's estimate); asserts the ≤80-byte
  target.
- `FrameCodecTest` (pre-existing, unmodified) still passes — the JSON path's behavior is untouched.

## Known limitations / next steps (not in scope for this pass)

- **Hop/TTL is carried on the wire but not yet acted on** — no store-and-forward mesh relay yet
  (that's the idea pack's separate, larger "Store-and-forward mesh relay" upgrade).
  `BinaryFrameCodec` reads and discards it today.
- **Pairing UI (QR scan screen) is not built here** — `PairingInfo`/`DeviceIdentity` are ready for
  it, per the task's scoping ("the UI agent will add the QR screen later").
  `TransportManager.configureSecurity()` is the integration point.
- **Key persistence** (`DeviceIdentity`'s raw key bytes across app restarts) is an app-layer
  concern, not implemented here.
- `Frame.Partial` isn't encrypted/signed in binary mode (only `Msg` is) — partials are ephemeral
  live-typing previews, judged lower value to protect than the final message.
