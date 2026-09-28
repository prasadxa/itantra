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

## Metrics (judging)
Per message: speechEnd→sttDone (STT latency), RTF, sent→received, received→playStarted (TTS latency), speechEnd→playStarted (end-to-end, clock-offset corrected). Idle CPU, RAM, model sizes shown on a metrics screen and exported as CSV.

## Models (pushed to `/sdcard/Android/data/org.itantra.app/files/models`, layout in ModelPaths.kt)
See `tools/` for fetch/export scripts.
