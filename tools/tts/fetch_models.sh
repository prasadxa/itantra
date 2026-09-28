#!/usr/bin/env bash
# Downloads the :tts model weights into ./models (matching org.itantra.core.ModelPaths' layout)
# and adb-pushes them to the app's external files dir on a connected device/emulator.
#
#   tts/mio/{indic-mio-q8_0.gguf, miocodec.gguf, wavlm.gguf, voices/<lang>.emb.gguf}
#   tts/vits-rasa/{model.onnx, tokens.txt}
#
# NOT run automatically (disk on this dev machine is tight and these are large files) — a human
# runs this on a machine with room, or CI does with model caching.
#
# Sources:
#   - Indic-Mio LLM + MioCodec/WavLM GGUFs: mmnga-o/miotts-cpp-gguf on the Hugging Face Hub
#     (repo layout has historically varied — MioTTS-*.gguf / indic-mio-*.gguf for the LLM,
#     miocodec*.gguf, wavlm*.gguf; this script tries the documented filenames and falls back to
#     scanning the repo file list). indic-mio-q8_0.gguf is the q8_0-quantized checkpoint per
#     tts/src/main/cpp/README.md.
#   - bytepass0/indic-mio-q8-gguf: an alternate/mirrored source for the q8_0 LLM GGUF specifically
#     (tried first for indic-mio-q8_0.gguf; falls back to mmnga-o/miotts-cpp-gguf).
#   - VITS: MatiasLin/sherpa-onnx-vits-rasa-13 (model.onnx + tokens.txt).
#   - Voice embeddings (tts/mio/voices/<lang>.emb.gguf) are NOT published anywhere public as far
#     as we found — see tools/tts/VOICES.md to build them yourselves from ~10s CC-BY reference
#     clips (e.g. AI4Bharat Rasa/IndicTTS) with mio-tts-cpp's own export tooling.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODELS_DIR="${MODELS_DIR:-${SCRIPT_DIR}/../../models}"
APP_ID="org.itantra.app"
DEVICE_MODELS_DIR="/sdcard/Android/data/${APP_ID}/files/models"

MIO_DIR="${MODELS_DIR}/tts/mio"
VITS_DIR="${MODELS_DIR}/tts/vits-rasa"

hf_url() { # hf_url <repo> <path-in-repo>
    echo "https://huggingface.co/$1/resolve/main/$2?download=true"
}

fetch() { # fetch <url> <dest>
    local url="$1" dest="$2"
    if [ -f "${dest}" ]; then
        echo "[fetch_models] ${dest} already exists, skipping"
        return
    fi
    echo "[fetch_models] ${url} -> ${dest}"
    mkdir -p "$(dirname "${dest}")"
    curl -L --fail --progress-bar -o "${dest}.part" "${url}"
    mv "${dest}.part" "${dest}"
}

fetch_first() { # fetch_first <dest> <url1> [url2 ...] — first URL that downloads OK wins.
    local dest="$1"; shift
    if [ -f "${dest}" ]; then
        echo "[fetch_models] ${dest} already exists, skipping"
        return
    fi
    for url in "$@"; do
        if curl -L --fail --progress-bar -o "${dest}.part" "${url}"; then
            mv "${dest}.part" "${dest}"
            return
        fi
        rm -f "${dest}.part"
    done
    echo "[fetch_models] WARNING: could not fetch ${dest} from any of: $*" >&2
}

mkdir -p "${MIO_DIR}/voices" "${VITS_DIR}"

echo "== Indic-Mio (LLM + MioCodec + WavLM) =="
# bytepass0/indic-mio-q8-gguf's actual filename in-repo is "indic-mio-q8.gguf" (no underscore
# before "0"); we still save it locally as indic-mio-q8_0.gguf per ModelPaths.kt's layout.
fetch_first "${MIO_DIR}/indic-mio-q8_0.gguf" \
    "$(hf_url bytepass0/indic-mio-q8-gguf indic-mio-q8.gguf)"
# miocodec.gguf in mmnga-o/miotts-cpp-gguf is byte-identical to that repo's miocodec-24khz.gguf
# (same LFS oid) i.e. Aratako/MioCodec-25Hz-24kHz, matching Indic-Mio's base codec. Do NOT use
# miocodec-25hz-44k-v2.gguf from that repo — wrong sample rate for this LLM.
fetch_first "${MIO_DIR}/miocodec.gguf" \
    "$(hf_url mmnga-o/miotts-cpp-gguf miocodec.gguf)"
# WavLM is NOT hosted in mmnga-o/miotts-cpp-gguf (that repo has no wavlm* file) — it's a
# separate repo.
fetch_first "${MIO_DIR}/wavlm.gguf" \
    "$(hf_url mmnga-o/wavlm-base-plus-gguf wavlm_base_plus_2l_f32.gguf)"

echo "== VITS fallback (bn, kn, ml, mr, ta, te) =="
fetch "$(hf_url MatiasLin/sherpa-onnx-vits-rasa-13 model.onnx)" "${VITS_DIR}/model.onnx"
fetch "$(hf_url MatiasLin/sherpa-onnx-vits-rasa-13 tokens.txt)" "${VITS_DIR}/tokens.txt"

echo "== Voice embeddings =="
echo "tts/mio/voices/<lang>.emb.gguf are NOT auto-fetched — see tools/tts/VOICES.md to build them."
echo "MioTtsEngine.supports(lang) is false for any language missing its <lang>.emb.gguf; VitsTtsEngine covers bn/kn/ml/mr/ta/te in the meantime."

if command -v adb >/dev/null 2>&1 && adb get-state >/dev/null 2>&1; then
    echo "== adb push -> ${DEVICE_MODELS_DIR} =="
    adb shell "mkdir -p ${DEVICE_MODELS_DIR}/tts/mio/voices ${DEVICE_MODELS_DIR}/tts/vits-rasa"
    adb push "${MIO_DIR}/." "${DEVICE_MODELS_DIR}/tts/mio/"
    adb push "${VITS_DIR}/." "${DEVICE_MODELS_DIR}/tts/vits-rasa/"
    echo "[fetch_models] pushed to device."
else
    echo "[fetch_models] no adb device connected; skipping push. Re-run this script (or just the"
    echo "  adb push commands above) once a device/emulator is attached."
fi
