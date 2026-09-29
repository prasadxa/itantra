# iTantra evaluation harness

Reproducible numbers for the SIH PS 26173 judging criteria: accuracy (STT WER, TTS
intelligibility) and latency (STT latency, TTS latency, RTF, sentence-said -> audio-starts
end-to-end). Owned files: `app/src/debug/**`, `tools/eval/**`, `tools/peer_sim.py`. Everything
else (STT/TTS engines, TextPipeline, transport, app/src/main UI) is owned by other agents working
in parallel - if a run fails because of a compile error outside these paths, that's not this
harness's bug.

## One command

```bash
tools/eval/run_all.sh            # full run
tools/eval/run_all.sh --quick    # 3 STT clips/lang, 3 TTS sentences/lang, N=2 bench - sanity check
```

Writes `docs/eval-results.md`. Intermediate artifacts land in `models/eval/` (gitignored-sized;
not meant to be committed).

## Shared device protocol

One vivo I2202 over USB, serial `10BD1C1C7T000HX` (`ITANTRA_ADB_SERIAL` env var to override),
shared with other agents. (An earlier run used a OnePlus 12 over wireless adb; that device is no
longer available - the default serial in `_adb.py`/`peer_sim.py`/`make_report.py`/`run_all.sh` was
switched to the vivo.) Every script here that touches the phone refuses to run unless
`/tmp/itantra-device.lock` exists:

```bash
until mkdir /tmp/itantra-device.lock 2>/dev/null; do sleep 10; done
# ... adb work ...
rmdir /tmp/itantra-device.lock
```

`run_all.sh` acquires it once for phases 1-3 and releases it before scoring/report generation
(which don't need the device). If you run the phase scripts individually, acquire/release the
lock yourself around each device-touching call - keep sessions short.

Thermal status (`dumpsys thermalservice`, Android THERMAL_STATUS 0=NONE .. 6=SHUTDOWN, plus peak
cached temp) is recorded before/after every device phase and surfaced in `docs/eval-results.md`;
the phone runs hot under sustained synth/decode load.

## Pipeline

1. **`fetch_fleurs.py`** - streams Google FLEURS `data/<cfg>/audio/test.tar.gz` (`tarfile` mode
   `"r|gz"`, stops as soon as N clips/language in [4,12]s are found - never downloads a whole
   archive) + `data/<cfg>/test.tsv` for transcripts. Writes 16 kHz mono WAVs + `manifest.tsv`
   (per-language and combined) to `models/eval/stt/<lang>/`, gaining up very quiet clips. Also
   writes `used_ids.txt` per language so `tts_intelligibility.py` can pick disjoint FLEURS
   sentences without re-downloading audio. No device needed.

2. **`run_stt_batch.py`** - pushes `models/eval/stt/` to
   `/sdcard/Android/data/org.itantra.app/files/models/eval/stt/`, broadcasts the on-device
   `DEBUG_STT_BATCH` hook (extra `manifest`, the on-device manifest path), which runs every clip
   through the real `SttEngine` sequentially with the mic paused and logs one
   `debug_stt_batch_clip` JSON line per clip (text, decodeMs, rtf, audioSeconds, sttLatencyMs) on
   logcat tag `ITANTRA_METRIC`. Dumps the matching lines to `models/eval/results/stt_batch.jsonl`.

3. **`score_stt.py`** - WER + CER (overall and per-language), RTF and STT-latency p50/p95, with
   shared Indic-aware normalisation (NFC, strips punctuation incl. `।॥`, lowercases English,
   collapses whitespace) so scores aren't dominated by transliteration/punctuation noise the app
   never claimed to preserve. `--results` also accepts a raw `adb logcat` dump (extracts JSON after
   the tag), not just JSONL.

4. **`tts_intelligibility.py`** - **ASR-based intelligibility, not human MOS.** For each language:
   20 FLEURS sentences not used in the STT set (deduped by text; FLEURS has multiple speaker takes
   per sentence) + 5 hand-written alert-style sentences with numbers/times
   (`tools/eval/alert_sentences.tsv`, exercises the text normaliser). Synthesizes each on-device via
   `DEBUG_TTS_SAVE` (extras `text`, `lang`, `out`; runs the real `TextPipeline` -> `TtsEngine`,
   resamples to 16 kHz mono on-device, writes a WAV, **never plays it**), pulls the WAV, transcribes
   on the Mac with the same SraVaani model (`tools/eval/_sravaani.py`,
   `OfflineRecognizer.from_transducer(..., model_type="nemo_transducer", feature_dim=128)`), and
   reports CER between source text and the ASR transcript (lower = more intelligible), plus the
   device-reported synthesis RTF and first-chunk latency.

5. **`peer_sim.py --bench N`** - end-to-end "sentence said -> audio starts on the other phone",
   with the Mac standing in for the second phone (only one physical phone is available):
   - **(a) phone -> Mac**: adb-broadcasts `DEBUG_STT_WAV` for real FLEURS clips already on the
     device; the app's normal orchestrator forwards the recognized sentence as a real `Frame.Msg`
     (same path as if it had been spoken live). `speechEndAt`/`sttDoneAt`/`sentAt` travel on the
     wire in `VoiceMessage`, so this reads them straight off the frame - no logcat parsing needed.
   - **(b) Mac -> phone**: sends N `Frame.Msg` per language and collects `Frame.Ack`
     (`receivedAt`/`playStartedAt`, both phone-clock - the receiver's real TTS-start latency).
   - Clock offset (phone vs Mac) is estimated NTP-style from several Ping/Pong round trips before
     either phase, so cross-clock arithmetic (e.g. Mac-received-time minus phone-send-time) is
     corrected.
   - Combined estimate per language = median(phase-a STT latency) + median(phase-a network) +
     median(phase-b receiver TTS-start latency). **This is an approximation**: phase (a) and phase
     (b) exercise the same physical phone in sender and receiver roles sequentially, not two phones
     simultaneously - a true two-phone measurement needs a second device.
   - Output: `models/eval/results/e2e_bench.json`. Requires `tools/peer/.venv` (zeroconf) and the
     app in the foreground with Wi-Fi transport connected/connectable (mDNS discovery).

6. **`make_report.py`** - assembles `docs/eval-results.md` from whatever JSON is present in
   `models/eval/results/` (missing phases are noted, not fatal). No device needed.

## Manual smoke test (no full run)

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
S=10BD1C1C7T000HX

# DEBUG_TTS_SAVE - note the *single pre-quoted remote command string*: `adb shell` re-parses its
# argv as one string on the device side, so multi-word/Unicode text must be shell-quoted for the
# remote shell yourself (see _adb.py:broadcast() / peer_sim.py:adb_broadcast()) - passing --es
# text "..." as separate adb argv elements silently mangles it on-device.
adb -s $S shell "am broadcast -a org.itantra.DEBUG_TTS_SAVE -n org.itantra.app/.DebugCommandReceiver --es text 'namaste, aap kaise hain' --es lang hi --es out /sdcard/Android/data/org.itantra.app/files/eval_tts_out/smoke.wav"
adb -s $S logcat -d -v raw -s ITANTRA_METRIC | grep debug_tts_save
```

## Known gaps / caveats to carry into `docs/eval-results.md`

- TTS intelligibility is ASR-based (see above), not a human MOS score.
- The e2e bench is a one-phone approximation of a two-phone conversation (see above).
- STT/TTS engine quality, the text normaliser, and transport are owned by other agents; this
  harness measures what they produce, it doesn't tune it.
