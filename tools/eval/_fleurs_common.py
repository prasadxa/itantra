"""Shared FLEURS streaming helpers for tools/eval/*.py.

FLEURS lives at https://huggingface.co/datasets/google/fleurs (resolve/main/data/<cfg>/...).
We never use the `datasets` library's full-download path: we stream the raw TSV (small, a few
hundred KB) and the audio tar.gz (large) with plain HTTP + `tarfile` in "r|gz" streaming mode,
so a run can stop early without pulling a whole archive.
"""

from __future__ import annotations

import csv
import io
import tarfile
import urllib.request
from dataclasses import dataclass
from typing import Iterator, Optional

FLEURS_BASE = "https://huggingface.co/datasets/google/fleurs/resolve/main/data"
USER_AGENT = "itantra-eval/1.0"

# lang code (ISO 639-1, matches core/Lang.kt) -> FLEURS config name
LANG_TO_FLEURS_CFG = {
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

ALL_LANGS = list(LANG_TO_FLEURS_CFG.keys())


@dataclass
class FleursRow:
    row_id: str
    filename: str  # e.g. "7251883826251297781.wav"
    raw_transcription: str
    transcription: str
    num_samples: int
    gender: str

    @property
    def duration_sec(self) -> float:
        # FLEURS audio is 16 kHz mono PCM16.
        return self.num_samples / 16000.0


def _http_get(url: str, timeout: float = 30.0):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    return urllib.request.urlopen(req, timeout=timeout)


def fetch_test_tsv(lang: str) -> list[FleursRow]:
    """Downloads (fully - it's small, ~100-500 KB) data/<cfg>/test.tsv for `lang`."""
    cfg = LANG_TO_FLEURS_CFG[lang]
    url = f"{FLEURS_BASE}/{cfg}/test.tsv"
    with _http_get(url) as resp:
        raw = resp.read().decode("utf-8")
    rows: list[FleursRow] = []
    reader = csv.reader(io.StringIO(raw), delimiter="\t", quoting=csv.QUOTE_NONE)
    for fields in reader:
        if len(fields) < 7:
            continue
        row_id, filename, raw_transcription, transcription, _phonemes, num_samples, gender = fields[:7]
        try:
            n = int(num_samples)
        except ValueError:
            continue
        rows.append(FleursRow(row_id, filename, raw_transcription, transcription, n, gender))
    return rows


def stream_test_audio(lang: str) -> Iterator[tuple[str, bytes]]:
    """Streams data/<cfg>/audio/test.tar.gz member-by-member, yielding (filename, wav_bytes).

    Caller controls how much of the stream to consume; stop iterating (e.g. `break`) to close
    the underlying connection early instead of downloading the rest of the archive.
    """
    cfg = LANG_TO_FLEURS_CFG[lang]
    url = f"{FLEURS_BASE}/{cfg}/audio/test.tar.gz"
    resp = _http_get(url, timeout=60.0)
    try:
        tf = tarfile.open(fileobj=resp, mode="r|gz")
        try:
            for member in tf:
                if not member.isfile() or not member.name.endswith(".wav"):
                    continue
                fh = tf.extractfile(member)
                if fh is None:
                    continue
                data = fh.read()
                filename = member.name.rsplit("/", 1)[-1]
                yield filename, data
        finally:
            tf.close()
    finally:
        resp.close()
