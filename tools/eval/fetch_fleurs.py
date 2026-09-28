#!/usr/bin/env python3
"""fetch_fleurs.py - build the on-device STT test set from Google FLEURS, without downloading
whole archives.

For each language, streams `data/<cfg>/audio/test.tar.gz` (tarfile mode "r|gz") and stops as soon
as N clips in [min-dur, max-dur] seconds have been collected. Saves 16 kHz mono WAVs plus a
manifest.tsv (used by the on-device DEBUG_STT_BATCH hook and by tools/eval/score_stt.py).

Usage:
    tools/.venv/bin/python tools/eval/fetch_fleurs.py
    tools/.venv/bin/python tools/eval/fetch_fleurs.py --langs hi,gu --n 10
"""

from __future__ import annotations

import argparse
import csv
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _fleurs_common import ALL_LANGS, FleursRow, fetch_test_tsv, stream_test_audio  # noqa: E402

TARGET_SR = 16000
QUIET_PEAK_DBFS_THRESHOLD = -20.0  # clips quieter than this get gained up
NORMALIZE_TARGET_DBFS = -3.0


def normalize_quiet(samples: np.ndarray) -> np.ndarray:
    """Gains up very quiet clips to a target peak dBFS; leaves normal-level clips untouched."""
    peak = float(np.max(np.abs(samples))) if samples.size else 0.0
    if peak <= 1e-6:
        return samples
    peak_dbfs = 20.0 * np.log10(peak)
    if peak_dbfs >= QUIET_PEAK_DBFS_THRESHOLD:
        return samples
    target_peak = 10.0 ** (NORMALIZE_TARGET_DBFS / 20.0)
    gain = target_peak / peak
    return np.clip(samples * gain, -1.0, 1.0)


def load_wav_16k_mono(raw_bytes: bytes) -> np.ndarray:
    import io

    data, sr = sf.read(io.BytesIO(raw_bytes), dtype="float32", always_2d=False)
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != TARGET_SR:
        from scipy.signal import resample_poly
        from math import gcd

        g = gcd(sr, TARGET_SR)
        data = resample_poly(data, TARGET_SR // g, sr // g).astype(np.float32)
    return data


def fetch_lang(lang: str, n: int, min_dur: float, max_dur: float, out_root: Path) -> list[dict]:
    print(f"[fetch_fleurs] {lang}: fetching test.tsv ...", file=sys.stderr)
    rows = fetch_test_tsv(lang)
    by_filename: dict[str, FleursRow] = {}
    for r in rows:
        if min_dur <= r.duration_sec <= max_dur:
            by_filename[r.filename] = r
    print(f"[fetch_fleurs] {lang}: {len(by_filename)}/{len(rows)} rows in [{min_dur},{max_dur}]s", file=sys.stderr)

    lang_dir = out_root / lang
    lang_dir.mkdir(parents=True, exist_ok=True)
    manifest_rows: list[dict] = []
    used_ids: set[str] = set()

    for filename, wav_bytes in stream_test_audio(lang):
        if len(manifest_rows) >= n:
            break
        row = by_filename.get(filename)
        if row is None:
            continue
        try:
            samples = load_wav_16k_mono(wav_bytes)
        except Exception as e:  # corrupt/short entries; skip
            print(f"[fetch_fleurs] {lang}: skip {filename}: {e}", file=sys.stderr)
            continue
        dur = len(samples) / TARGET_SR
        if not (min_dur <= dur <= max_dur):
            continue
        samples = normalize_quiet(samples)
        out_name = f"clip_{len(manifest_rows):03d}.wav"
        sf.write(str(lang_dir / out_name), samples, TARGET_SR, subtype="PCM_16")
        manifest_rows.append(
            {
                "filename": out_name,
                "lang": lang,
                "transcript": row.raw_transcription,
                "duration_sec": f"{dur:.3f}",
                "source_id": row.row_id,
            }
        )
        used_ids.add(row.row_id)
        print(f"[fetch_fleurs] {lang}: {len(manifest_rows)}/{n} -> {out_name} ({dur:.2f}s)", file=sys.stderr)

    if len(manifest_rows) < n:
        print(f"[fetch_fleurs] WARNING {lang}: only got {len(manifest_rows)}/{n} clips", file=sys.stderr)

    with open(lang_dir / "manifest.tsv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["filename", "lang", "transcript", "duration_sec", "source_id"], delimiter="\t")
        w.writeheader()
        w.writerows(manifest_rows)

    # Record which FLEURS ids were consumed here so tts_intelligibility.py can pick disjoint
    # sentences from the same test.tsv without re-downloading audio.
    with open(lang_dir / "used_ids.txt", "w", encoding="utf-8") as f:
        f.write("\n".join(sorted(used_ids)))

    return manifest_rows


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--langs", default=",".join(ALL_LANGS), help="comma-separated ISO 639-1 codes")
    ap.add_argument("--n", type=int, default=30, help="clips per language")
    ap.add_argument("--min-dur", type=float, default=4.0)
    ap.add_argument("--max-dur", type=float, default=12.0)
    ap.add_argument("--out-dir", default=str(Path(__file__).resolve().parents[2] / "models" / "eval" / "stt"))
    args = ap.parse_args()

    langs = [l.strip() for l in args.langs.split(",") if l.strip()]
    out_root = Path(args.out_dir)
    out_root.mkdir(parents=True, exist_ok=True)

    combined: list[dict] = []
    for lang in langs:
        rows = fetch_lang(lang, args.n, args.min_dur, args.max_dur, out_root)
        for r in rows:
            combined.append({**r, "filename": f"{lang}/{r['filename']}"})

    with open(out_root / "manifest.tsv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["filename", "lang", "transcript", "duration_sec", "source_id"], delimiter="\t")
        w.writeheader()
        w.writerows(combined)

    print(f"[fetch_fleurs] wrote {len(combined)} total clips to {out_root}/manifest.tsv")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
