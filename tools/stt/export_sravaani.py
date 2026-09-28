#!/usr/bin/env python3
"""Export SraVaani-1.0 (iAkashPaul/sravaani-indic-asr, MIT) to sherpa-onnx NeMo-TDT
offline-transducer format, matching `ModelPaths.sttDir`:

    stt/sravaani/{encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt, bpe.vocab}

Adapted from sherpa-onnx's own exporter for nvidia/parakeet-tdt (a sibling NeMo TDT model):
    https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py
    https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/nemo/generate_bpe_vocab.py

DOES NOT RUN as part of the build. See README.md in this directory for the venv
setup (NeMo toolkit + torch, ~10 GB — do this on a machine with room, not the dev box)
and how to push the resulting files to the phone with adb.

Usage:
    python export_sravaani.py [--model iAkashPaul/sravaani-indic-asr] [--out ./sravaani]
"""

import argparse
import os
from pathlib import Path
from typing import Dict


def _print_onnx_sizes(directory: Path = Path(".")) -> None:
    """Lists *.onnx file sizes without shelling out (no os.system/subprocess)."""
    for f in sorted(directory.glob("*.onnx")):
        print(f"{f.stat().st_size / 1e6:8.2f} MB  {f.name}")


def add_meta_data(filename: str, meta_data: Dict[str, object]) -> None:
    """Write key/value metadata into an ONNX model in place (mirrors sherpa-onnx's exporter)."""
    import onnx

    model = onnx.load(filename)
    while len(model.metadata_props):
        model.metadata_props.pop()
    for key, value in meta_data.items():
        meta = model.metadata_props.add()
        meta.key = key
        meta.value = str(value)
    onnx.save(model, filename)


def generate_bpe_vocab(asr_model, output_path: str) -> None:
    """Write bpe.vocab (token \\t score per line) for modified_beam_search hotwords.

    Mirrors sherpa-onnx/scripts/nemo/generate_bpe_vocab.py: the scores are the
    SentencePiece model's own merge-priority scores, needed so sherpa-onnx can
    tokenize hotword phrases the same way the model's tokenizer would.
    """
    sp = asr_model.tokenizer.tokenizer
    vocab_size = sp.get_piece_size()
    print(f"[bpe.vocab] vocabulary size: {vocab_size}")
    with open(output_path, "w", encoding="utf-8") as f:
        for token_id in range(vocab_size):
            token = sp.id_to_piece(token_id)
            score = sp.get_score(token_id)
            f.write(f"{token}\t{score}\n")
    print(f"[bpe.vocab] wrote {output_path}")


