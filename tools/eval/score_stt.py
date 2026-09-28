#!/usr/bin/env python3
"""score_stt.py - WER/CER + latency/RTF scoring for the on-device STT batch eval.

Input: a JSONL (or logcat-tag-filtered text) file of `debug_stt_batch_clip` events emitted by
DEBUG_STT_BATCH (app/src/debug/kotlin/org/itantra/app/DebugCommandReceiver.kt) on logcat tag
ITANTRA_METRIC, plus the manifest.tsv written by fetch_fleurs.py (for the reference transcript,
in case the device log line doesn't carry it).

Usage:
    tools/.venv/bin/python tools/eval/score_stt.py \
        --results models/eval/results/stt_batch.jsonl \
        --manifest models/eval/stt/manifest.tsv \
        --out docs/_stt_scores.json
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import statistics
import sys
import unicodedata
from pathlib import Path

INDIC_PUNCT = "।॥"
PUNCT_RE = re.compile(
    r"[!\"#$%&'()*+,\-./:;<=>?@\[\\\]^_`{|}~"
    + INDIC_PUNCT
    + r"‘’“”…]"
)
WS_RE = re.compile(r"\s+")


def normalize(text: str, lang: str) -> str:
    """NFC, strip punctuation (incl. Devanagari danda/double-danda), lowercase English, collapse
    whitespace. Shared normalisation so WER/CER isn't dominated by transliteration/punctuation
    noise the app's TextPipeline never claimed to preserve."""
    if text is None:
        return ""
    t = unicodedata.normalize("NFC", text)
    t = PUNCT_RE.sub(" ", t)
    if lang == "en":
        t = t.lower()
    t = WS_RE.sub(" ", t).strip()
    return t


def word_edit_distance(ref: list[str], hyp: list[str]) -> int:
    n, m = len(ref), len(hyp)
    dp = list(range(m + 1))
    for i in range(1, n + 1):
        prev = dp[0]
        dp[0] = i
        for j in range(1, m + 1):
            cur = dp[j]
            if ref[i - 1] == hyp[j - 1]:
                dp[j] = prev
            else:
                dp[j] = 1 + min(prev, dp[j], dp[j - 1])
            prev = cur
    return dp[m]


def char_edit_distance(ref: str, hyp: str) -> int:
    return word_edit_distance(list(ref), list(hyp))


def wer(ref: str, hyp: str) -> tuple[int, int]:
    r, h = ref.split(), hyp.split()
    if not r:
        return (0 if not h else len(h), 0)
    return word_edit_distance(r, h), len(r)


def cer(ref: str, hyp: str) -> tuple[int, int]:
    # For unsegmented scripts (Tamil/Telugu/etc. don't use spaces the way Latin does), CER is
    # the more meaningful accuracy number; WER is reported alongside for comparability.
    if not ref:
        return (len(hyp), 0)
    return char_edit_distance(ref, hyp), len(ref)


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * (p / 100.0)
    f, c = int(k), min(int(k) + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def load_manifest(path: Path) -> dict[str, dict]:
    by_key: dict[str, dict] = {}
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f, delimiter="\t"):
            by_key[row["filename"]] = row
    return by_key


def load_results(path: Path) -> list[dict]:
    """Accepts either raw JSONL, or full `adb logcat` text (extracts JSON after the tag)."""
    out = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            idx = line.find("{")
            if idx < 0:
                continue
            candidate = line[idx:]
            try:
                obj = json.loads(candidate)
            except json.JSONDecodeError:
                continue
            if obj.get("event") == "debug_stt_batch_clip":
                out.append(obj)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--results", required=True, help="JSONL or logcat dump with debug_stt_batch_clip events")
    ap.add_argument("--manifest", required=True, help="manifest.tsv (combined, from fetch_fleurs.py)")
    ap.add_argument("--out", default=None, help="write JSON summary here too")
    args = ap.parse_args()

    manifest = load_manifest(Path(args.manifest))
    results = load_results(Path(args.results))
    if not results:
        print(f"[score_stt] no debug_stt_batch_clip events found in {args.results}", file=sys.stderr)
        return 1

    per_lang: dict[str, dict] = {}
    overall = {"word_errs": 0, "word_total": 0, "char_errs": 0, "char_total": 0, "rtf": [], "latency_ms": []}
    rows_out = []

    for r in results:
        key = r.get("filename") or r.get("file")
        m = manifest.get(key)
        lang = r.get("lang") or (m["lang"] if m else "unknown")
        ref_raw = (m["transcript"] if m else r.get("reference", "")) or ""
        hyp_raw = r.get("text", "") or ""
        ref = normalize(ref_raw, lang)
        hyp = normalize(hyp_raw, lang)

        we, wt = wer(ref, hyp)
        ce, ct = cer(ref, hyp)

        bucket = per_lang.setdefault(
            lang, {"word_errs": 0, "word_total": 0, "char_errs": 0, "char_total": 0, "rtf": [], "latency_ms": [], "n": 0}
        )
        bucket["word_errs"] += we
        bucket["word_total"] += wt
        bucket["char_errs"] += ce
        bucket["char_total"] += ct
        bucket["n"] += 1
        overall["word_errs"] += we
        overall["word_total"] += wt
        overall["char_errs"] += ce
        overall["char_total"] += ct

        rtf = r.get("rtf")
        lat = r.get("sttLatencyMs")
        if rtf is not None:
            bucket["rtf"].append(rtf)
            overall["rtf"].append(rtf)
        if lat is not None:
            bucket["latency_ms"].append(lat)
            overall["latency_ms"].append(lat)

        rows_out.append(
            {"filename": key, "lang": lang, "ref": ref_raw, "hyp": hyp_raw, "wer": we / wt if wt else None, "cer": ce / ct if ct else None}
        )

    def summarize(b: dict) -> dict:
        return {
            "n": b.get("n"),
            "wer": b["word_errs"] / b["word_total"] if b["word_total"] else None,
            "cer": b["char_errs"] / b["char_total"] if b["char_total"] else None,
            "rtf_p50": percentile(b["rtf"], 50),
            "rtf_p95": percentile(b["rtf"], 95),
            "latency_ms_p50": percentile(b["latency_ms"], 50),
            "latency_ms_p95": percentile(b["latency_ms"], 95),
        }

    summary = {"overall": summarize(overall), "by_lang": {lang: summarize(b) for lang, b in sorted(per_lang.items())}}

    print(json.dumps(summary, indent=2, ensure_ascii=False))
    if args.out:
        Path(args.out).write_text(json.dumps({"summary": summary, "rows": rows_out}, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"[score_stt] wrote {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
