# :tts native (Indic-Mio via mio-tts-cpp)

`MioNative` (Kotlin, `org.itantra.tts.native.MioNative`) wraps a thin JNI bridge
(`native_bridge.cpp`) around [mmnga/mio-tts-cpp](https://github.com/mmnga/mio-tts-cpp) (MIT),
which itself builds on [llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT) for the
Indic-Mio LLM-driven TTS backbone.

## Pinned commits

| Repo | Commit | Notes |
|---|---|---|
| `mmnga/mio-tts-cpp` | `6180947f713f754e490581149d07fd00b4613b66` | `main`, as of 2026-09-28 |
| `ggml-org/llama.cpp` | `01d8eaa28d57bfc6d06e30072085ed0ef12e06c5` | mio-tts-cpp's `llama.cpp` git submodule at the commit above |

Neither is vendored in git — `tts/.gitignore` excludes `src/main/cpp/third_party/`. Run
`fetch_deps.sh` once (shallow `git init` + `fetch --depth 1` + `checkout`, pinned to the commits
above) to reproduce the checkout locally:

```
tts/src/main/cpp/fetch_deps.sh
```

This does **not** download any model weights (see `tools/tts/fetch_models.sh` for GGUF files);
it only fetches the C++ source needed to compile `libmiotts.so`.

To pick up a newer upstream commit: bump `MIO_TTS_CPP_COMMIT` / `LLAMA_CPP_COMMIT` in
`fetch_deps.sh`, update the table above, `rm -rf third_party`, re-run the script, and re-verify
`../../../build.gradle.kts`'s CMake args still apply (upstream's Android sample CMakeLists.txt —
`examples/android/MioTTSCppDemoAndroid/app/src/main/cpp/CMakeLists.txt` in mio-tts-cpp — is the
reference; ours is adapted from it, not copied, see below).

## What we build

`CMakeLists.txt` in this directory builds, for `arm64-v8a` only:
- `llama` + `ggml`/`ggml-base`/`ggml-cpu` (via `add_subdirectory` on the vendored llama.cpp,
  `GGML_NATIVE=OFF`, OpenMP off, all the extra ggml backends off — CPU-only, matches upstream's
  Android sample flags)
- `mio-tts-lib` (static): `mio-tts-cpp/src/{mio-tts-lib,miocodec-decoder,wavlm-extractor}.cpp`
  (the MioCodec/WavLM/LLM glue — see `mio-tts-lib.h` for the C API)
- `miotts` (shared, `c++_shared` STL): our own `native_bridge.cpp`, the only file we wrote here

We deliberately do **not** build mio-tts-cpp's `mio-tts-server`/`llama-tts-mio` CLI targets or
link `cpp-httplib` — the app only needs the library, driven directly over JNI.

## Differences from upstream's Android sample

Upstream's `examples/android/MioTTSCppDemoAndroid` (minSdk 33, arm64-v8a **and** x86_64,
`mio-tts-mobile-shared.hpp` helper) informed our CMake flags and JNI shape, but:
- We target **minSdk 26** (project minimum) and **arm64-v8a only** (no x86_64 — physical target
  devices only, per `docs/design.md`'s "8 GB arm64 phone"). Their sample's `mio_tts_android_jni.cpp`
  and `mio-tts-mobile-shared.hpp` do not call any API gated above 26 (`llama_*`/`mio_tts_*`/`ggml_*`
  are all plain C, `AAssetManager`/`__android_log_print` are minSdk-1 NDK APIs) — the 33 floor in
  their `build.gradle.kts` looks like an app-level choice (e.g. themed launcher icon / per-app
  language APIs in their demo `MainActivity.kt`, not in the native layer), not a native
  dependency, so lowering to 26 here should be safe. **Unverified end to end on a real 26–28
  device** — flag if `dlopen`/`ggml`'s runtime CPU feature detection misbehaves below API 29.
- We wrote our own `native_bridge.cpp` from scratch against `mio-tts-lib.h`'s C API (init /
  loadVoice / synthesize / release), instead of reusing `mio-tts-mobile-shared.hpp`'s
  `mobile_engine` (which supports multi-voice hot-swap, on-device reference-embedding extraction,
  WAV file I/O, etc. — more than `TtsEngine`'s single-voice-per-call, streamed-PCM contract needs).
  We do follow its patterns for the generation loop (`llama_decode` prompt, then a
  `llama_sampler_sample` per-token loop with `llama_vocab_is_eog` as the stop condition) and the
  prompt template / sampler settings, which match this module's spec:
  prompt `<|im_start|>user\n{text}<|im_end|>\n<|im_start|>assistant\n`, `temp=0.9`, `top_p=0.9`.
- Streaming: every ~50 newly generated audio-code tokens (`mio_tts_token_to_code` via
  `mio_tts_vocab_map`), we call `mio_tts_synthesize` on just that token run (not cumulative) and
  hand the resulting float PCM chunk back to Kotlin through the `chunkCallback`. This trades a
  small amount of continuity at chunk boundaries (MioCodec has no public incremental/streaming
  decode entry point in this commit) for bounded latency to first audio. If a boundary click/pop
  is audible in practice, the fix is either a short crossfade in Kotlin (`SpeechOutput`/DSP layer)
  or growing the chunk size.

## Build config (`tts/build.gradle.kts`)

- `ndkVersion = "28.2.13676358"` (only NDK installed under `~/Library/Android/sdk/ndk` at time of
  writing besides 27.x)
- CMake `3.22.1` (only version under `~/Library/Android/sdk/cmake`; `cmake_minimum_required` in
  both our `CMakeLists.txt` and upstream's is `3.22.1`/`3.16`, so this is compatible)
- `abiFilters = ["arm64-v8a"]`, arguments: `-DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
  -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_shared` plus the same
  `GGML_CPU_ALL_VARIANTS=OFF -DGGML_CPU_KLEIDIAI=OFF -DGGML_LLAMAFILE=OFF -DGGML_VULKAN=OFF
  -DLLAMA_BUILD_{COMMON,TESTS,EXAMPLES,TOOLS,SERVER}=OFF` flags upstream's sample uses to keep the
  build deterministic on NDK's CMake toolchain.

## Runtime files (`MioNative.init`)

`ModelPaths.mioDir` = `{indic-mio-q8_0.gguf, miocodec.gguf, wavlm.gguf, voices/<lang>.emb.gguf}` —
see `core/.../ModelPaths.kt`. `indic-mio-q8_0.gguf` is the LLM (`llama_model_load`), `miocodec.gguf`
+ `wavlm.gguf` go to `mio_tts_init_from_file` (vocoder + WavLM for reference embeddings — we only
use pre-baked `voices/<lang>.emb.gguf` embeddings via `mio_tts_embedding_load_gguf`, so WavLM is
loaded but not exercised at runtime by this app; kept because `mio_tts_init_from_file` couples
vocoder+WavLM loading in one call in this mio-tts-cpp commit).
