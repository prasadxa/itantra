#!/usr/bin/env python3
"""make_report.py - assembles docs/eval-results.md from the JSON produced by the other eval
scripts. Run last, after everything else (does not touch the device).
"""

from __future__ import annotations

import argparse
import datetime
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_json(path: Path):
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None


def fmt(x, nd=3):
    if x is None:
        return "-"
    if isinstance(x, (int,)):
        return str(x)
    try:
        return f"{float(x):.{nd}f}"
    except (TypeError, ValueError):
        return str(x)


def device_model() -> str:
    import os

    adb_bin = os.environ.get("ADB_BIN", str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    serial = os.environ.get("ITANTRA_ADB_SERIAL", "10BD1C1C7T000HX")
    try:
        r = subprocess.run(
            [adb_bin, "-s", serial, "shell", "getprop", "ro.product.model"],
            capture_output=True, text=True, timeout=10,
        )
        return r.stdout.strip() or "unknown"
    except Exception:
        return "unknown (adb unavailable when report was generated)"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--results-dir", default=str(ROOT / "models" / "eval" / "results"))
    ap.add_argument("--out", default=str(ROOT / "docs" / "eval-results.md"))
    args = ap.parse_args()

    rdir = Path(args.results_dir)
    stt_scores = load_json(rdir / "stt_scores.json")
    stt_meta = load_json(rdir / "stt_batch.meta.json")
    tts_intel = load_json(rdir / "tts_intel.json")
    e2e = load_json(rdir / "e2e_bench.json")

    lines = []
    lines.append("# iTantra evaluation results")
    lines.append("")
    lines.append(f"Generated: {datetime.datetime.now().isoformat(timespec='seconds')}")
    lines.append(f"Device: OnePlus 12 ({device_model()}), wireless adb")
    lines.append("")
    lines.append(
        "Harness: `tools/eval/` (owned by this agent) + on-device hooks in "
        "`app/src/debug/kotlin/org/itantra/app/DebugCommandReceiver.kt` "
        "(`DEBUG_STT_BATCH`, `DEBUG_TTS_SAVE`, plus the pre-existing `DEBUG_STT_WAV`/`DEBUG_SPEAK`). "
        "Test set: streamed Google FLEURS (`tools/eval/fetch_fleurs.py`), 30 clips/language, 4-12s, "
        "16 kHz mono. See `tools/eval/README.md` for how to reproduce."
    )
    lines.append("")

    # ---- STT accuracy + latency ----
    lines.append("## 1. STT accuracy (WER/CER) and latency")
    lines.append("")
    if stt_meta:
        lines.append(f"Thermal before/after: `{stt_meta.get('thermal_before')}` / `{stt_meta.get('thermal_after')}`")
        lines.append("")
    if stt_scores:
        s = stt_scores["summary"]
        lines.append("| Language | n | WER | CER | RTF p50 | RTF p95 | STT latency p50 (ms) | STT latency p95 (ms) |")
        lines.append("|---|---|---|---|---|---|---|---|")
        for lang, b in s["by_lang"].items():
            lines.append(
                f"| {lang} | {b['n']} | {fmt(b['wer'])} | {fmt(b['cer'])} | {fmt(b['rtf_p50'])} | {fmt(b['rtf_p95'])} "
                f"| {fmt(b['latency_ms_p50'],1)} | {fmt(b['latency_ms_p95'],1)} |"
            )
        o = s["overall"]
        lines.append(
            f"| **overall** | | **{fmt(o['wer'])}** | **{fmt(o['cer'])}** | {fmt(o['rtf_p50'])} | {fmt(o['rtf_p95'])} "
            f"| {fmt(o['latency_ms_p50'],1)} | {fmt(o['latency_ms_p95'],1)} |"
        )
    else:
        lines.append("_Not run (no `stt_scores.json` in the results dir) - see tools/eval/README.md to reproduce._")
    lines.append("")

    # ---- TTS intelligibility ----
    lines.append("## 2. TTS intelligibility (ASR-based, not human MOS)")
    lines.append("")
    lines.append(
        "**Caveat:** CER here is between the sentence text and what SraVaani (the same STT model "
        "the app uses) transcribes back from the synthesized audio. It measures whether an "
        "independent ASR can recover the words, which correlates with but is not the same as "
        "human-perceived intelligibility or naturalness."
    )
    lines.append("")
    if tts_intel:
        s = tts_intel["summary"]
        lines.append(f"Thermal before/after: `{s.get('thermal_before')}` / `{s.get('thermal_after')}`")
        lines.append("")
        lines.append("| Language | n | ASR-CER (lower=better) | synth RTF (median) | first-chunk latency p50 (ms) |")
        lines.append("|---|---|---|---|---|")
        for lang, b in s["by_lang"].items():
            lines.append(f"| {lang} | {b['n']} | {fmt(b['cer'])} | {fmt(b['rtf_median'])} | {fmt(b['firstChunkMs_median'],1)} |")
    else:
        lines.append("_Not run (no `tts_intel.json` in the results dir) - see tools/eval/README.md to reproduce._")
    lines.append("")

    # ---- End-to-end latency ----
    lines.append("## 3. End-to-end latency (sentence said -> audio starts on the other phone)")
    lines.append("")
    lines.append(
        "Measured with the Mac standing in for the second phone (`tools/peer_sim.py --bench`): "
        "phone->Mac uses real DEBUG_STT_WAV-triggered `Frame.Msg` sends (STT latency + network, "
        "clock-offset corrected via Ping/Pong); Mac->phone sends text and reads back `Frame.Ack` "
        "for the receiver's TTS-start latency. The combined number below adds phase (a)'s STT "
        "latency + network to phase (b)'s receiver TTS-start latency - it approximates a real "
        "two-phone conversation using one physical phone in both roles, since only one phone was "
        "available; it is not a simultaneous two-device measurement."
    )
    lines.append("")
    if e2e:
        lines.append(f"Clock offset (phone - Mac): {fmt(e2e.get('clockOffsetMsPhoneMinusMac'),1)} ms")
        lines.append(f"Thermal before/after: `{e2e.get('thermal_before')}` / `{e2e.get('thermal_after')}`")
        lines.append("")
        lines.append(
            "| Language | STT lat. p50 (ms) | Network p50 (ms) | Receiver TTS-start p50 (ms) | "
            "STT lat. p95 (ms) | Receiver TTS-start p95 (ms) | **Estimated speech-said -> audio-started (ms)** |"
        )
        lines.append("|---|---|---|---|---|---|---|")
        for lang, b in e2e["by_lang"].items():
            lines.append(
                f"| {lang} | {fmt(b['sttLatencyMs_p50'],1)} | {fmt(b['networkMs_p50'],1)} | {fmt(b['receiverTtsLatencyMs_p50'],1)} "
                f"| {fmt(b['sttLatencyMs_p95'],1)} | {fmt(b['receiverTtsLatencyMs_p95'],1)} | **{fmt(b['estimatedSpeechSaidToAudioStartedMs'],1)}** |"
            )
    else:
        lines.append("_Not run (no `e2e_bench.json` in the results dir) - see tools/eval/README.md to reproduce._")
    lines.append("")

    lines.append("## Caveats")
    lines.append("")
    lines.append("- TTS intelligibility is ASR-based (see caveat above), not human MOS.")
    lines.append("- The end-to-end latency bench uses one physical phone in both sender and receiver roles sequentially (see note above); it is an estimate, not a live two-phone measurement.")
    lines.append("- The phone runs hot under sustained synthesis/decoding load; thermal status is recorded before/after each phase above (`dumpsys thermalservice`: 0=NONE 1=LIGHT 2=MODERATE 3=SEVERE 4=CRITICAL).")
    lines.append("- This agent owns `app/src/debug/**`, `tools/eval/**`, `tools/peer_sim.py` only. STT/TTS model quality, TextPipeline normalisation, and transport implementation are owned by parallel agents and out of scope for edits here.")
    lines.append("")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"[make_report] wrote {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
