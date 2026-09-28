"""Loads the same SraVaani-1.0 TDT model the phone uses (sherpa-onnx NeMo transducer) for
Mac-side transcription, used by tts_intelligibility.py to score ASR-based TTS intelligibility.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

DEFAULT_MODEL_DIR = Path(__file__).resolve().parents[2] / "models" / "stt" / "sravaani"


def load_recognizer(model_dir: Path = DEFAULT_MODEL_DIR, num_threads: int = 4) -> "sherpa_onnx.OfflineRecognizer":
    d = str(model_dir)
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{d}/encoder.int8.onnx",
        decoder=f"{d}/decoder.int8.onnx",
        joiner=f"{d}/joiner.int8.onnx",
        tokens=f"{d}/tokens.txt",
        num_threads=num_threads,
        sample_rate=16000,
        feature_dim=128,
        model_type="nemo_transducer",
    )


def transcribe(rec: "sherpa_onnx.OfflineRecognizer", wav_path: str) -> str:
    samples, sr = sf.read(wav_path, dtype="float32", always_2d=False)
    if samples.ndim > 1:
        samples = samples.mean(axis=1)
    stream = rec.create_stream()
    stream.accept_waveform(sr, samples.astype(np.float32))
    rec.decode_stream(stream)
    return stream.result.text
