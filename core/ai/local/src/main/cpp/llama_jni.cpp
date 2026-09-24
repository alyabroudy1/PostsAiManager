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
#include <algorithm>
#include <chrono>
#include <string>
#include <vector>
#include <set>

#include "llama.h"
#include "ggml-backend.h"

#define LOG_TAG "pam_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

/** One message in a [PamSession]'s standing chat history — see its doc. */
struct ChatTurn {
    std::string role;
    std::string content;
};

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

    // ── standing chat session ────────────────────────────────────────────────
    //
    // The KV cache *is* the conversation (see documentation/02-architecture.md §5.3 and
    // llama.cpp's examples/simple-chat/simple-chat.cpp, which this mirrors): rather than
    // re-decoding the whole formatted prompt on every turn, the session keeps the message
    // list it last rendered and, on each new turn, decodes only the text the chat template
    // adds beyond what was rendered last time (`chatPrevLen` — the "prev_len" trick).
    // Sampled/decoded tokens are never removed from the KV cache between turns; only
    // openChatSession/resetChatSession ever clear it.
    std::vector<ChatTurn> chatHistory;
    int chatPrevLen = 0;
};

double elapsedMs(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

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

/**
 * Frees everything owned by [session] — chain, context, model — and the struct itself.
 * Shared by [freeModel] and the "free the previous model before loading a new one" guard in
 * [loadModel], so both paths tear a session down the same way.
 */
void destroySession(PamSession *session) {
    releaseChain(session);
    if (session->ctx)   llama_free(session->ctx);
    if (session->model) llama_model_free(session->model);
    delete session;
}

// Tracks the one model this process may have resident, independently of the Kotlin-side
// handle. A model must be resident on exactly one accelerator at a time and never two at
// once — InferenceService.loadModel already frees its handle before calling loadModel()
// again, so in the normal path this is a no-op by the time loadModel runs. It exists as a
// belt-and-braces guard at the JNI boundary itself: no caller can end up with two resident
// llama_model/llama_context pairs in this process, even one that (by bug or future
// refactor) skips the Kotlin-side free.
PamSession *g_activeSession = nullptr;

// What the most recent loadModel() actually passed to llama_model_params.devices — "CPU"
// when the device list was restricted to CPU-type devices, "all" when devices was left
// NULL (every registered backend, including Vulkan, is eligible). Exposed to Kotlin via
// lastLoadDevices() so a test can assert on it without scraping logcat.
std::string g_lastLoadDevices = "none";

/**
 * Builds a NULL-terminated device list containing only CPU-type backend devices, for
 * `llama_model_params.devices` — see llama.h: "NULL-terminated list of devices to use for
 * offloading (if NULL, all available devices are used)". Passing this instead of NULL keeps
 * llama.cpp from ever registering the Vulkan device in ggml_backend_sched, which is what
 * `n_gpu_layers = 0` alone does not do: it only keeps weights on CPU, but large-batch ops
 * (prompt processing in particular) still get offloaded to whatever non-CPU device is
 * registered, which is what crashes on the Adreno 740
 * (`vk::Device::createComputePipeline: ErrorUnknown`) for models whose shaders do not
 * compile on this driver.
 *
 * The returned vector only needs to stay alive for the duration of the synchronous
 * `llama_model_load_from_file` call that follows — llama.cpp reads the list during loading,
 * it does not retain the array itself — so a function-local vector on the caller's stack is
 * enough; nothing outlives that call holding a pointer into it.
 */
/**
 * Renders [session]'s standing chat history with the model's own template — shared by
 * formatChat() (stateless, one-shot) and the chat-session functions below (stateful,
 * incremental). Returns empty when the model declares no template.
 */
std::string renderChatHistory(PamSession *session, bool addAssistant) {
    const char *tmpl = llama_model_chat_template(session->model, nullptr);
    if (tmpl == nullptr) return {};

    const size_t count = session->chatHistory.size();
    std::vector<llama_chat_message> messages(count);
    size_t totalChars = 0;
    for (size_t i = 0; i < count; ++i) {
        messages[i].role = session->chatHistory[i].role.c_str();
        messages[i].content = session->chatHistory[i].content.c_str();
        totalChars += session->chatHistory[i].role.size() + session->chatHistory[i].content.size();
    }

    std::vector<char> buf(totalChars * 2 + 512);
    int32_t written = llama_chat_apply_template(
            tmpl, messages.data(), messages.size(), addAssistant, buf.data(), (int32_t) buf.size());
    if (written > (int32_t) buf.size()) {
        buf.resize(written + 1);
        written = llama_chat_apply_template(
                tmpl, messages.data(), messages.size(), addAssistant, buf.data(), (int32_t) buf.size());
    }
    if (written < 0) return {};
    return std::string(buf.data(), written);
}

/**
 * Tokenises [text] and decodes it into [session]'s context in `n_batch`-sized chunks — the
 * same chunking [startGeneration] always needed (see its doc on why one big batch aborts
 * the process). All but the last chunk are decoded here; the remainder is left as
 * [session]->batch/promptTokens so a following [nextToken] can decode it and sample.
 *
 * @param addSpecial whether to add the model's special tokens (BOS) — only true for the
 *   very first decode this context has ever seen; see `llama_memory_seq_pos_max(mem, 0) ==
 *   -1` at each call site, matching llama.cpp's own simple-chat example.
 * @param leaveRemainderForSampling when true (a chat turn about to generate), the final
 *   chunk is left un-decoded for nextToken's first call; when false (bulk history replay,
 *   nothing will be generated from it), every chunk including the last is decoded here.
 * @return false only on an actual decode failure — an empty [text] is not an error.
 */
bool decodeIntoSession(PamSession *session, const std::string &text, bool addSpecial,
                        bool leaveRemainderForSampling, int *outTokenCount) {
    *outTokenCount = 0;
    if (text.empty()) return true;

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const int needed = -llama_tokenize(
            vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, addSpecial, true);
    if (needed <= 0) return true;

    session->promptTokens.assign(needed, 0);
    if (llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                        session->promptTokens.data(), needed, addSpecial, true) < 0) {
        LOGE("decodeIntoSession: tokenisation failed");
        return false;
    }
    *outTokenCount = needed;

    const int nBatch = (int) llama_n_batch(session->ctx);
    const int total  = needed;
    int consumed = 0;

    // Leave the final chunk un-decoded only when the caller wants to sample from it next
    // (a chat turn, via nextToken); a bulk replay decodes everything, chunk by chunk,
    // including the last — there is no generation to follow it.
    while (total - consumed > (leaveRemainderForSampling ? nBatch : 0)) {
        const int chunkSize = std::min(nBatch, total - consumed);
        llama_batch chunk = llama_batch_get_one(session->promptTokens.data() + consumed, chunkSize);
        if (llama_decode(session->ctx, chunk) != 0) {
            LOGE("decodeIntoSession: decode failed at token %d of %d", consumed, total);
            return false;
        }
        consumed += chunkSize;
    }

    if (leaveRemainderForSampling) {
        session->batch = llama_batch_get_one(session->promptTokens.data() + consumed, total - consumed);
    }
    return true;
}

