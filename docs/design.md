# iTantra — design (v0.1)

SIH PS 26173. Offline Android walkie-talkie: speech → text on sender, text over Wi-Fi/BLE, text → speech on receiver.
No post-training; open models as-is. Target: 8 GB arm64 phone, minSdk 26, targetSdk 36. No Google Play Services.

## Modules (contracts live in `:core`, see `core/src/main/kotlin/org/itantra/core`)
| Module | Owns | Implements |
|---|---|---|
| `:core` | Lang, VoiceMessage, Frame (wire protocol), engine interfaces, ModelPaths | — |
| `:stt` | Mic capture (AudioRecord 16 kHz), sherpa-onnx Silero VAD + SraVaani-1.0 TDT int8, hotwords | `SttEngine` |
| `:tts` | Indic-Mio via mio-tts-cpp (llama.cpp, JNI) + rasa VITS fallback (sherpa-onnx), AudioTrack player, alert player | `TtsEngine` |
| `:text` | Indic number/date/currency normaliser (10 langs), SSML subset parser, auto style classifier | `TextPipeline` |
| `:transport` | Wi-Fi TCP (NSD + Wi-Fi Direct) primary, BLE GATT fallback; framing | `Transport` |
| `:app` | Compose UI, foreground service, orchestrator, PTT/phone modes, metrics | — |

## Flow
Mic → VAD cuts sentence at pause → SraVaani → `VoiceMessage` → `Frame.Msg` → peer → `TextPipeline.plan` → `TtsEngine` per segment → player.
ALERT priority: USAGE_ALARM, alarm stream at max, AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, not interruptible, queue-jumps normal messages.
PTT mode: mic only while button held; release = `SttEngine.flush()`; `Frame.Ptt` for floor control.
Phone mode (PTT off): mic always on with VOICE_COMMUNICATION (AEC); incoming speech played meanwhile.

## Wire framing
TCP: 4-byte big-endian length + UTF-8 JSON of `Frame` (kotlinx.serialization, class discriminator `type`).
BLE: Nordic-UART-style service; each JSON frame split into chunks with 3-byte header `[seq, index, count]`.

## Expressiveness
No SSML: classifier picks `Emotion` (alert keywords → URGENT; `?` → question intonation kept in text; else NEUTRAL).
SSML subset: `<speak> <break time> <prosody rate|pitch|volume> <emphasis> <say-as interpret-as=cardinal|digits|date|time|telephone> <s> <p>` and custom `<emotion name>` → `TtsSegment` fields.
Mio emotion tags appended to sentence: `<happy> <sad> <angry> <fear> <surprise> <disgust>`; `*word*` = stress. URGENT → `<angry>`-free fast/loud rendering: rate 1.1, +6 dB, neutral/fear tag (tune by ear).

## Profiles (efficiency on low/mid-range phones)
`Profile { FULL, LITE }`, resolved by `ProfileManager` at `TalkService` startup from
`ActivityManager.MemoryInfo.totalMem` (< 6 GB → LITE), overridable Auto/Full/Lite from the Profile
row on the Models screen (persisted in `SharedPreferences`; switching restarts the service).
LITE: STT/TTS `numThreads` 2 (vs 4 on FULL); TTS stays VITS-first where VITS has a voice (bn kn ml
mr ta te, unchanged from FULL) and Mio for hi/gu/or/en, preferring `ModelPaths.mioModelLite`
(`tts/mio/indic-mio-q4.gguf`) when present, else the q8 file; live STT partials off entirely
(`SttEngine.partialListener` left `null`, so `SherpaSttEngine` skips the decode, not just the
callback). FULL: current behaviour (numThreads 4, live partials every 500ms).
Open item: the LITE-only Mio model-path selection and a 60s-idle Mio-unload hook need a small API
addition in `tts/.../engine/` (owned by the TTS-engine agent) — `EngineFactory` already computes
and passes `numThreads`/the profile so wiring the rest is a small follow-up there.

## Metrics (judging)
Per message: speechEnd→sttDone (STT latency), RTF, sent→received, received→playStarted (TTS latency), speechEnd→playStarted (end-to-end, clock-offset corrected). Metrics screen adds: PSS, Java heap, native heap, CPU% over the last 10s (idle), model bytes on disk, APK size — all exported in the CSV (a `# resource_snapshot` header row above the per-message table). A debug-free `ITANTRA_METRIC` logcat line (`event: resource`) fires every 30s with idle CPU% + PSS, for `adb logcat` on a release build.

## Voice notes (replay)
`:tts` `SpeechOutput` retains the synthesized PCM of the last 20 received messages (NORMAL and
ALERT), bounded to ~20 MB, oldest evicted first, and exposes `replay(id)` to re-play it without
re-running TTS — ALERT notes replay via the same alarm path (max `STREAM_ALARM` volume,
`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`) as their original playback. Transcript rows for received
messages show a small mono "▶ REPLAY  N.Ns" control (matches the field-radio glyph/label style
used elsewhere in `ui/theme`) once the audio is cached; alerts get the same control.

## Models (pushed to `/sdcard/Android/data/org.itantra.app/files/models`, layout in ModelPaths.kt)
See `tools/` for fetch/export scripts.
