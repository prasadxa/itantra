#!/usr/bin/env bash
# Fetches the native third-party sources needed to build MioNative (Indic-Mio TTS).
#
# Vendors, as shallow clones pinned to exact commits (see README.md in this directory):
#   third_party/mio-tts-cpp  <- https://github.com/mmnga/mio-tts-cpp
#   third_party/mio-tts-cpp/llama.cpp <- https://github.com/ggml-org/llama.cpp (mio-tts-cpp's submodule)
#
# Nothing here is committed to git (see tts/.gitignore) — every contributor (and CI) runs this
# script once before building :tts's native code. It does NOT download any GGUF model weights;
# see tools/tts/fetch_models.sh for that.
set -euo pipefail

MIO_TTS_CPP_COMMIT="6180947f713f754e490581149d07fd00b4613b66"
LLAMA_CPP_COMMIT="01d8eaa28d57bfc6d06e30072085ed0ef12e06c5"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
THIRD_PARTY_DIR="${SCRIPT_DIR}/third_party"
MIO_DIR="${THIRD_PARTY_DIR}/mio-tts-cpp"

clone_pinned() {
  local url="$1" dir="$2" commit="$3"
  if [ -d "${dir}/.git" ]; then
    echo "[fetch_deps] ${dir} already exists, skipping (rm -rf it to re-fetch)"
    return
  fi
  echo "[fetch_deps] cloning ${url} @ ${commit} -> ${dir}"
  mkdir -p "${dir}"
  git -C "${dir}" init -q
  git -C "${dir}" remote add origin "${url}"
  git -C "${dir}" fetch --depth 1 origin "${commit}"
  git -C "${dir}" checkout -q FETCH_HEAD
}

mkdir -p "${THIRD_PARTY_DIR}"
clone_pinned "https://github.com/mmnga/mio-tts-cpp.git" "${MIO_DIR}" "${MIO_TTS_CPP_COMMIT}"
clone_pinned "https://github.com/ggml-org/llama.cpp.git" "${MIO_DIR}/llama.cpp" "${LLAMA_CPP_COMMIT}"

echo "[fetch_deps] done. mio-tts-cpp @ $(git -C "${MIO_DIR}" rev-parse --short HEAD), llama.cpp @ $(git -C "${MIO_DIR}/llama.cpp" rev-parse --short HEAD)"
