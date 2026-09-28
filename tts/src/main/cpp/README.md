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
- Streaming: the first chunk is emitted after just ~13 newly generated audio-code tokens (~0.5s of
  audio; `kFirstChunkCodes`), then every ~40 tokens after that (`kSubsequentChunkCodes`) —
  `mio_tts_token_to_code` via `mio_tts_vocab_map`, `mio_tts_synthesize` on just that token run (not
  cumulative), PCM handed back to Kotlin through `chunkCallback`. Was a flat 50 tokens/chunk
  (~3-5s to first audio measured on a throttled Snapdragon 8 Gen 3); the smaller first chunk cuts
  that to under ~1s while keeping the larger steady-state chunks (fewer `mio_tts_synthesize`/JNI
  round-trips per sentence). This trades a small amount of continuity at chunk boundaries (MioCodec
  has no public incremental/streaming decode entry point in this commit) for bounded latency to
  first audio; if a boundary click/pop is audible in practice, the fix is either a short crossfade
  in Kotlin (`SpeechOutput`/DSP layer) or growing `kSubsequentChunkCodes`.
- Persistent `llama_context`: created once in `nativeInit` (fixed `kCtxSize`/`kBatchSize`, sized
  for one `TtsSegment`'s prompt + `kMaxPredictTokens`) and reused across `nativeSynthesize()` calls
  via `llama_memory_clear()` instead of `llama_init_from_model`/`llama_free` per call — avoids
  KV-cache buffer alloc/dealloc on every sentence. Doubles as the "warm path" ask: since `nativeInit`
  runs from `FallbackTtsEngine.warmUp()` (called at engine construction, see `EngineFactory.kt`),
  the context alloc cost is paid once at app startup, off the first real message's latency.
- Profiling: every `nativeSynthesize()` call logs one JSON line to logcat tag
  `ITANTRA_METRIC_NATIVE` (`{"event":"mio_native_profile", "promptDecodeMs", "tokenGenMs",
  "codecDecodeMs", "callbackMs", "totalMs", "nTokens", "nChunks", "firstChunkMs", "nThreads"}`) —
  splits LM prompt decode / per-token generation (`llama_decode`/`llama_sampler_sample`) from
  MioCodec decode (`mio_tts_synthesize`) from the JNI hop into Kotlin (`callbackMs`, which is a
  proxy for `SpeechOutput`'s resampler running synchronously inside `onChunk`, since that's the
  only work on that side of the hop). Separate from `Metrics`/`ITANTRA_METRIC` (Kotlin-side,
  `app/src/main/kotlin/org/itantra/app/Metrics.kt`) so native profiling never risks breaking that
  tag's JSON-per-line contract for host-side harnesses filtering on it.

## Quantization

`indic-mio-q4.gguf` (Q4_0, ~390MB) is preferred over `indic-mio-q8_0.gguf` (~653MB) when present —
see `MioTtsEngine.kt`'s `lmGguf` selection and `tools/tts/quantize_q4.sh` (converts the original
SPRINGLab/Indic-Mio safetensors via llama.cpp's `convert_hf_to_gguf.py` --outtype f16, then
`llama-quantize ... Q4_0`; falls back to requantizing `indic-mio-q8_0.gguf` directly if the HF
checkpoint is ever unavailable). llama.cpp repacks Q4_0 weight blocks at load time into
dotprod/i8mm-optimised layouts on ARM (`ggml-cpu/arch/arm/repack.cpp`), so this isn't just a
smaller download — it's faster matmuls too. Filename is fixed at exactly `indic-mio-q4.gguf`: the
app's LITE profile (`app/src/main/kotlin/org/itantra/app/Profile.kt`, not owned by this module)
expects that name specifically.

## Build config (`tts/build.gradle.kts`, `CMakeLists.txt`)

- `ndkVersion = "28.2.13676358"` (only NDK installed under `~/Library/Android/sdk/ndk` at time of
  writing besides 27.x)
- CMake `3.22.1` (only version under `~/Library/Android/sdk/cmake`; `cmake_minimum_required` in
  both our `CMakeLists.txt` and upstream's is `3.22.1`/`3.16`, so this is compatible)
- `abiFilters = ["arm64-v8a"]`, arguments: `-DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
  -DCMAKE_BUILD_TYPE=Release -DANDROID_STL=c++_shared` plus the same
  `GGML_CPU_ALL_VARIANTS=OFF -DGGML_CPU_KLEIDIAI=OFF -DGGML_LLAMAFILE=OFF -DGGML_VULKAN=OFF
  -DLLAMA_BUILD_{COMMON,TESTS,EXAMPLES,TOOLS,SERVER}=OFF` flags upstream's sample uses to keep the
  build deterministic on NDK's CMake toolchain.
- `GGML_CPU_ARM_ARCH` is `armv8.2-a+dotprod+fp16+i8mm` (was `armv8.2-a+dotprod+fp16`): `+i8mm` is
  layered on the same armv8.2-a baseline (doesn't raise the required base ISA to 8.6), and all
  i8mm-using code in ggml is compiled behind `#if defined(__ARM_FEATURE_MATMUL_INT8)` *and*
  runtime-dispatched via `ggml_cpu_has_matmul_int8()` before ever being called (see
  `ggml-cpu.c`/`repack.cpp`) — so this one static binary still runs correctly on older
  dotprod-only phones (i8mm path never invoked) while newer i8mm-capable phones (Snapdragon 8 Gen
  3's Cortex-X4/A720 cores both support it) get the faster Q4_0/Q8_0 repack GEMM kernels
  automatically. Deliberately **not** `GGML_CPU_KLEIDIAI=ON` or
  `GGML_CPU_ALL_VARIANTS`+`GGML_BACKEND_DL`: KleidiAI needs a network fetch at CMake configure time
  (external ARM-software/kleidiai tarball) and `GGML_CPU_ALL_VARIANTS` needs `GGML_BACKEND_DL`
  (multiple per-variant `.so`s dynamically loaded at runtime), which would need jniLibs packaging
  changes in `tts/build.gradle.kts`/the app's Gradle config — outside this module's ownership.
  Worth revisiting if a future measurement shows the built-in i8mm repack path isn't enough.

## Runtime files (`MioNative.init`)

`ModelPaths.mioDir` = `{indic-mio-q4.gguf (preferred) | indic-mio-q8_0.gguf, miocodec.gguf,
wavlm.gguf, voices/<lang>.emb.gguf}` — see `core/.../ModelPaths.kt`. The LLM GGUF is
`llama_model_load`ed, `miocodec.gguf` + `wavlm.gguf` go to `mio_tts_init_from_file` (vocoder +
WavLM for reference embeddings — we only use pre-baked `voices/<lang>.emb.gguf` embeddings via
`mio_tts_embedding_load_gguf`, so WavLM is loaded but not exercised at runtime by this app; kept
because `mio_tts_init_from_file` couples vocoder+WavLM loading in one call in this mio-tts-cpp
commit).
