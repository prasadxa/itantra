#!/usr/bin/env python3
"""Validate the exported SraVaani sherpa-onnx model: decode models/testclips/*.wav
with greedy_search, print transcript vs reference + RTF, and check
modified_beam_search + hotwords_file works.

Usage: python validate_sravaani.py
"""
import csv
import time
from pathlib import Path

import sherpa_onnx
import soundfile as sf

ROOT = Path(__file__).resolve().parents[2]
STT_DIR = ROOT / "models" / "stt" / "sravaani"
CLIPS_DIR = ROOT / "models" / "testclips"


def make_recognizer(decoding_method="greedy_search", hotwords_file=""):
    kwargs = dict(
        encoder=str(STT_DIR / "encoder.int8.onnx"),
        decoder=str(STT_DIR / "decoder.int8.onnx"),
        joiner=str(STT_DIR / "joiner.int8.onnx"),
        tokens=str(STT_DIR / "tokens.txt"),
        num_threads=2,
        sample_rate=16000,
        feature_dim=128,
        decoding_method=decoding_method,
        model_type="nemo_transducer",
    )
    if hotwords_file:
        kwargs["hotwords_file"] = hotwords_file
        kwargs["hotwords_score"] = 2.0
        kwargs["modeling_unit"] = "bpe"
        kwargs["bpe_vocab"] = str(STT_DIR / "bpe.vocab")
    return sherpa_onnx.OfflineRecognizer.from_transducer(**kwargs)


def decode_file(rec, wav_path: Path):
    audio, sr = sf.read(str(wav_path), dtype="float32", always_2d=False)
    if audio.ndim > 1:
        audio = audio[:, 0]
    dur = len(audio) / sr
    stream = rec.create_stream()
    stream.accept_waveform(sr, audio)
    t0 = time.time()
    rec.decode_stream(stream)
    elapsed = time.time() - t0
    text = stream.result.text
    rtf = elapsed / dur if dur > 0 else float("nan")
    return text, dur, elapsed, rtf


def main():
    print("=== greedy_search decode of models/testclips/*.wav ===")
    rec = make_recognizer("greedy_search")
    rows = []
    tsv = CLIPS_DIR / "clips.tsv"
    if tsv.is_file():
        with open(tsv, encoding="utf-8") as f:
            for line in csv.reader(f, delimiter="\t"):
                if len(line) == 3:
                    rows.append(line)
    total_dur = total_elapsed = 0.0
    for fname, lang, ref in rows:
        wav_path = CLIPS_DIR / fname
        if not wav_path.is_file():
            print(f"[skip] {fname} missing")
            continue
        text, dur, elapsed, rtf = decode_file(rec, wav_path)
        total_dur += dur
        total_elapsed += elapsed
        print(f"\n[{lang}] {fname}  dur={dur:.2f}s  decode={elapsed:.3f}s  RTF={rtf:.3f}")
        print(f"  ref: {ref}")
        print(f"  hyp: {text}")
    if total_dur > 0:
        print(f"\nOverall RTF (greedy_search): {total_elapsed/total_dur:.3f} "
              f"({total_elapsed:.2f}s decode / {total_dur:.2f}s audio)")

    # --- modified_beam_search + hotwords sanity check ---
    print("\n=== modified_beam_search + hotwords sanity check ===")
    hotwords_file = CLIPS_DIR / "_hotwords.txt"
    # Use a couple of BPE-tokenizable English/Hindi-transliterated words as hotwords;
    # sherpa-onnx tokenizes hotword phrases itself using bpe_vocab.
    hotwords_file.write_text("ITANTRA\nSRAVAANI\n", encoding="utf-8")
    try:
        rec_hw = make_recognizer("modified_beam_search", hotwords_file=str(hotwords_file))
        # decode whichever english clip we have, or first available clip, just to
        # confirm the recognizer loads and runs end-to-end with hotwords active.
        target = None
        for fname, lang, ref in rows:
            if lang == "en":
                target = fname
                break
        if target is None and rows:
            target = rows[0][0]
        if target:
            wav_path = CLIPS_DIR / target
            text, dur, elapsed, rtf = decode_file(rec_hw, wav_path)
            print(f"[hotwords ok] {target}  RTF={rtf:.3f}\n  hyp: {text}")
        else:
            print("[hotwords] no clips available to decode")
        print("modified_beam_search + hotwords_file: WORKS (recognizer loaded & decoded)")
    except Exception as e:
        print(f"modified_beam_search + hotwords_file: FAILED -- {e!r}")
    finally:
        hotwords_file.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
