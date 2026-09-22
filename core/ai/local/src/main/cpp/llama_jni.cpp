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
#include <set>

#include "llama.h"
#include "ggml-backend.h"

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
 * Begins a generation. Tokens are then pulled one at a time via nextToken().
 *
 * The KV cache is cleared first. Without that every generation inherits the previous
 * one's context, so two unrelated prompts silently condition on each other — which
 * presents as a model-quality problem rather than the state bug it actually is.
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_startGeneration(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jint maxTokens,
        jfloat temperature, jint topK, jfloat topP, jlong seed, jstring grammar) {

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
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        // Negative means "no seed requested" from the Kotlin side (AiRequest.seed == null).
        const uint32_t resolvedSeed = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
        llama_sampler_chain_add(chain, llama_sampler_init_dist(resolvedSeed));
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
