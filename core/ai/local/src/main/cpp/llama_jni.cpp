// JNI bridge to llama.cpp.
//
// Generation is exposed as a **pull-based token stream** — start / next / stop — rather
// than one blocking call returning a finished string. Three reasons:
//
//   * Kotlin owns the loop, so `Flow` and structured cancellation come free.
//   * C++ stays free of JNI callbacks, which would need thread attach/detach per token.
//   * Stopping is simply "stop calling next", with no cancellation flag to race on.
//
// See documentation/06-llama-spike.md

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "llama.h"

#define LOG_TAG "pam_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct PamSession {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;

    // ── per-generation state ─────────────────────────────────────────────────
    llama_sampler *chain = nullptr;
    // Must outlive the batch: llama_batch_get_one stores a pointer into this.
    std::vector<llama_token> promptTokens;
    llama_token lastToken = 0;
    llama_batch batch{};
    int  generated = 0;
    int  maxTokens = 0;
    bool finished  = true;
};

std::string jstringToStd(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string tokenToPiece(const llama_vocab *vocab, llama_token token) {
    char buf[128];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, true);
    if (n >= 0) return std::string(buf, n);

    // Negative means the buffer was too small and -n is the size required. Returning
    // empty here would silently drop the token — invisible until output is subtly wrong.
    std::vector<char> large(-n);
    n = llama_token_to_piece(vocab, token, large.data(), (int32_t) large.size(), 0, true);
    if (n < 0) return {};
    return std::string(large.data(), n);
}

void releaseChain(PamSession *session) {
    if (session->chain != nullptr) {
        llama_sampler_free(session->chain);
        session->chain = nullptr;
    }
    session->finished = true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
    LOGI("llama backend initialised");
}

JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_loadModel(
        JNIEnv *env, jobject, jstring modelPath, jint contextTokens, jint threads) {

    const std::string path = jstringToStd(env, modelPath);

    llama_model_params modelParams = llama_model_default_params();
    // CPU only. GPU offload is a separate question with a large build surface — do not
    // conflate it with "does this work at all".
    modelParams.n_gpu_layers = 0;

    llama_model *model = llama_model_load_from_file(path.c_str(), modelParams);
    if (model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx     = static_cast<uint32_t>(contextTokens);
    ctxParams.n_batch   = 512;
    ctxParams.n_threads = threads;

    llama_context *ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *session = new PamSession{};
    session->model = model;
    session->ctx   = ctx;
    LOGI("model loaded, n_ctx=%d threads=%d", contextTokens, threads);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return;
    releaseChain(session);
    if (session->ctx)   llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

/**
 * Begins a generation. Tokens are then pulled one at a time via nextToken().
 *
 * The KV cache is cleared first. Without that every generation inherits the previous
 * one's context, so two unrelated prompts silently condition on each other — which
 * presents as a model-quality problem rather than the state bug it actually is.
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_startGeneration(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens,
        jfloat temperature, jstring grammar) {

    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return JNI_FALSE;

    releaseChain(session);
    llama_memory_clear(llama_get_memory(session->ctx), true);

    const std::string promptStd  = jstringToStd(env, prompt);
    const std::string grammarStd = jstringToStd(env, grammar);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);

    const int needed = -llama_tokenize(
            vocab, promptStd.c_str(), (int32_t) promptStd.size(), nullptr, 0, true, true);
    if (needed <= 0) {
        // An empty prompt would build a zero-length batch, which llama_decode rejects.
        LOGE("prompt produced no tokens");
        return JNI_FALSE;
    }

    session->promptTokens.assign(needed, 0);
    if (llama_tokenize(vocab, promptStd.c_str(), (int32_t) promptStd.size(),
                       session->promptTokens.data(), needed, true, true) < 0) {
        LOGE("tokenisation failed");
        return JNI_FALSE;
    }

    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());

    // The grammar goes first so it filters the candidate set before any probabilistic
    // sampler runs. Placed afterwards, the constraint could be sampled around.
    if (!grammarStd.empty()) {
        llama_sampler *grammarSampler =
                llama_sampler_init_grammar(vocab, grammarStd.c_str(), "root");
        if (grammarSampler == nullptr) {
            LOGE("grammar failed to parse — continuing unconstrained");
        } else {
            llama_sampler_chain_add(chain, grammarSampler);
        }
    }

    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    session->chain = chain;

    // Feed the prompt in n_batch-sized pieces.
    //
    // llama_decode asserts that a batch is no larger than n_batch (512 here) and, being an
    // assert, it calls ggml_abort — SIGABRT, taking the whole inference process with it.
    // Not an error code, nothing catchable. Submitting the prompt as one batch therefore
    // worked only while prompts stayed under 512 tokens: short chat turns did, a document
    // extraction prompt (instructions plus the page layout, around 800) did not, and
    // grounding chat in a document would have hit exactly the same wall.
    //
    // All but the final piece are decoded here; the remainder is left in session->batch so
    // the first nextToken decodes it and samples from its logits.
    const int nBatch = (int) llama_n_batch(session->ctx);
    const int total  = (int) session->promptTokens.size();
    int consumed = 0;

    while (total - consumed > nBatch) {
        llama_batch chunk = llama_batch_get_one(session->promptTokens.data() + consumed, nBatch);
        if (llama_decode(session->ctx, chunk) != 0) {
            LOGE("prompt decode failed at token %d of %d", consumed, total);
            releaseChain(session);
            return JNI_FALSE;
        }
        consumed += nBatch;
    }

    session->batch = llama_batch_get_one(session->promptTokens.data() + consumed,
                                         total - consumed);
    session->generated = 0;
    session->maxTokens = maxTokens;
    session->finished  = false;
    return JNI_TRUE;
}

/** @return the next token's text, or null when generation is complete. */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_nextToken(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->finished || session->chain == nullptr) {
        return nullptr;
    }

    if (session->generated >= session->maxTokens) {
        releaseChain(session);
        return nullptr;
    }

    if (llama_decode(session->ctx, session->batch) != 0) {
        LOGE("decode failed at token %d", session->generated);
        releaseChain(session);
        return nullptr;
    }

    // llama_sampler_sample() samples *and accepts* — calling llama_sampler_accept again
    // would advance the grammar state twice per token and abort the process.
    const llama_token next = llama_sampler_sample(session->chain, session->ctx, -1);

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    if (llama_vocab_is_eog(vocab, next)) {
        releaseChain(session);
        return nullptr;
    }

    const std::string piece = tokenToPiece(vocab, next);

    session->lastToken = next;
    session->batch     = llama_batch_get_one(&session->lastToken, 1);
    session->generated++;

    return env->NewStringUTF(piece.c_str());
}

JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_stopGeneration(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session != nullptr) releaseChain(session);
}