def export(model_name: str, out_dir: Path, nemo_filename: str) -> None:
    # Imported lazily: these pull in NeMo + torch, which are NOT installed on the
    # dev box on purpose (disk budget). Only import when this script actually runs.
    import nemo.collections.asr as nemo_asr
    import torch
    from onnxruntime.quantization import QuantType, quantize_dynamic

    out_dir.mkdir(parents=True, exist_ok=True)
    out_dir = out_dir.resolve()
    os.chdir(out_dir)

    if model_name.endswith(".nemo") and Path(model_name).is_file():
        # Explicit local .nemo file path.
        nemo_path = Path(model_name).resolve()
    else:
        local_nemo = Path(f"./{model_name.split('/')[-1]}.nemo")
        if local_nemo.is_file():
            nemo_path = local_nemo.resolve()
        elif Path(model_name).is_dir():
            # Local checkout of the HF repo (e.g. `huggingface-cli download ... --local-dir`).
            candidate = Path(model_name) / nemo_filename
            if not candidate.is_file():
                raise FileNotFoundError(f"{candidate} not found in local repo checkout {model_name}")
            nemo_path = candidate.resolve()
        else:
            # Download ONLY the single .nemo checkpoint file from the HF repo -- NOT
            # nemo_asr.models.ASRModel.from_pretrained(), which snapshot_downloads the
            # WHOLE repo (including multi-GB sibling training checkpoints we don't need,
            # e.g. a `.ckpt`). hf_hub_download fetches just one file.
            from huggingface_hub import hf_hub_download

            print(f"Downloading only {nemo_filename!r} from {model_name} ...")
            downloaded = hf_hub_download(repo_id=model_name, filename=nemo_filename)
            nemo_path = Path(downloaded).resolve()

    print(f"Loading NeMo checkpoint from {nemo_path}")
    asr_model = nemo_asr.models.ASRModel.restore_from(restore_path=str(nemo_path))
    asr_model.eval()

    # tokens.txt: one BPE piece per line, id-ordered, blank appended last (sherpa-onnx convention).
    with open("./tokens.txt", "w", encoding="utf-8") as f:
        i = -1
        for i, s in enumerate(asr_model.joint.vocabulary):
            f.write(f"{s} {i}\n")
        f.write(f"<blk> {i + 1}\n")
    print("Saved to tokens.txt")

    print("Generating bpe.vocab for hotword support...")
    generate_bpe_vocab(asr_model, "./bpe.vocab")

    with torch.no_grad():
        asr_model.encoder.export("encoder.onnx")
        asr_model.decoder.export("decoder.onnx")
        asr_model.joint.export("joiner.onnx")
    _print_onnx_sizes()

    normalize_type = asr_model.cfg.preprocessor.normalize
    if normalize_type == "NA":
        normalize_type = ""

    # Metadata sherpa-onnx's NeMo-TDT loader reads at model-load time. `url` must
    # contain "tdt" so sherpa-onnx picks the TDT (duration-aware) decoding path
    # instead of plain RNN-T.
    meta_data = {
        "vocab_size": asr_model.decoder.vocab_size,
        "normalize_type": normalize_type,
        "pred_rnn_layers": asr_model.decoder.pred_rnn_layers,
        "pred_hidden": asr_model.decoder.pred_hidden,
        "subsampling_factor": 8,
        "model_type": "EncDecRNNTBPEModel",
        "version": "2",
        "model_author": "NeMo",
        "url": f"https://huggingface.co/{model_name} (tdt)",
        "comment": "Only the transducer branch is exported",
        "feat_dim": 128,
    }

    for m in ["encoder", "decoder", "joiner"]:
        quantize_dynamic(
            model_input=f"./{m}.onnx",
            model_output=f"./{m}.int8.onnx",
            weight_type=QuantType.QUInt8 if m == "encoder" else QuantType.QInt8,
        )
    _print_onnx_sizes()

    # Only the encoder needs the metadata (that's what sherpa-onnx's OfflineRecognizer reads).
    add_meta_data("encoder.int8.onnx", meta_data)
    add_meta_data("encoder.onnx", meta_data)
    print("meta_data:", meta_data)

    # decoder/joiner ship as plain .onnx too, matching what the C++ loader expects
    # if int8 quantisation of a given sub-model turns out not to help / not to be needed.
    print("Done. Files written to:", out_dir.resolve())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--model",
        default="iAkashPaul/sravaani-indic-asr",
        help="HF model repo (a .nemo checkpoint) or path to a local .nemo file",
    )
    parser.add_argument(
        "--out",
        default="./sravaani",
        help="Output directory (defaults to ./sravaani; copy its contents to "
        "ModelPaths.sttDir = <models>/stt/sravaani on the device)",
    )
    parser.add_argument(
        "--nemo-filename",
        default="SraVaani-nemo-checkpoint.nemo",
        help="Filename of the .nemo checkpoint to fetch from the --model HF repo "
        "(only this single file is downloaded, not the whole repo -- the repo also "
        "ships an unrelated multi-GB training .ckpt we must not pull).",
    )
    args = parser.parse_args()
    export(args.model, Path(args.out), args.nemo_filename)


if __name__ == "__main__":
    main()