/** Builds the sampler chain shared by the raw one-shot path and the chat-session path. */
llama_sampler *buildSamplerChain(const llama_vocab *vocab, float temperature, int topK, float topP,
                                  jlong seed, const std::string &grammarStd) {
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
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        // Negative means "no seed requested" from the Kotlin side (AiRequest.seed == null).
        const uint32_t resolvedSeed = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
        llama_sampler_chain_add(chain, llama_sampler_init_dist(resolvedSeed));
    }
    return chain;
}

/**
 * Drops the oldest non-system turn (a user/assistant pair, or a lone trailing user turn) so
 * a context-overflowing conversation can be rebuilt smaller. @return false if there is
 * nothing left to drop (only the pending, not-yet-answered user turn remains).
 */
bool dropOldestChatTurn(PamSession *session) {
    const size_t start = (!session->chatHistory.empty() && session->chatHistory[0].role == "system") ? 1 : 0;
    if (session->chatHistory.size() <= start + 1) return false;
    session->chatHistory.erase(session->chatHistory.begin() + (long) start);
    return true;
}

std::vector<ggml_backend_dev_t> cpuOnlyDevices() {
    std::vector<ggml_backend_dev_t> devices;
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (ggml_backend_dev_type(dev) == GGML_BACKEND_DEVICE_TYPE_CPU) {
            devices.push_back(dev);
        }
    }
    devices.push_back(nullptr); // NULL terminator — required by llama.cpp's contract.
    return devices;
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

