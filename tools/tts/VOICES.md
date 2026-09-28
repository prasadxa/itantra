# Making `tts/mio/voices/<lang>.emb.gguf` voice embeddings

## Sources actually used for this build (2026-09-28)

All 10 embeddings (`hi gu mr kn ml ta te or bn en`) were built from
[`google/fleurs`](https://huggingface.co/datasets/google/fleurs) (CC-BY-4.0, ungated) `validation`
split clips — `hi_in gu_in mr_in kn_in ml_in ta_in te_in or_in bn_in en_us` respectively. Order of
preference was: (a) any preset/sample voice shipped in the `SPRINGLab/Indic-Mio` repo itself, (b)
`ai4bharat/rasa` (CC-BY-4.0 but gated on HF — no `~/.cache/huggingface/token` was present on the
build machine, so this was unavailable), (c) `google/fleurs`.

(a) was checked first: `SPRINGLab/Indic-Mio`'s `samples/` dir only has 4 widget demo clips
(sample1=hi/en code-switch, sample2=en, sample3=gu with `<disgust>`, sample4=ta with `<surprise>`)
— i.e. *model-generated outputs*, not source reference recordings, and only covering 2-4 of the 10
languages. For consistency across all 10 languages we used fleurs uniformly instead.

Per language: downloaded that language's `validation-00000-of-00001.parquet` (~160-390 MB each —
FLEURS ships one giant row group per split with no page index, so a single row can't be read via
HTTP range request without pulling the whole `audio` column chunk; this was confirmed empirically
before falling back to a plain full-file download), picked the first row with `gender == female`
and `7.5s <= duration <= 14s` (fell back to the closest-to-10s row if no such row existed),
trimmed to <=10 s, wrote 16 kHz mono PCM16 WAV, then deleted the parquet. Script:
`extract_ref.py` (ad hoc, not checked in — see git history/scratch if you need to re-run it; the
logic is short enough to reproduce: `pyarrow.parquet.read_table(columns=[...])`, filter by
`gender`/`num_samples`, decode the picked row's `audio.bytes` with `soundfile`).

This is **not** committed anywhere as a script in `tools/tts/` because it's a one-off data-sourcing
step, not part of the repeatable build; re-run it (or substitute AI4Bharat Rasa/IndicTTS clips per
the original recipe below) if you need to regenerate voices, e.g. with an HF token for (b) instead.

`MioTtsEngine.supports(lang)` is only true once `ModelPaths.mioVoice(lang)` — i.e.
`<models>/tts/mio/voices/<lang>.emb.gguf` — exists (`FallbackTtsEngine` falls back to VITS for
bn/kn/ml/mr/ta/te in the meantime; other languages have no VITS speaker, see `VitsTtsEngine`, so
they need a Mio voice to work at all).

An embedding is produced from ~10 s of clean reference speech in the target language, using
mio-tts-cpp's own `llama-tts-mio` CLI in "embedding only" mode — no separate Python conversion
step is needed for this path (that's only for importing embeddings from another training
pipeline's `.pt`/`.npz` files; see `third_party/mio-tts-cpp/scripts/README.md` if you have one of
those instead).

## 1. Get a reference clip

~10 s (the CLI's `--tts-max-reference-seconds` defaults to 20 s; 10 is plenty and keeps the
WavLM extraction pass fast), single speaker, minimal background noise/reverb, mono, any common
sample rate (WavLM resamples internally). CC-BY sources to start from:

- [AI4Bharat IndicTTS](https://www.iitm.ac.in/donlab/indictts/database) — per-language, CC-BY-4.0.
- [AI4Bharat Rasa](https://huggingface.co/datasets/ai4bharat/rasa) (gated on HF; request access) —
  the same dataset `vits_rasa_13` (the VITS fallback model) was trained on, so picking a Rasa clip
  for a language also gives Mio a voice with a similar "feel" to the VITS fallback for that
  language.

Trim to ~10 s of continuous speech (e.g. `ffmpeg -i in.wav -t 10 -ac 1 ref_<lang>.wav`) and check
the license/attribution terms of whichever clip you pick before shipping it in a build.

## 2. Build `llama-tts-mio` (host build, NOT the Android one)

This is a one-off host-side tool run on your dev machine, unrelated to `tts/build.gradle.kts`'s
Android/CMake build (that one only builds `libmiotts.so`, not the CLI). From the repo root:

```bash
tts/src/main/cpp/fetch_deps.sh    # if not already done
cd tts/src/main/cpp/third_party/mio-tts-cpp
cmake -B build -DLLAMA_CPP_SOURCE_DIR="$(pwd)/llama.cpp" -DCMAKE_BUILD_TYPE=Release
cmake --build build --target llama-tts-mio -j
```

(This uses the *root* `CMakeLists.txt` at `third_party/mio-tts-cpp/CMakeLists.txt`, which builds
plain host binaries — not the Android-specific one at `tts/src/main/cpp/CMakeLists.txt`.)

## 3. Extract the embedding

```bash
./build/llama-tts-mio \
  --model-vocoder /path/to/miocodec.gguf \
  --tts-wavlm-model /path/to/wavlm.gguf \
  --tts-reference-audio ref_<lang>.wav \
  --tts-max-reference-seconds 10 \
  --tts-mio-embedding-only \
  --tts-mio-embedding-out models/tts/mio/voices/<lang>.emb.gguf
```

`<lang>` is the `Lang.code` from `core/.../Lang.kt` (`hi`, `gu`, `mr`, `kn`, `ml`, `ta`, `te`,
`or`, `bn`, `en`). Repeat per language you want Mio to cover — the LLM/codec/WavLM weights are
shared across all 10; only this embedding is per-language.

## 4. Install it

Either drop it straight into `tools/tts/models/tts/mio/voices/<lang>.emb.gguf` before running
`tools/tts/fetch_models.sh` (it won't overwrite an existing file), or `adb push` it directly:

```bash
adb push <lang>.emb.gguf /sdcard/Android/data/org.itantra.app/files/models/tts/mio/voices/<lang>.emb.gguf
```

## Bulk conversion of pre-existing embeddings

If you already have speaker embeddings as `.pt`/`.npz` (e.g. exported from another TTS training
run) rather than raw reference audio, convert them directly instead of steps 1–3:

```bash
cd third_party/mio-tts-cpp
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python scripts/convert_preset_embedding_to_gguf.py <embedding.pt> -o <lang>.emb.gguf
```
