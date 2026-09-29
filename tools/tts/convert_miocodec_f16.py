#!/usr/bin/env python3
"""Generic F32 -> F16 tensor downcast for a GGUF file, preserving all KV metadata, tensor names
and non-float tensors (int32 index/shape tensors etc.) unchanged. Used to shrink
tts/src/main/cpp/third_party/mio-tts-cpp-produced GGUFs (e.g. miocodec.gguf, 247 tensors, all
currently F32) roughly in half, matching what miocodec-decoder.cpp already casts weights to
on-the-fly for matmuls (see its `ggml_cast(ctx, w, GGML_TYPE_F16)` call sites) - i.e. storing F16
directly removes that runtime cast AND halves the mmap'd/resident size, at F16's usual near-lossless
cost for inference (not F32 training precision).

Standalone: uses only the `gguf` python package (already vendored in tools/tts/.venv), no torch.
"""
from __future__ import annotations

import argparse
import sys

import numpy as np
import gguf


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("infile", help="source GGUF (e.g. models/tts/mio/miocodec.gguf)")
    p.add_argument("-o", "--outfile", required=True, help="destination GGUF")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    reader = gguf.GGUFReader(args.infile)

    arch = None
    for f in reader.fields.values():
        if f.name == "general.architecture":
            arch = bytes(f.parts[f.data[0]]).decode("utf-8")
            break
    print(f"architecture: {arch}")

    writer = gguf.GGUFWriter(args.outfile, arch or "miocodec-dec")

    n_converted = 0
    n_kept = 0
    total_in = 0
    total_out = 0

    # Re-emit every KV pair as-is (skip general.architecture; the writer sets it from arch above).
    for field in reader.fields.values():
        if field.name in ("general.architecture", "GGUF.version", "GGUF.tensor_count", "GGUF.kv_count"):
            continue
        types = field.types
        if not types:
            continue
        main_type = types[0]
        if main_type == gguf.GGUFValueType.ARRAY:
            sub_type = types[-1]
            vals = [field.parts[idx].tolist() if hasattr(field.parts[idx], "tolist") else field.parts[idx]
                    for idx in field.data]
            if sub_type == gguf.GGUFValueType.STRING:
                vals = [bytes(field.parts[idx]).decode("utf-8") for idx in field.data]
            writer.add_array(field.name, vals)
        elif main_type == gguf.GGUFValueType.STRING:
            writer.add_string(field.name, bytes(field.parts[field.data[0]]).decode("utf-8"))
        else:
            val = field.parts[field.data[0]][0]
            writer.add_key_value(field.name, val, main_type)

    for tensor in reader.tensors:
        data = tensor.data
        total_in += data.nbytes
        if tensor.tensor_type == gguf.GGMLQuantizationType.F32 and data.ndim >= 1 and data.size > 1:
            f16 = data.astype(np.float16)
            writer.add_tensor(tensor.name, f16, raw_dtype=gguf.GGMLQuantizationType.F16)
            total_out += f16.nbytes
            n_converted += 1
        else:
            writer.add_tensor(tensor.name, data, raw_dtype=tensor.tensor_type)
            total_out += data.nbytes
            n_kept += 1

    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()

    print(f"converted {n_converted} tensors to F16, kept {n_kept} as-is")
    print(f"tensor bytes: {total_in} -> {total_out} ({100.0 * total_out / total_in:.1f}%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
