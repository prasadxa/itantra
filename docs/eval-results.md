# iTantra evaluation results

Generated: 2026-09-29T01:21:52
Device: OnePlus 12 (unknown), wireless adb

Harness: `tools/eval/` (owned by this agent) + on-device hooks in `app/src/debug/kotlin/org/itantra/app/DebugCommandReceiver.kt` (`DEBUG_STT_BATCH`, `DEBUG_TTS_SAVE`, plus the pre-existing `DEBUG_STT_WAV`/`DEBUG_SPEAK`). Test set: streamed Google FLEURS (`tools/eval/fetch_fleurs.py`), 30 clips/language, 4-12s, 16 kHz mono. See `tools/eval/README.md` for how to reproduce.

## 1. STT accuracy (WER/CER) and latency

_Not run (no `stt_scores.json` in the results dir) - see tools/eval/README.md to reproduce._

## 2. TTS intelligibility (ASR-based, not human MOS)

**Caveat:** CER here is between the sentence text and what SraVaani (the same STT model the app uses) transcribes back from the synthesized audio. It measures whether an independent ASR can recover the words, which correlates with but is not the same as human-perceived intelligibility or naturalness.

_Not run (no `tts_intel.json` in the results dir) - see tools/eval/README.md to reproduce._

## 3. End-to-end latency (sentence said -> audio starts on the other phone)

Measured with the Mac standing in for the second phone (`tools/peer_sim.py --bench`): phone->Mac uses real DEBUG_STT_WAV-triggered `Frame.Msg` sends (STT latency + network, clock-offset corrected via Ping/Pong); Mac->phone sends text and reads back `Frame.Ack` for the receiver's TTS-start latency. The combined number below adds phase (a)'s STT latency + network to phase (b)'s receiver TTS-start latency - it approximates a real two-phone conversation using one physical phone in both roles, since only one phone was available; it is not a simultaneous two-device measurement.

_Not run (no `e2e_bench.json` in the results dir) - see tools/eval/README.md to reproduce._

## Caveats

- TTS intelligibility is ASR-based (see caveat above), not human MOS.
- The end-to-end latency bench uses one physical phone in both sender and receiver roles sequentially (see note above); it is an estimate, not a live two-phone measurement.
- The phone runs hot under sustained synthesis/decoding load; thermal status is recorded before/after each phase above (`dumpsys thermalservice`: 0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL).
- This agent owns `app/src/debug/**`, `tools/eval/**`, `tools/peer_sim.py` only. STT/TTS model quality, TextPipeline normalisation, and transport implementation are owned by parallel agents and out of scope for edits here.
