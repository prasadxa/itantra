// Thin JNI bridge between org.itantra.tts.mio.MioNative (Kotlin) and mio-tts-cpp's C API
// (third_party/mio-tts-cpp/src/mio-tts-lib.h, built on llama.cpp). See README.md in this
// directory for what is/isn't reused from mio-tts-cpp's own Android sample.
//
// One handle == one loaded (LLM + MioCodec + WavLM) triple with a single "current voice"
// embedding, matching MioNative's single-native-object-per-process shape. Not thread-safe:
// MioTtsEngine (Kotlin) must serialize calls onto one worker thread, same as TtsEngine's
// documented contract.

#include <jni.h>
#include <android/log.h>

#include "llama.h"
#include "mio-tts-lib.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char *TAG = "MioNative";
constexpr int32_t kCodesPerChunk = 50; // ~50 LLM tokens per streamed audio chunk, per spec.
constexpr int32_t kMaxPredictTokens = 1024; // safety cap; one TtsSegment is at most a sentence.

std::once_flag g_backend_init_once;

struct MioHandle {
    llama_model *llm_model = nullptr;
    mio_tts_context *mio = nullptr;
    mio_tts_vocab_map *vmap = nullptr;
    int32_t n_threads = 2;

    std::vector<float> voice_embedding;
    bool has_voice = false;

    std::string last_error;
    std::mutex mutex;

    ~MioHandle() {
        if (vmap != nullptr) mio_tts_vocab_map_free(vmap);
        if (mio != nullptr) mio_tts_free(mio);
        if (llm_model != nullptr) llama_model_free(llm_model);
    }
};

std::string jstring_to_std(JNIEnv *env, jstring value) {
    if (value == nullptr) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

MioHandle *to_handle(jlong h) { return reinterpret_cast<MioHandle *>(h); }

void log_error(MioHandle *h, const std::string &msg) {
    if (h != nullptr) h->last_error = msg;
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", msg.c_str());
}

bool tokenize_prompt(
        const llama_vocab *vocab,
        const std::string &text,
        std::vector<llama_token> &out_tokens,
        std::string &err) {
    // Spec'd prompt template.
    const std::string prompt = "<|im_start|>user\n" + text + "<|im_end|>\n<|im_start|>assistant\n";

    const int n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, /*add_special=*/false, /*parse_special=*/true);
    if (n >= 0) {
        err = "unexpected llama_tokenize size probe result";
        return false;
    }
    const int needed = -n;
    if (needed <= 0) {
        err = "empty tokenization";
        return false;
    }
    out_tokens.resize((size_t) needed);
    const int got = llama_tokenize(
            vocab, prompt.c_str(), (int32_t) prompt.size(),
            out_tokens.data(), (int32_t) out_tokens.size(),
            /*add_special=*/false, /*parse_special=*/true);
    if (got < 0) {
        err = "llama_tokenize failed";
        return false;
    }
    out_tokens.resize((size_t) got);
    return !out_tokens.empty();
}