/**
 * Reports which accelerator types are actually usable on this device.
 *
 * `llama_backend_init()` (called from `backendInit()` above, before this can meaningfully
 * run) already calls `ggml_backend_load_all()` when no backend is registered yet, so every
 * backend compiled into this binary — CPU always, Vulkan only when built with
 * `-DGGML_VULKAN=ON` (the `pam.gpuBackend=vulkan` Gradle switch) — is enumerable here via
 * `ggml_backend_dev_count()`/`ggml_backend_dev_get()`.
 *
 * Ordinals match `com.postsaimanager.core.model.Accelerator`: 0 = CPU, 1 = GPU. A CPU/iGPU
 * device on a build with no GPU backend compiled in still reports as CPU-only, since no
 * device of type GGML_BACKEND_DEVICE_TYPE_GPU/_IGPU is ever registered in that case.
 */
JNIEXPORT jintArray JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_availableAccelerators(JNIEnv *env, jobject) {
    std::set<jint> accelerators;
    accelerators.insert(0); // CPU — every build ships the CPU backend.

    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const enum ggml_backend_dev_type type = ggml_backend_dev_type(dev);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            accelerators.insert(1); // GPU
        }
    }

    jintArray result = env->NewIntArray((jsize) accelerators.size());
    std::vector<jint> values(accelerators.begin(), accelerators.end());
    env->SetIntArrayRegion(result, 0, (jsize) values.size(), values.data());
    return result;
}

