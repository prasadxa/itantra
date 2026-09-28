#!/usr/bin/env bash
# Reproduces models/tts/mio/indic-mio-q4.gguf (Q4_0) from the original SPRINGLab/Indic-Mio
# safetensors checkpoint, using llama.cpp's convert_hf_to_gguf.py + llama-quantize (host build).
# See tts/src/main/cpp/README.md's "Quantization" section for why Q4_0 (llama.cpp repacks Q4_0
# blocks at load time into ARM dotprod/i8mm-optimised layouts — see ggml-cpu/arch/arm/repack.cpp)
# and tts/src/main/kotlin/org/itantra/tts/engine/MioTtsEngine.kt for how the app picks it up
# (preferred over indic-mio-q8_0.gguf whenever present; filename must stay exactly
# "indic-mio-q4.gguf" — the app's LITE profile expects it).
#
# Not run automatically — heavy (downloads ~1.1GB safetensors + a host build of llama.cpp's
# convert/quantize tooling; needs ~4GB free disk at peak, cleaned up at the end). A human runs
# this once; the output (models/tts/mio/indic-mio-q4.gguf, ~390MB) is what actually ships.
#
# Fallback if the HF checkpoint is ever unavailable/renamed: requantize the existing q8_0 GGUF
# directly (llama-quantize dequantizes any source type to F32 before requantizing, so this works
# without the original safetensors, at a small extra quality loss vs quantizing from F16):
#   llama-quantize models/tts/mio/indic-mio-q8_0.gguf models/tts/mio/indic-mio-q4.gguf Q4_0
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LLAMA_SRC="${REPO_ROOT}/tts/src/main/cpp/third_party/mio-tts-cpp/llama.cpp"
MIO_DIR="${REPO_ROOT}/models/tts/mio"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

if [ ! -f "${LLAMA_SRC}/convert_hf_to_gguf.py" ]; then
    echo "Missing ${LLAMA_SRC} — run tts/src/main/cpp/fetch_deps.sh first." >&2
    exit 1
fi

echo "== 1. venv + convert deps (torch/transformers/safetensors/gguf; ~1.5GB, removed after) =="
VENV="${SCRIPT_DIR}/.venv"
if [ ! -d "${VENV}" ]; then python3 -m venv "${VENV}"; fi
source "${VENV}/bin/activate"
pip install --quiet --index-url https://download.pytorch.org/whl/cpu torch
pip install --quiet transformers safetensors sentencepiece gguf protobuf

echo "== 2. download SPRINGLab/Indic-Mio safetensors (~1.1GB) =="
HF_MODEL_DIR="${WORK_DIR}/hf_model"
mkdir -p "${HF_MODEL_DIR}"
for f in config.json generation_config.json tokenizer_config.json tokenizer.json vocab.json \
         merges.txt added_tokens.json special_tokens_map.json chat_template.jinja model.safetensors; do
    curl -sL --fail -o "${HF_MODEL_DIR}/${f}" "https://huggingface.co/SPRINGLab/Indic-Mio/resolve/main/${f}"
done

echo "== 3. convert -> F16 GGUF =="
python3 "${LLAMA_SRC}/convert_hf_to_gguf.py" --outtype f16 \
    --outfile "${WORK_DIR}/indic-mio-f16.gguf" "${HF_MODEL_DIR}"
deactivate

echo "== 4. host build of llama-quantize =="
BUILD_HOST="${WORK_DIR}/build-host"
CMAKE="${REPO_ROOT}/../../../Library/Android/sdk/cmake/3.22.1/bin/cmake"
command -v cmake >/dev/null 2>&1 && CMAKE=cmake
"${CMAKE}" -S "${LLAMA_SRC}" -B "${BUILD_HOST}" -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_COMMON=ON -DGGML_NATIVE=ON -DGGML_METAL=OFF -DGGML_OPENMP=OFF
"${CMAKE}" --build "${BUILD_HOST}" --target llama-quantize -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

echo "== 5. quantize -> Q4_0 =="
mkdir -p "${MIO_DIR}"
"${BUILD_HOST}/bin/llama-quantize" "${WORK_DIR}/indic-mio-f16.gguf" "${MIO_DIR}/indic-mio-q4.gguf" Q4_0

echo "== done: ${MIO_DIR}/indic-mio-q4.gguf =="
ls -la "${MIO_DIR}/indic-mio-q4.gguf"
echo "Push to device: adb push ${MIO_DIR}/indic-mio-q4.gguf /sdcard/Android/data/org.itantra.app/files/models/tts/mio/indic-mio-q4.gguf"
