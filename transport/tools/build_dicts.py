#!/usr/bin/env python3
"""
build_dicts.py - generates transport/src/main/resources/dict/<lang>.dict, the per-script preset
DEFLATE dictionaries used by TextDictionaryCodec (see BinaryFrameCodec.kt) to compress the "Text"
field of a compact binary frame.

Source corpus (open text already in this repo, see docs/design.md "Evaluation plan"):
  - models/eval/stt/<lang>/manifest.tsv  (30 FLEURS sentences per language, real speech transcripts)
  - models/testclips/clips.tsv           (1 more FLEURS sentence per language)
  - tools/eval/alert_sentences.tsv       (5 hand-written emergency/ALERT sentences per language -
    "common emergency vocabulary", the phrases a compact ALERT frame is most likely to carry)

A DEFLATE preset dictionary acts as if it immediately precedes the compressed data, so LZ77 back-
references into it are cheapest (shortest distance) near its *end* - this script orders each
dictionary least-useful-first, with the alert-sentence corpus (highest value for the ALERT use
case) and the most frequent general words last.

Usage: python3 transport/tools/build_dicts.py
(stdlib only; no dependencies. Also prints a quick zlib-based compression estimate per language -
the real, authoritative measurement is TextDictionaryCodecMeasurementTest.kt, which exercises the
actual Kotlin/java.util.zip code path used at runtime.)
"""
from __future__ import annotations

import csv
import re
import sys
import zlib
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = REPO_ROOT / "transport" / "src" / "main" / "resources" / "dict"
MAX_DICT_BYTES = 12000
MAX_GENERAL_WORDS = 400

LANGS = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn", "en"]

WORD_RE = re.compile(r"\S+", re.UNICODE)


def read_manifest_transcripts(lang: str) -> list[str]:
    path = REPO_ROOT / "models" / "eval" / "stt" / lang / "manifest.tsv"
    if not path.exists():
        return []
    out = []
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            t = row.get("transcript", "").strip()
            if t:
                out.append(t)
    return out


def read_clips_tsv() -> dict[str, str]:
    path = REPO_ROOT / "models" / "testclips" / "clips.tsv"
    out: dict[str, str] = {}
    if not path.exists():
        return out
    with path.open(encoding="utf-8") as f:
        for row in csv.reader(f, delimiter="\t"):
            if len(row) >= 3:
                out[row[1].strip()] = row[2].strip()
    return out


def read_alert_sentences() -> dict[str, list[str]]:
    path = REPO_ROOT / "tools" / "eval" / "alert_sentences.tsv"
    out: dict[str, list[str]] = {lang: [] for lang in LANGS}
    if not path.exists():
        return out
    with path.open(encoding="utf-8") as f:
        reader = csv.DictReader(f, delimiter="\t")
        for row in reader:
            lang = row.get("lang", "").strip()
            sentence = row.get("sentence", "").strip()
            if lang in out and sentence:
                out[lang].append(sentence)
    return out


def build_dict_for_lang(lang: str, alert_sentences: list[str], clip: str | None) -> bytes:
    general_sentences = list(read_manifest_transcripts(lang))
    if clip:
        general_sentences.append(clip)

    freq: Counter[str] = Counter()
    for sentence in general_sentences:
        for w in WORD_RE.findall(sentence):
            freq[w] += 1
    # Emergency vocabulary counted too, but the sentences themselves are appended verbatim below.
    for sentence in alert_sentences:
        for w in WORD_RE.findall(sentence):
            freq[w] += 1

    # Most frequent last (cheapest LZ77 distance); cap word count so common short function words
    # (and the alert sentences) always fit inside MAX_DICT_BYTES.
    most_common = [w for w, _ in freq.most_common(MAX_GENERAL_WORDS)]
    most_common.reverse()  # least frequent of the selected set first, most frequent last
    words_blob = " ".join(most_common)

    alerts_blob = " ".join(alert_sentences)  # emergency phrases: highest value, placed last

    blob = (words_blob + " " + alerts_blob).strip()
    data = blob.encode("utf-8")
    if len(data) > MAX_DICT_BYTES:
        data = data[-MAX_DICT_BYTES:]  # keep the tail (alert sentences + most frequent words)
    return data


def estimate_compression(dict_bytes: bytes, sentences: list[str]) -> tuple[float, float]:
    """Quick zlib-based sanity estimate (same DEFLATE algorithm java.util.zip.Deflater uses)."""
    raw_sizes = []
    comp_sizes = []
    for s in sentences:
        raw = s.encode("utf-8")
        raw_sizes.append(len(raw))
        co = zlib.compressobj(9, zlib.DEFLATED, -15, 9, zlib.Z_DEFAULT_STRATEGY, dict_bytes)
        comp = co.compress(raw) + co.flush()
        comp_sizes.append(min(len(comp), len(raw)))
    raw_sizes.sort()
    comp_sizes.sort()
    n = len(raw_sizes)
    median_raw = raw_sizes[n // 2] if n else 0
    median_comp = comp_sizes[n // 2] if n else 0
    return median_raw, median_comp


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    clips = read_clips_tsv()
    alerts = read_alert_sentences()

    print(f"{'lang':<5} {'dict_bytes':>10} {'median_raw':>10} {'median_deflate':>15}")
    for lang in LANGS:
        alert_sentences = alerts.get(lang, [])
        dict_bytes = build_dict_for_lang(lang, alert_sentences, clips.get(lang))
        out_path = OUT_DIR / f"{lang}.dict"
        out_path.write_bytes(dict_bytes)

        all_sentences = list(read_manifest_transcripts(lang)) + alert_sentences
        if clips.get(lang):
            all_sentences.append(clips[lang])
        median_raw, median_comp = estimate_compression(dict_bytes, all_sentences) if all_sentences else (0, 0)
        print(f"{lang:<5} {len(dict_bytes):>10} {median_raw:>10} {median_comp:>15}")

    print(f"\nWrote {len(LANGS)} dictionaries to {OUT_DIR}")


if __name__ == "__main__":
    sys.exit(main())