/** Diagnostics: name and description of every registered backend device, one per line. */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_backendDescription(JNIEnv *env, jobject) {
    std::string description;
    const size_t count = ggml_backend_dev_count();
    for (size_t i = 0; i < count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        description += ggml_backend_dev_name(dev);
        description += " (";
        description += ggml_backend_dev_description(dev);
        description += ")\n";
    }
    return env->NewStringUTF(description.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_loadModel(
        JNIEnv *env, jobject, jstring modelPath, jint contextTokens, jint batchTokens,
        jint threads, jint threadsBatch, jboolean useMmap, jboolean useMlock,
        jboolean flashAttention, jint gpuLayers, jint accelerator) {

    const std::string path = jstringToStd(env, modelPath);
    // Ordinals of com.postsaimanager.core.model.Accelerator: 0 = CPU, 1 = GPU.
    const bool cpuOnly = accelerator == 0;

    // A model must be resident on exactly one accelerator at a time, never two, and this
    // process may hold at most one resident model. InferenceService.loadModel already frees
    // its handle before calling this again, so g_activeSession is normally already null
    // here — this is the belt-and-braces guard at the JNI boundary itself: the old model's
    // weights are always released *before* the new ones are allocated, never after.
    if (g_activeSession != nullptr) {
        LOGI("pam_llama: freed previous model before loading %s on %s",
             path.c_str(), cpuOnly ? "CPU" : "GPU");
        destroySession(g_activeSession);
        g_activeSession = nullptr;
    }

    llama_model_params modelParams = llama_model_default_params();

    // See llama.h: `devices` is a NULL-terminated list of devices to use for offloading; if
    // NULL, all available devices are used. `n_gpu_layers = 0` alone does not stop llama.cpp
    // from registering a non-CPU device (Vulkan, here) in ggml_backend_sched and offloading
    // large-batch ops to it regardless — which is what crashes on the Adreno 740. Passing a
    // device list that contains only CPU-type devices is the actual "never touch the GPU"
    // switch. See cpuOnlyDevices() above for why the vector only needs stack lifetime.
    std::vector<ggml_backend_dev_t> cpuDevices;
    if (cpuOnly) {
        cpuDevices = cpuOnlyDevices();
        modelParams.devices = cpuDevices.data();
        // Belt-and-braces: CPU means CPU regardless of what gpuLayers was forwarded as.
        modelParams.n_gpu_layers = 0;
        g_lastLoadDevices = "CPU";
        LOGI("pam_llama: devices=[CPU]");
    } else {
        modelParams.devices = nullptr; // all available devices — Vulkan may offload.
        modelParams.n_gpu_layers = gpuLayers;
        g_lastLoadDevices = "all";
        LOGI("pam_llama: devices=[all]");
    }

    modelParams.load_mode = useMmap && useMlock ? LLAMA_LOAD_MODE_MMAP_MLOCK
                             : useMmap           ? LLAMA_LOAD_MODE_MMAP
                             : useMlock          ? LLAMA_LOAD_MODE_MLOCK
                                                  : LLAMA_LOAD_MODE_NONE;

    llama_model *model = llama_model_load_from_file(path.c_str(), modelParams);
    if (model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx           = static_cast<uint32_t>(contextTokens);
    ctxParams.n_batch         = static_cast<uint32_t>(batchTokens);
    ctxParams.n_threads       = threads;
    ctxParams.n_threads_batch = threadsBatch;
    ctxParams.flash_attn_type = flashAttention
            ? LLAMA_FLASH_ATTN_TYPE_ENABLED
            : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context *ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *session = new PamSession{};
    session->model = model;
    session->ctx   = ctx;
    g_activeSession = session;
    LOGI("model loaded, n_ctx=%d n_batch=%d threads=%d threads_batch=%d gpu_layers=%d accelerator=%s",
         contextTokens, batchTokens, threads, threadsBatch, gpuLayers, cpuOnly ? "CPU" : "GPU");
    return reinterpret_cast<jlong>(session);
}

/**
 * Recreates the context for an already-loaded model — ReloadScope.CONTEXT on the Kotlin
 * side. The model itself (and its mmap'd/mlock'd weights) is left completely alone; only
 * context/batch/thread/flash-attention size change, which is what makes this materially
 * cheaper than a full loadModel().
 *
 * Any in-flight generation state belongs to the old context and cannot outlive it, so it is
 * released first. The new context is created *before* the old one is freed: if creation
 * fails the old context is still valid and the session is left exactly as it was, rather
 * than in a half-torn-down state.
 *
 * This new-before-free ordering is fine here — unlike loadModel's old-model-before-new-model
 * ordering — because a `llama_context` is small relative to the resident model weights (KV
 * cache aside, it holds no second copy of the weights), so briefly holding two contexts for
 * one already-loaded model never risks the out-of-memory failure that motivates freeing the
 * old *model* first.
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_recreateContext(
        JNIEnv *, jobject, jlong handle, jint contextTokens, jint batchTokens,
        jint threads, jint threadsBatch, jboolean flashAttention) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr) return JNI_FALSE;

    releaseChain(session);

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx           = static_cast<uint32_t>(contextTokens);
    ctxParams.n_batch         = static_cast<uint32_t>(batchTokens);
    ctxParams.n_threads       = threads;
    ctxParams.n_threads_batch = threadsBatch;
    ctxParams.flash_attn_type = flashAttention
            ? LLAMA_FLASH_ATTN_TYPE_ENABLED
            : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    llama_context *newCtx = llama_init_from_model(session->model, ctxParams);
    if (newCtx == nullptr) {
        LOGE("failed to recreate context");
        return JNI_FALSE;
    }

    if (session->ctx != nullptr) llama_free(session->ctx);
    session->ctx = newCtx;
    LOGI("context recreated, n_ctx=%d n_batch=%d threads=%d threads_batch=%d",
         contextTokens, batchTokens, threads, threadsBatch);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return;
    if (session == g_activeSession) g_activeSession = nullptr;
    destroySession(session);
}

/**
 * Diagnostic: which devices the most recent successful loadModel() passed to
 * `llama_model_params.devices` — `"CPU"` when restricted to CPU-type devices, `"all"` when
 * left NULL, or `"none"` if no model has ever loaded in this process. Lets a test assert on
 * this directly instead of scraping logcat for the `pam_llama: devices=` line.
 */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_lastLoadDevices(JNIEnv *env, jobject) {
    return env->NewStringUTF(g_lastLoadDevices.c_str());
}

/**
 * Begins a **one-shot** generation — no standing conversation, tokens pulled one at a time
 * via nextToken(). Used by `AiExtractionUseCase`'s grammar-constrained extraction, which
 * must not read or pollute a chat session's KV cache (they can share this process's one
 * resident context — see the class doc on `PamSession`).
 *
 * The KV cache is cleared first, and any chat session standing in it is discarded (its
 * `chatHistory`/`chatPrevLen` reset to empty/0) — the two paths are mutually exclusive by
 * construction: whichever ran most recently owns the KV cache, and the chat path's
 * `ensureChatSession` on the Kotlin side is what notices this happened and re-primes before
 * the next chat turn (see `RemoteAiEngine`/`LocalAiEngine`).
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_startGeneration(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens,
        jfloat temperature, jint topK, jfloat topP, jlong seed, jstring grammar) {

    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return JNI_FALSE;

    releaseChain(session);
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->chatHistory.clear();
    session->chatPrevLen = 0;

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

    session->chain = buildSamplerChain(vocab, temperature, topK, topP, seed, grammarStd);

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
    const auto decodeStart = std::chrono::steady_clock::now();

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
    LOGI("pam_llama: one-shot prompt tokens=%d prompt_eval_ms=%.1f", total, elapsedMs(decodeStart));
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

// ── standing chat session ───────────────────────────────────────────────────────────────
//
// See the `chatHistory`/`chatPrevLen` doc on PamSession and documentation/02-architecture.md
// §5.3. Mirrors llama.cpp's own examples/simple-chat/simple-chat.cpp: the KV cache is never
// cleared between turns, so only the template text *newly added* since the last turn is
// tokenised and decoded — a handful of tokens instead of the whole conversation every time.

/**
 * Opens a fresh chat session: clears the KV cache and this session's chat history, then
 * seeds it with [systemPrompt] (skipped if blank). Nothing is decoded yet — the system
 * prompt's tokens are folded into the first turn's diff by [sendChatMessage].
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_openChatSession(
        JNIEnv *env, jobject, jlong handle, jstring systemPrompt) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return JNI_FALSE;

    releaseChain(session);
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->chatHistory.clear();
    session->chatPrevLen = 0;

    const std::string sys = jstringToStd(env, systemPrompt);
    if (!sys.empty()) {
        session->chatHistory.push_back({"system", sys});
    }
    LOGI("pam_llama: chat session opened, system prompt %zu chars", sys.size());
    return JNI_TRUE;
}

/**
 * Bulk-replays already-completed turns (persisted history) into an opened session — used
 * once when a session is (re)opened for a conversation that already has messages, e.g. on
 * cold start or after a model reload invalidated the KV cache. This is the one place a
 * full re-decode of history is expected: everything else only ever decodes the newest turn.
 *
 * [roles]/[contents] must alternate user/assistant (system, if any, was already set by
 * [openChatSession]). Nothing is generated; the whole rendered diff is decoded immediately.
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_primeChatSession(
        JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return JNI_FALSE;

    const jsize count = env->GetArrayLength(roles);
    if (count == 0) return JNI_TRUE;
    if (env->GetArrayLength(contents) != count) return JNI_FALSE;

    for (jsize i = 0; i < count; ++i) {
        auto role = (jstring) env->GetObjectArrayElement(roles, i);
        auto content = (jstring) env->GetObjectArrayElement(contents, i);
        session->chatHistory.push_back({jstringToStd(env, role), jstringToStd(env, content)});
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }

    const std::string formatted = renderChatHistory(session, /* addAssistant */ false);
    if (formatted.empty()) {
        LOGE("primeChatSession: model has no chat template");
        return JNI_FALSE;
    }
    const size_t from = std::min((size_t) session->chatPrevLen, formatted.size());
    const std::string diff = formatted.substr(from);

    llama_memory_t mem = llama_get_memory(session->ctx);
    const bool isFirst = llama_memory_seq_pos_max(mem, 0) == -1;

    int tokenCount = 0;
    const auto start = std::chrono::steady_clock::now();
    if (!decodeIntoSession(session, diff, isFirst, /* leaveRemainderForSampling */ false, &tokenCount)) {
        return JNI_FALSE;
    }
    session->chatPrevLen = (int) formatted.size();
    LOGI("pam_llama: session primed turns=%d tokens=%d ms=%.1f", (int) count, tokenCount, elapsedMs(start));
    return JNI_TRUE;
}

/**
 * Begins a chat turn: appends [userText] to the session's history, renders the template
 * with an open assistant turn, and decodes only what is new since the last turn was
 * committed (see the class doc). Tokens are then pulled via the existing [nextToken].
 *
 * If [noThink] is set, `/no_think` is appended to the user turn — the practical way to
 * disable Qwen3/3.5 reasoning without a chat-template kwarg llama.cpp's
 * `llama_chat_apply_template` has no way to pass (see documentation/02-architecture.md §5.3
 * on why this, rather than an `enable_thinking` flag, is what actually works against the
 * template baked into the GGUF).
 *
 * On overflow (this turn would not fit in `n_ctx`) the oldest non-system turns are dropped
 * and the remaining history is fully re-decoded once — the one legitimate full re-decode
 * outside of [primeChatSession].
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_sendChatMessage(
        JNIEnv *env, jobject, jlong handle, jstring userText, jint maxTokens,
        jfloat temperature, jint topK, jfloat topP, jlong seed, jstring grammar, jboolean noThink) {

    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return JNI_FALSE;

    releaseChain(session);

    std::string userStd = jstringToStd(env, userText);
    if (noThink == JNI_TRUE) userStd += " /no_think";
    const std::string grammarStd = jstringToStd(env, grammar);

    session->chatHistory.push_back({"user", userStd});

    std::string formatted = renderChatHistory(session, /* addAssistant */ true);
    if (formatted.empty()) {
        LOGE("sendChatMessage: model has no chat template");
        return JNI_FALSE;
    }

    llama_memory_t mem = llama_get_memory(session->ctx);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const int nCtx = (int) llama_n_ctx(session->ctx);

    auto currentDiffTokenCount = [&]() -> int {
        const size_t from = std::min((size_t) session->chatPrevLen, formatted.size());
        const std::string diff = formatted.substr(from);
        if (diff.empty()) return 0;
        const bool isFirst = llama_memory_seq_pos_max(mem, 0) == -1;
        return -llama_tokenize(vocab, diff.c_str(), (int32_t) diff.size(), nullptr, 0, isFirst, true);
    };

    int nPast = (int) llama_memory_seq_pos_max(mem, 0) + 1;
    int diffTokens = currentDiffTokenCount();

    if (nPast + diffTokens + (int) maxTokens > nCtx) {
        LOGI("pam_llama: context overflow (n_past=%d diff=%d max=%d n_ctx=%d) — dropping oldest turns",
             nPast, diffTokens, (int) maxTokens, nCtx);
        while (nPast + diffTokens + (int) maxTokens > nCtx && dropOldestChatTurn(session)) {
            llama_memory_clear(mem, true);
            session->chatPrevLen = 0;
            formatted = renderChatHistory(session, /* addAssistant */ true);
            nPast = (int) llama_memory_seq_pos_max(mem, 0) + 1; // always 0 right after clear
            diffTokens = currentDiffTokenCount();
        }
    }

    const size_t from = std::min((size_t) session->chatPrevLen, formatted.size());
    const std::string diff = formatted.substr(from);
    const bool isFirst = llama_memory_seq_pos_max(mem, 0) == -1;

    int tokenCount = 0;
    const auto start = std::chrono::steady_clock::now();
    if (!decodeIntoSession(session, diff, isFirst, /* leaveRemainderForSampling */ true, &tokenCount)) {
        return JNI_FALSE;
    }
    LOGI("pam_llama: session decode new_tokens=%d n_past=%d prompt_eval_ms=%.1f",
         tokenCount, nPast, elapsedMs(start));

    // Marks "up to the open assistant turn" as decoded; commitChatReply() extends this once
    // the generated content is known, without decoding anything more (see its doc).
    session->chatPrevLen = (int) formatted.size();

    session->chain = buildSamplerChain(vocab, temperature, topK, topP, seed, grammarStd);
    session->generated = 0;
    session->maxTokens = maxTokens;
    session->finished  = false;
    return JNI_TRUE;
}

/**
 * Records the assistant's reply (thinking-stripped — see `SendChatMessageUseCase`'s KDoc on
 * why a reasoning trace never re-enters a future prompt) in the session's history, so the
 * *next* turn's template diff renders correctly. Decodes nothing: the reply's tokens are
 * already in the KV cache from the [nextToken] calls that produced them.
 */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_commitChatReply(
        JNIEnv *env, jobject, jlong handle, jstring answer) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return;

    session->chatHistory.push_back({"assistant", jstringToStd(env, answer)});
    const std::string formatted = renderChatHistory(session, /* addAssistant */ false);
    session->chatPrevLen = (int) formatted.size();
}

/** Drops the standing chat session — its KV cache and history — e.g. on a conversation switch. */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_resetChatSession(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return;
    releaseChain(session);
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->chatHistory.clear();
    session->chatPrevLen = 0;
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