// Synthesizes one chunk of MioCodec audio codes with the handle's current voice and invokes the
// Kotlin (FloatArray) -> Unit lambda `on_chunk` with the resulting PCM. Returns false + sets err
// on failure; codes.empty() is a no-op success.
bool synthesize_and_emit_chunk(
        JNIEnv *env,
        MioHandle *h,
        const std::vector<int32_t> &codes,
        jobject on_chunk,
        jmethodID invoke_method,
        std::string &err) {
    if (codes.empty()) return true;

    mio_tts_params params = mio_tts_default_params();
    params.n_threads = h->n_threads;

    char c_err[512] = {0};
    if (!mio_tts_reserve_workspace(h->mio, (int32_t) codes.size(), c_err, sizeof(c_err))) {
        err = std::string("mio_tts_reserve_workspace failed: ") + c_err;
        return false;
    }

    float *audio = nullptr;
    size_t n_audio = 0;
    int32_t sample_rate = 0;
    const bool ok = mio_tts_synthesize(
            h->mio,
            codes.data(), codes.size(),
            h->voice_embedding.data(), h->voice_embedding.size(),
            params,
            &audio, &n_audio, &sample_rate,
            c_err, sizeof(c_err));
    if (!ok) {
        err = std::string("mio_tts_synthesize failed: ") + c_err;
        return false;
    }

    if (n_audio > 0 && audio != nullptr) {
        jfloatArray arr = env->NewFloatArray((jsize) n_audio);
        if (arr == nullptr) {
            mio_tts_audio_free(audio);
            err = "OOM allocating jfloatArray";
            return false;
        }
        env->SetFloatArrayRegion(arr, 0, (jsize) n_audio, audio);
        env->CallObjectMethod(on_chunk, invoke_method, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) {
            mio_tts_audio_free(audio);
            err = "onChunk callback threw";
            return false;
        }
    }
    mio_tts_audio_free(audio);
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_itantra_tts_mio_MioNative_nativeInit(
        JNIEnv *env, jobject /*thiz*/,
        jstring jLmGguf, jstring jCodecGguf, jstring jWavlmGguf, jint jThreads) {
    std::call_once(g_backend_init_once, [] { llama_backend_init(); });

    const std::string lm_path = jstring_to_std(env, jLmGguf);
    const std::string codec_path = jstring_to_std(env, jCodecGguf);
    const std::string wavlm_path = jstring_to_std(env, jWavlmGguf);

    auto *h = new (std::nothrow) MioHandle();
    if (h == nullptr) return 0;
    h->n_threads = jThreads > 0 ? (int32_t) jThreads : 2;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only on mobile.
    h->llm_model = llama_model_load_from_file(lm_path.c_str(), mparams);
    if (h->llm_model == nullptr) {
        log_error(h, "llama_model_load_from_file failed: " + lm_path);
        delete h;
        return 0;
    }

    char c_err[512] = {0};
    const llama_vocab *vocab = llama_model_get_vocab(h->llm_model);
    h->vmap = mio_tts_vocab_map_init(vocab, c_err, sizeof(c_err));
    if (h->vmap == nullptr) {
        log_error(h, std::string("mio_tts_vocab_map_init failed: ") + c_err);
        delete h;
        return 0;
    }

    h->mio = mio_tts_init_from_file(codec_path.c_str(), wavlm_path.c_str(), c_err, sizeof(c_err));
    if (h->mio == nullptr) {
        log_error(h, std::string("mio_tts_init_from_file failed: ") + c_err);
        delete h;
        return 0;
    }

    // Mobile policy (matches mio-tts-cpp's own Android sample): force MioCodec/WavLM to CPU for
    // stability across devices/backends.
    if (!mio_tts_context_set_backend_device(h->mio, "CPU", c_err, sizeof(c_err))) {
        log_error(h, std::string("mio_tts_context_set_backend_device(CPU) failed: ") + c_err);
        delete h;
        return 0;
    }

    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT jint JNICALL
Java_org_itantra_tts_mio_MioNative_nativeSampleRate(JNIEnv *, jobject, jlong jHandle) {
    MioHandle *h = to_handle(jHandle);
    if (h == nullptr || h->mio == nullptr) return 24000;
    const int32_t sr = mio_tts_context_sample_rate(h->mio);
    return sr > 0 ? sr : 24000;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_itantra_tts_mio_MioNative_nativeLoadVoice(JNIEnv *env, jobject, jlong jHandle, jstring jEmbGguf) {
    MioHandle *h = to_handle(jHandle);
    if (h == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->mutex);

    const std::string emb_path = jstring_to_std(env, jEmbGguf);
    float *embedding = nullptr;
    size_t n_embedding = 0;
    char c_err[512] = {0};
    if (!mio_tts_embedding_load_gguf(emb_path.c_str(), &embedding, &n_embedding, c_err, sizeof(c_err))) {
        log_error(h, std::string("mio_tts_embedding_load_gguf failed: ") + c_err);
        return JNI_FALSE;
    }

    h->voice_embedding.assign(embedding, embedding + n_embedding);
    h->has_voice = true;
    mio_tts_embedding_free(embedding);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_itantra_tts_mio_MioNative_nativeSynthesize(
        JNIEnv *env, jobject, jlong jHandle, jstring jText, jobject jOnChunk) {
    MioHandle *h = to_handle(jHandle);
    if (h == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(h->mutex);

    if (!h->has_voice) {
        log_error(h, "no voice loaded; call loadVoice() first");
        return JNI_FALSE;
    }

    jclass fn1_class = env->GetObjectClass(jOnChunk);
    jmethodID invoke_method = env->GetMethodID(fn1_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(fn1_class);
    if (invoke_method == nullptr) {
        log_error(h, "onChunk lambda has no Function1.invoke(Object)Object");
        return JNI_FALSE;
    }

    const std::string text = jstring_to_std(env, jText);
    const llama_vocab *vocab = llama_model_get_vocab(h->llm_model);

    std::vector<llama_token> prompt_tokens;
    std::string err;
    if (!tokenize_prompt(vocab, text, prompt_tokens, err)) {
        log_error(h, err);
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) (prompt_tokens.size() + kMaxPredictTokens + 32);
    cparams.n_batch = (uint32_t) std::max<int32_t>((int32_t) prompt_tokens.size(), 512);
    cparams.n_threads = h->n_threads;
    cparams.n_threads_batch = h->n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context *ctx = llama_init_from_model(h->llm_model, cparams);
    if (ctx == nullptr) {
        log_error(h, "llama_init_from_model failed");
        return JNI_FALSE;
    }

    // temperature 0.9, top_p 0.9 per spec.
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.9f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));

    bool ok = true;
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t) prompt_tokens.size());
    if (llama_decode(ctx, batch) != 0) {
        log_error(h, "llama_decode failed on prompt");
        ok = false;
    }

    std::vector<int32_t> chunk_codes;
    chunk_codes.reserve(kCodesPerChunk);

    for (int32_t i = 0; ok && i < kMaxPredictTokens; ++i) {
        llama_token tok = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, tok);

        if (llama_vocab_is_eog(vocab, tok)) break;

        int32_t code = 0;
        if (mio_tts_token_to_code(h->vmap, tok, &code)) {
            chunk_codes.push_back(code);
            if ((int32_t) chunk_codes.size() >= kCodesPerChunk) {
                if (!synthesize_and_emit_chunk(env, h, chunk_codes, jOnChunk, invoke_method, err)) {
                    log_error(h, err);
                    ok = false;
                    break;
                }
                chunk_codes.clear();
            }
        }
        // Non-audio (text) tokens are simply not appended to codes, matching
        // mio_tts_tokens_to_codes' filtering behaviour.

        llama_batch next = llama_batch_get_one(&tok, 1);
        if (llama_decode(ctx, next) != 0) {
            log_error(h, "llama_decode failed during generation");
            ok = false;
            break;
        }
    }

    if (ok && !chunk_codes.empty()) {
        ok = synthesize_and_emit_chunk(env, h, chunk_codes, jOnChunk, invoke_method, err);
        if (!ok) log_error(h, err);
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_itantra_tts_mio_MioNative_nativeLastError(JNIEnv *env, jobject, jlong jHandle) {
    MioHandle *h = to_handle(jHandle);
    if (h == nullptr) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(h->mutex);
    return env->NewStringUTF(h->last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_itantra_tts_mio_MioNative_nativeRelease(JNIEnv *, jobject, jlong jHandle) {
    delete to_handle(jHandle);
}
