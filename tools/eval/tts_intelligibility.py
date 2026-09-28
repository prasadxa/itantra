#!/usr/bin/env python3
"""tts_intelligibility.py - ASR-based TTS intelligibility eval (NOT human MOS).

For each of the 10 languages: synthesizes 20 FLEURS sentences (not used in the STT test set) plus
5 hand-written alert-style sentences (tools/eval/alert_sentences.tsv, numbers/times, exercises the
text normaliser) on-device via DEBUG_TTS_SAVE, pulls the WAVs, transcribes them on the Mac with
the same SraVaani model the phone uses, and reports CER against the original text (lower CER =
more intelligible), plus synthesis RTF and first-chunk latency as logged by the app.

Caveat printed in every report: this measures whether an independent ASR model can recover the
text, which correlates with but is not the same as human-perceived intelligibility (MOS).

Must run with the shared-device lock held. Usage:
    tools/.venv/bin/python tools/eval/tts_intelligibility.py --out models/eval/results/tts_intel.json
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _adb  # noqa: E402
from _fleurs_common import ALL_LANGS, fetch_test_tsv  # noqa: E402
from _sravaani import load_recognizer, transcribe  # noqa: E402
from score_stt import cer as cer_counts, normalize  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def load_used_ids(lang: str) -> set[str]:
    p = ROOT / "models" / "eval" / "stt" / lang / "used_ids.txt"
    if not p.exists():
        return set()
    return {l.strip() for l in p.read_text(encoding="utf-8").splitlines() if l.strip()}


def pick_fleurs_sentences(lang: str, n: int) -> list[str]:
    used = load_used_ids(lang)
    rows = fetch_test_tsv(lang)
    out = []
    seen_text = set()
    for r in rows:
        if r.row_id in used:
            continue
        # FLEURS has multiple speaker takes per sentence (same text, different row_id/audio) -
        # dedupe by text so the 20 sentences are textually distinct.
        if r.raw_transcription in seen_text:
            continue
        wc = len(r.raw_transcription.split())
        if not (4 <= wc <= 25):
            continue
        out.append(r.raw_transcription)
        seen_text.add(r.raw_transcription)
        if len(out) >= n:
            break
    return out


def load_alert_sentences(lang: str) -> list[str]:
    path = Path(__file__).resolve().parent / "alert_sentences.tsv"
    out = []
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            if row["lang"] == lang:
                out.append(row["sentence"])
    return out


def synth_one(lang: str, idx: int, text: str, local_dir: Path) -> dict:
    remote_out = f"{_adb.DEVICE_FILES_ROOT}/eval_tts_out/{lang}/{idx:02d}.wav"
    local_out = local_dir / lang / f"{idx:02d}.wav"
    local_out.parent.mkdir(parents=True, exist_ok=True)

    _adb.logcat_clear()
    _adb.broadcast("org.itantra.DEBUG_TTS_SAVE", {"text": text, "lang": lang, "out": remote_out})
    evt = _adb.wait_for_metric_event("debug_tts_save", timeout_s=30.0, poll_s=0.5)
    if evt is None:
        return {"lang": lang, "idx": idx, "text": text, "error": "timeout_waiting_for_debug_tts_save"}
    if evt.get("error"):
        return {"lang": lang, "idx": idx, "text": text, "error": evt["error"]}
    try:
        _adb.pull(remote_out, str(local_out))
    except Exception as e:
        return {"lang": lang, "idx": idx, "text": text, "error": f"pull_failed: {e}"}
    return {
        "lang": lang, "idx": idx, "text": text, "local_wav": str(local_out),
        "rtf": evt.get("rtf"), "firstChunkMs": evt.get("firstChunkMs"),
        "synthMs": evt.get("synthMs"), "audioSeconds": evt.get("audioSeconds"), "engine": evt.get("engine"),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--langs", default=",".join(ALL_LANGS))
    ap.add_argument("--n-fleurs", type=int, default=20)
    ap.add_argument("--local-dir", default=str(ROOT / "models" / "eval" / "tts_out"))
    ap.add_argument("--out", default=str(ROOT / "models" / "eval" / "results" / "tts_intel.json"))
    ap.add_argument("--skip-synth", action="store_true", help="reuse WAVs already pulled locally (re-score only)")
    args = ap.parse_args()

    langs = [l.strip() for l in args.langs.split(",") if l.strip()]
    local_dir = Path(args.local_dir)

    if not args.skip_synth:
        _adb.require_lock_held()
        if not _adb.device_online():
            print(f"[tts_intel] device {_adb.SERIAL} not online - aborting", file=sys.stderr)
            return 1

    if not args.skip_synth:
        _adb.wake_device()
    thermal_before = _adb.thermal_status() if not args.skip_synth else "n/a (skip-synth)"
    print(f"[tts_intel] thermal before: {thermal_before}")

    per_sentence: list[dict] = []
    for lang in langs:
        sentences = [(f"fleurs", t) for t in pick_fleurs_sentences(lang, args.n_fleurs)]
        sentences += [("alert", t) for t in load_alert_sentences(lang)]
        print(f"[tts_intel] {lang}: {len(sentences)} sentences ({sum(1 for s in sentences if s[0]=='fleurs')} fleurs + {sum(1 for s in sentences if s[0]=='alert')} alert)")
        for idx, (kind, text) in enumerate(sentences):
            if args.skip_synth:
                local_out = local_dir / lang / f"{idx:02d}.wav"
                row = {"lang": lang, "idx": idx, "text": text, "kind": kind, "local_wav": str(local_out) if local_out.exists() else None}
                if not local_out.exists():
                    row["error"] = "missing_local_wav"
            else:
                row = synth_one(lang, idx, text, local_dir)
                row["kind"] = kind
                time.sleep(0.05)
            per_sentence.append(row)
            status = "ok" if row.get("local_wav") else f"ERR:{row.get('error')}"
            print(f"[tts_intel]   {lang}/{idx:02d} [{kind}] {status}")

    thermal_after = _adb.thermal_status() if not args.skip_synth else "n/a (skip-synth)"
    print(f"[tts_intel] thermal after: {thermal_after}")

    print("[tts_intel] transcribing pulled WAVs on Mac with SraVaani ...")
    rec = load_recognizer()
    per_lang: dict[str, dict] = {}
    for row in per_sentence:
        if not row.get("local_wav"):
            continue
        try:
            hyp = transcribe(rec, row["local_wav"])
        except Exception as e:
            row["error"] = f"transcribe_failed: {e}"
            continue
        row["asr_hyp"] = hyp
        ref_n = normalize(row["text"], row["lang"])
        hyp_n = normalize(hyp, row["lang"])
        ce, ct = cer_counts(ref_n, hyp_n)
        row["cer"] = ce / ct if ct else None
        b = per_lang.setdefault(row["lang"], {"char_errs": 0, "char_total": 0, "rtf": [], "firstChunkMs": [], "n": 0})
        b["char_errs"] += ce
        b["char_total"] += ct
        b["n"] += 1
        if row.get("rtf") is not None:
            b["rtf"].append(row["rtf"])
        if row.get("firstChunkMs") is not None:
            b["firstChunkMs"].append(row["firstChunkMs"])

    def median(xs):
        if not xs:
            return None
        s = sorted(xs)
        m = len(s) // 2
        return s[m] if len(s) % 2 else (s[m - 1] + s[m]) / 2

    summary = {
        "caveat": "ASR-based intelligibility (CER between synthesized-then-transcribed text and source text), NOT a human MOS score.",
        "by_lang": {
            lang: {
                "n": b["n"],
                "cer": b["char_errs"] / b["char_total"] if b["char_total"] else None,
                "rtf_median": median(b["rtf"]),
                "firstChunkMs_median": median(b["firstChunkMs"]),
            }
            for lang, b in sorted(per_lang.items())
        },
        "thermal_before": thermal_before,
        "thermal_after": thermal_after,
    }

    print(json.dumps(summary, indent=2, ensure_ascii=False))
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps({"summary": summary, "rows": per_sentence}, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[tts_intel] wrote {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
