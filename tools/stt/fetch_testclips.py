#!/usr/bin/env python3
"""Fetch 1 short 16kHz mono WAV test clip per language from google/fleurs, plus its
reference transcript, for on-device STT testing.

google/fleurs on HF is published as one big single-row-group parquet per language/split
(no page index), so partial/streaming row access isn't possible -- the whole parquet
must be downloaded to read even one row. We download one language's parquet at a time,
extract the first short clip + reference text, then delete the parquet before moving to
the next language, so peak disk use stays at ~1 file (<= ~900 MB) instead of accumulating
~6 GB across all 10 languages.

Writes models/testclips/{lang}.wav + models/testclips/clips.tsv (file<TAB>lang<TAB>reference).
"""
import csv
import io
import sys
from pathlib import Path

import pyarrow.parquet as pq
import requests
import soundfile as sf

OUT = Path(__file__).resolve().parents[2] / "models" / "testclips"
OUT.mkdir(parents=True, exist_ok=True)
TMP_PARQUET = OUT / "_tmp.parquet"

# FLEURS config code -> our lang tag
LANGS = {
    "hi": "hi_in",
    "gu": "gu_in",
    "mr": "mr_in",
    "kn": "kn_in",
    "ml": "ml_in",
    "ta": "ta_in",
    "te": "te_in",
    "or": "or_in",
    "bn": "bn_in",
    "en": "en_us",
}

MAX_SECONDS = 9.0  # keep clips short


def download(url: str, dest: Path) -> None:
    with requests.get(url, stream=True, timeout=120) as r:
        r.raise_for_status()
        with open(dest, "wb") as f:
            for chunk in r.iter_content(chunk_size=1 << 20):
                f.write(chunk)


def main() -> None:
    only = sys.argv[1:] or list(LANGS.keys())
    rows = []
    existing_tsv = OUT / "clips.tsv"
    if existing_tsv.is_file():
        for line in existing_tsv.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) == 3 and parts[1] not in only:
                rows.append(tuple(parts))

    for lang in only:
        cfg = LANGS[lang]
        print(f"--- {lang} ({cfg}) ---", flush=True)
        url = f"https://huggingface.co/api/datasets/google/fleurs/parquet/{cfg}/test/0.parquet"
        print(f"  downloading {url}...", flush=True)
        download(url, TMP_PARQUET)
        size_mb = TMP_PARQUET.stat().st_size / 1e6
        print(f"  downloaded {size_mb:.1f} MB", flush=True)

        table = pq.read_table(TMP_PARQUET, columns=["audio", "num_samples", "transcription"])
        found = False
        for i in range(table.num_rows):
            n_samples = table["num_samples"][i].as_py()
            # FLEURS audio is 16kHz already; num_samples/16000 == duration.
            dur = n_samples / 16000.0
            if dur > MAX_SECONDS:
                continue
            text = table["transcription"][i].as_py()
            if not text:
                continue
            audio_bytes = table["audio"][i]["bytes"].as_py()
            arr, sr = sf.read(io.BytesIO(audio_bytes), dtype="int16")
            if arr.ndim > 1:
                arr = arr[:, 0]
            wav_path = OUT / f"{lang}.wav"
            sf.write(str(wav_path), arr, sr, subtype="PCM_16")
            print(f"  saved {wav_path.name}  dur={dur:.2f}s sr={sr}  ref={text[:60]!r}", flush=True)
            rows.append((f"{lang}.wav", lang, text.strip()))
            found = True
            break
        if not found:
            print(f"  [WARN] no clip <= {MAX_SECONDS}s found for {lang}", file=sys.stderr)

        TMP_PARQUET.unlink(missing_ok=True)

    with open(OUT / "clips.tsv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, delimiter="\t")
        for row in rows:
            w.writerow(row)
    print(f"\nWrote {len(rows)} clips to {OUT}")


if __name__ == "__main__":
    main()