/**
 * Formats a conversation using **the model's own chat template**, read from its GGUF
 * metadata.
 *
 * Hard-coding one template does not work across a catalogue: Qwen uses ChatML, Gemma uses
 * `<start_of_turn>`, Llama 3 uses its own header markers. Using the wrong one does not
 * error — it silently degrades output, which is the worst kind of bug to ship.
 *
 * @return the formatted prompt, or null when the model declares no template (the caller
 *         then falls back to ChatML).
 */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_formatChat(
        JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents,
        jboolean addAssistant) {

    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr) return nullptr;

    const char *tmpl = llama_model_chat_template(session->model, nullptr);
    if (tmpl == nullptr) return nullptr;

    const jsize count = env->GetArrayLength(roles);
    if (count == 0 || env->GetArrayLength(contents) != count) return nullptr;

    // Hold the JNI strings alive for as long as llama_chat_message points at them.
    std::vector<std::string> roleStore(count);
    std::vector<std::string> contentStore(count);
    std::vector<llama_chat_message> messages(count);
    size_t totalChars = 0;

    for (jsize i = 0; i < count; ++i) {
        auto role = (jstring) env->GetObjectArrayElement(roles, i);
        auto content = (jstring) env->GetObjectArrayElement(contents, i);
        roleStore[i] = jstringToStd(env, role);
        contentStore[i] = jstringToStd(env, content);
        messages[i].role = roleStore[i].c_str();
        messages[i].content = contentStore[i].c_str();
        totalChars += roleStore[i].size() + contentStore[i].size();
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    // The documented recommendation is 2x the total message length; grow once if short.
    std::vector<char> buf(totalChars * 2 + 512);
    int32_t written = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), addAssistant == JNI_TRUE,
            buf.data(), (int32_t) buf.size());

    if (written > (int32_t) buf.size()) {
        buf.resize(written + 1);
        written = llama_chat_apply_template(
                tmpl, messages.data(), messages.size(), addAssistant == JNI_TRUE,
                buf.data(), (int32_t) buf.size());
    }

    if (written < 0) {
        LOGE("chat template application failed");
        return nullptr;
    }

    return env->NewStringUTF(std::string(buf.data(), written).c_str());
}

/** @return true if the model declares its own chat template. */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_hasChatTemplate(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr) return JNI_FALSE;
    return llama_model_chat_template(session->model, nullptr) != nullptr ? JNI_TRUE : JNI_FALSE;
}

/** Debug only — intentionally segfaults, to measure crash blast radius (spike Q3). */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_crashForTesting(JNIEnv *, jobject) {
    LOGE("intentional native crash — spike Q3");
    volatile int *nowhere = nullptr;
    *nowhere = 1;
}

} // extern "C"
