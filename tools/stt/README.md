# `:stt` model prep

Exports/fetches for `ModelPaths`:

```
vad/silero_vad.onnx
stt/sravaani/{encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt, bpe.vocab}
```

Do this on a machine with disk to spare (NeMo + torch pull in several GB) — **not** the
Android dev box, which is intentionally kept free of NeMo/torch/model weights.

## 1. Silero VAD (no export needed, just download)

```bash
curl -L -o silero_vad.onnx \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
```

## 2. SraVaani-1.0 (NeMo TDT -> sherpa-onnx int8)

```bash
python3 -m venv .venv-sravaani
source .venv-sravaani/bin/activate
pip install -U pip
# NeMo ASR + export deps. Pin to whatever NeMo release the SraVaani checkpoint was
# trained against if `from_pretrained`/`restore_from` complains about a version mismatch.
pip install "nemo_toolkit[asr]" torch onnx onnxruntime huggingface_hub sentencepiece

python export_sravaani.py \
  --model iAkashPaul/sravaani-indic-asr \
  --out ./sravaani
```

This adapts sherpa-onnx's own NeMo-TDT exporter
(`scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py` +
`scripts/nemo/generate_bpe_vocab.py`, both from the k2-fsa/sherpa-onnx repo) for the
SraVaani checkpoint: it pulls the `.nemo` file from the `iAkashPaul/sravaani-indic-asr`
HF repo (MIT), exports encoder/decoder/joiner to ONNX, dynamically int8-quantizes them
with onnxruntime, writes `tokens.txt`, and writes `bpe.vocab` (BPE piece + score per
line) for `modified_beam_search` hotword boosting. The encoder's ONNX metadata gets
`feat_dim=128`, `subsampling_factor=8`, `vocab_size`, `normalize_type`,
`pred_rnn_layers`, `pred_hidden`, and a `url` containing `"tdt"` (sherpa-onnx's
NeMo-TDT loader keys off model metadata, not the filename, to pick the TDT duration-head
decoding path over plain RNN-T).

Expect `./sravaani/` to contain `encoder.onnx`, `encoder.int8.onnx`, `decoder.onnx`,
`decoder.int8.onnx`, `joiner.onnx`, `joiner.int8.onnx`, `tokens.txt`, `bpe.vocab`. Only
the `*.int8.onnx` + `tokens.txt` + `bpe.vocab` files are needed on-device; the
full-precision `.onnx` files can be discarded after a sanity check.

If `iAkashPaul/sravaani-indic-asr` turns out not to expose a bare `.nemo` file (HF repos
sometimes wrap the checkpoint), download it by hand first and pass a local path:

```bash
huggingface-cli download iAkashPaul/sravaani-indic-asr --local-dir ./sravaani-src
python export_sravaani.py --model ./sravaani-src/<checkpoint>.nemo --out ./sravaani
```

## 3. Push to the device

```bash
adb shell mkdir -p /sdcard/Android/data/org.itantra.app/files/models/vad
adb shell mkdir -p /sdcard/Android/data/org.itantra.app/files/models/stt/sravaani

adb push silero_vad.onnx \
  /sdcard/Android/data/org.itantra.app/files/models/vad/silero_vad.onnx

adb push sravaani/encoder.int8.onnx sravaani/decoder.int8.onnx sravaani/joiner.int8.onnx \
         sravaani/tokens.txt sravaani/bpe.vocab \
  /sdcard/Android/data/org.itantra.app/files/models/stt/sravaani/
```

Verify against `ModelPaths` (`core/src/main/kotlin/org/itantra/core/ModelPaths.kt`):

```
<externalFilesDir>/models/vad/silero_vad.onnx
<externalFilesDir>/models/stt/sravaani/encoder.int8.onnx
<externalFilesDir>/models/stt/sravaani/decoder.int8.onnx
<externalFilesDir>/models/stt/sravaani/joiner.int8.onnx
<externalFilesDir>/models/stt/sravaani/tokens.txt
<externalFilesDir>/models/stt/sravaani/bpe.vocab
```

`adb shell run-as org.itantra.app ls -la /sdcard/Android/data/org.itantra.app/files/models`
(or just `ls` the `/sdcard/...` path directly, since `externalFilesDir` there needs no
`run-as`) to confirm before launching the app.

## Not run by this task

Neither script here has been executed as part of this change — no NeMo/torch install,
no model download, no `adb push`. Do that on your own machine per the steps above.
