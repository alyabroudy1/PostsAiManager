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
#include <sys/auxv.h>
#include <asm/hwcap.h>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "pam_llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Reply-budget floors (see sendChatMessage()'s doc, "Reply budget"). Deliberately small,
// fixed constants rather than a fraction of n_ctx — they only ever bind in the edge case of
// a conversation already hard up against its context window.
constexpr int kMinReplyTokens = 32;          // never offer less than this to answer with at all.
constexpr int kOverflowReserveTokens = 1024; // history is trimmed to leave room for this much reply.
constexpr int kMinAnswerReserveTokens = 512; // thinking may never eat into this much of the reply.

// What Qwen3.5's own chat template appends to the generation prompt for enable_thinking=true
// / false. Rendered here rather than by llama_chat_apply_template (which can't pass the flag).
const std::string kThinkOpenPrefill = "<think>\n";
const std::string kThinkClosedPrefill = "<think>\n\n</think>\n\n";

/**
 * CMakeLists.txt builds this library with `-march=armv8.2-a+dotprod+fp16` (see its
 * comment) rather than true runtime dispatch, which means the whole .so — not just a
 * runtime-selected kernel — can contain dotprod instructions. A core without dotprod
 * (pre-2018-ish arm64, still reachable at this project's minSdk 26) would SIGILL the
 * first time one of those instructions executes.
 *
 * Checked once, before the first model load, via the HWCAP_ASIMDDP bit
 * (`getauxval(AT_HWCAP)`, bionic's documented way to read ARMv8 feature bits — this is
 * what /proc/cpuinfo's "asimddp" line is derived from). If it is missing we refuse to
 * load a model instead of letting the process crash.
 */
bool deviceSupportsRequiredCpuFeatures() {
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const bool hasDotprod = (hwcap & HWCAP_ASIMDDP) != 0;
    LOGI("pam_llama: HWCAP_ASIMDDP (dotprod) %s", hasDotprod ? "present" : "MISSING");
    return hasDotprod;
}

/** One message in a [PamSession]'s standing chat history — see its doc. */
struct ChatTurn {
    std::string role;
    std::string content;
};

struct PamSession {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;

    // ── vision (optional; see loadVision) ────────────────────────────────────
    // libmtmd context bound to [model]; null when no mmproj is loaded.
    mtmd_context *mtmd = nullptr;
    // True right after a vision prompt was evaluated with logits for its last token: the
    // first nextToken() must sample from them instead of decoding session->batch.
    bool logitsReady = false;
    // "imageTokens=.. encodeMs=.. ..." for the last vision prompt — see lastVisionStats.
    std::string lastVisionStats;

    // ── per-generation state ─────────────────────────────────────────────────
    llama_sampler *chain = nullptr;
    // Must outlive the batch: llama_batch_get_one stores a pointer into this.
    std::vector<llama_token> promptTokens;
    llama_token lastToken = 0;
    llama_batch batch{};
    int  generated = 0;
    int  maxTokens = 0;
    bool finished  = true;
    // True when the last generation ended by reaching maxTokens rather than by an
    // end-of-generation token (a "length" finish). Reset at the start of every generation.
    bool hitLengthCap = false;
    // Trailing bytes of an incomplete multi-byte character carried to the next token — see nextToken().
    std::string pendingUtf8;
    // Wall-clock start of the current generation (set by startGeneration/sendChatMessage),
    // used to log total decode tok/s once generation ends — see nextToken().
    std::chrono::steady_clock::time_point generationStart{};

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

    // KV-cache position right before the currently open reply's tokens start — i.e. right
    // after [sendChatMessage] decoded the user turn + open assistant tag, before any tokens
    // were sampled. -1 when no reply is open (nothing pending to discard). Set at the end of
    // sendChatMessage and consumed by discardPendingReply — see its doc.
    llama_pos replyStartPos = -1;

    // Recurrent-state snapshot taken at replyStartPos — see rollbackToReplyStart(). Only
    // populated for hybrid/recurrent models (Qwen3.5 is one), whose state cannot be trimmed
    // token-by-token with llama_memory_seq_rm.
    std::vector<uint8_t> replyCheckpoint;

    // ── thinking-budget forced close ─────────────────────────────────────────
    //
    // Without this, a turn's maxTokens is spent on thinking + answer combined, and a small
    // model asked to reason about a document can easily spend the whole budget inside
    // <think>...</think>, leaving nothing to generate the actual answer — "model finished
    // thinking but produced no answer". See nextToken()'s doc and updateThinkingState().

    /** Only true for a chat turn where thinking is enabled — see sendChatMessage(). */
    bool trackThinking = false;
    bool sawThinkOpen = false;
    /** True from the first token after `<think>` until `</think>` (natural or forced). */
    bool inThinking = false;
    int  thinkingTokenCount = 0;
    /** Trigger threshold for the forced close; 0 (or trackThinking false) disables it. */
    int  thinkingBudgetTokens = 0;
    /** Rolling window of recently generated text, scanned for `<think>`/`</think>`. */
    std::string tagScanBuffer;
    /** Queued once the budget is hit — drained by nextToken() before it samples anything. */
    std::vector<llama_token> forcedCloseTokens;
    size_t forcedCloseIndex = 0;
};

double elapsedMs(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

/** tokens / (ms / 1000) — 0 when ms is ~0 so a log line never divides by zero. */
double tokensPerSecond(int tokens, double ms) {
    return ms > 0.001 ? (double) tokens / (ms / 1000.0) : 0.0;
}

/**
 * A "tok/s=..." log fragment, or "tok/s=n/a" when the interval is too short to mean anything
 * (a sub-millisecond timing divided into a token count prints absurd rates like 340496).
 */
std::string tokensPerSecondText(int tokens, double ms) {
    if (ms < 1.0) return "tok_s=n/a";
    char buf[32];
    snprintf(buf, sizeof(buf), "tok_s=%.1f", tokensPerSecond(tokens, ms));
    return buf;
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

/**
 * Length of the longest prefix of [s] that ends on a UTF-8 character boundary — i.e. [s]
 * without a trailing, still-incomplete multi-byte sequence.
 */
size_t completeUtf8Prefix(const std::string &s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 3 && back <= n; ++back) {
        const unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue; // continuation byte: keep looking for its lead byte.
        const size_t need = c >= 0xF0 ? 4 : c >= 0xE0 ? 3 : c >= 0xC0 ? 2 : 1;
        return need > back ? n - back : n;
    }
    return n;
}

/**
 * Builds a jstring from UTF-8 via UTF-16 (`NewString`) instead of `NewStringUTF`, which
 * wants *Modified* UTF-8 and rejects 4-byte sequences (emoji) as well as stray bytes. Invalid
 * input becomes U+FFFD rather than a process abort.
 */
jstring utf8ToJString(JNIEnv *env, const std::string &s) {
    std::u16string out;
    out.reserve(s.size());
    size_t i = 0;
    const size_t n = s.size();
    while (i < n) {
        const unsigned char c = (unsigned char) s[i];
        uint32_t cp = 0xFFFD;
        size_t len = 1;
        if (c < 0x80) {
            cp = c;
        } else if (c >= 0xC2 && c < 0xE0) {
            len = 2;
        } else if (c >= 0xE0 && c < 0xF0) {
            len = 3;
        } else if (c >= 0xF0 && c < 0xF5) {
            len = 4;
        }
        if (len > 1) {
            if (i + len <= n) {
                uint32_t value = c & (0xFF >> (len + 1));
                bool ok = true;
                for (size_t k = 1; k < len; ++k) {
                    const unsigned char cc = (unsigned char) s[i + k];
                    if ((cc & 0xC0) != 0x80) { ok = false; break; }
                    value = (value << 6) | (cc & 0x3F);
                }
                if (ok && value <= 0x10FFFF && !(value >= 0xD800 && value <= 0xDFFF)) {
                    cp = value;
                } else {
                    len = 1;
                }
            } else {
                len = 1;
            }
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back((char16_t) (0xD800 + (cp >> 10)));
            out.push_back((char16_t) (0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back((char16_t) cp);
        }
        i += len;
    }
    return env->NewString(reinterpret_cast<const jchar *>(out.data()), (jsize) out.size());
}

/**
 * Logs decode throughput for the generation that just ended (0 tokens is a no-op — a
 * failed/empty turn has nothing to report). Called from every path that can end a
 * generation, right before it stops being "in flight" — see releaseChain()'s callers.
 */
void logGenerationEnd(const PamSession *session) {
    if (session->generated <= 0) return;
    const double ms = elapsedMs(session->generationStart);
    LOGI("pam_llama: decode tokens=%d ms=%.1f %s",
         session->generated, ms, tokensPerSecondText(session->generated, ms).c_str());
}

void releaseChain(PamSession *session) {
    if (session->chain != nullptr) {
        logGenerationEnd(session);
        llama_sampler_free(session->chain);
        session->chain = nullptr;
    }
    session->finished = true;
    session->logitsReady = false;
}

/**
 * Frees everything owned by [session] — chain, context, model — and the struct itself.
 * Shared by [freeModel] and the "free the previous model before loading a new one" guard in
 * [loadModel], so both paths tear a session down the same way.
 */
void destroySession(PamSession *session) {
    releaseChain(session);
    if (session->mtmd)  mtmd_free(session->mtmd);
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

/**
 * True for models whose memory has a recurrent component (Mamba/RWKV and hybrids such as
 * Qwen3.5's gated delta-net layers). That state is a running summary of every token so far,
 * so it cannot be trimmed back to an earlier position: `llama_memory_seq_rm(seq, p0, -1)` with
 * p0 > 0 returns false and changes nothing (see llama-memory-recurrent.cpp, seq_rm).
 */
bool hasRecurrentState(const PamSession *session) {
    return llama_model_is_hybrid(session->model) || llama_model_is_recurrent(session->model);
}

/** Snapshots the recurrent state at the current position — llama-server's checkpoint recipe. */
void takeReplyCheckpoint(PamSession *session) {
    session->replyCheckpoint.clear();
    if (!hasRecurrentState(session)) return;
    const size_t size = llama_state_seq_get_size_ext(session->ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    std::vector<uint8_t> data(size);
    if (size == 0 ||
        llama_state_seq_get_data_ext(session->ctx, data.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != size) {
        LOGE("pam_llama: could not checkpoint the recurrent state (size=%zu)", size);
        return;
    }
    session->replyCheckpoint = std::move(data);
}

/**
 * Drops the KV cache back to [pos] — where the open reply began. Attention-only models just
 * trim; recurrent/hybrid ones first restore the checkpoint [takeReplyCheckpoint] took at
 * that position, then trim the attention cells beyond it (llama-server does the same).
 * @return false if the rollback could not be done, in which case nothing can be trusted.
 */
bool rollbackToReplyStart(PamSession *session, llama_pos pos) {
    llama_memory_t mem = llama_get_memory(session->ctx);
    if (hasRecurrentState(session)) {
        if (session->replyCheckpoint.empty()) return false;
        if (llama_state_seq_set_data_ext(session->ctx, session->replyCheckpoint.data(),
                                         session->replyCheckpoint.size(), 0,
                                         LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == 0) {
            return false;
        }
    }
    return llama_memory_seq_rm(mem, 0, pos, -1);
}

/**
 * The KV cache can no longer be trusted to match [PamSession::chatHistory] (a rollback or a
 * re-decode failed part-way). Clears it and zeroes [PamSession::chatPrevLen] while keeping
 * the history, so the next [sendChatMessage] renders and decodes the whole conversation
 * again — a slow turn, but never one built on a stale cache.
 */
void invalidateKvCache(PamSession *session, const char *why) {
    LOGE("pam_llama: %s — invalidating the KV cache; the next turn re-primes from history", why);
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->chatPrevLen = 0;
    session->replyStartPos = -1;
    session->replyCheckpoint.clear();
}

/**
 * Consistency check: with no reply open, the KV must hold exactly the tokens of the rendered
 * history (n_past == token count of it). On any mismatch, log a warning and invalidate so the
 * next send re-primes instead of building on a cache that disagrees with the text bookkeeping.
 * @return true when consistent.
 */
bool verifyKvConsistency(PamSession *session, const char *where) {
    if (session->chatHistory.empty()) return true;
    const std::string rendered = renderChatHistory(session, /* addAssistant */ false);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const int expected = rendered.empty() ? 0 : -llama_tokenize(
            vocab, rendered.c_str(), (int32_t) rendered.size(), nullptr, 0, true, true);
    const int nPast = (int) llama_memory_seq_pos_max(llama_get_memory(session->ctx), 0) + 1;
    if (nPast == expected && (size_t) session->chatPrevLen == rendered.size()) {
        LOGI("pam_llama: kv consistent after %s (n_past=%d)", where, nPast);
        return true;
    }
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                        "pam_llama: kv MISMATCH after %s: n_past=%d rendered_tokens=%d prev_len=%d rendered_len=%zu",
                        where, nPast, expected, session->chatPrevLen, rendered.size());
    invalidateKvCache(session, where);
    return false;
}

/** Builds the sampler chain shared by the raw one-shot path and the chat-session path. */
llama_sampler *buildSamplerChain(const llama_vocab *vocab, float temperature, int topK, float topP,
                                  float presencePenalty, jlong seed, const std::string &grammarStd) {
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
        // Presence penalty (Qwen3.5 recommends 1.5-2.0 for chat) discourages re-emitting
        // tokens already in this reply. It only sees tokens sampled in this reply (the last
        // 256), not the prompt, so it cannot by itself stop copying from earlier turns.
        if (presencePenalty != 0.0f) {
            llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                    llama_vocab_n_tokens(vocab), /* penalty_last_n */ 256, /* repeat */ 1.0f, /* freq */ 0.0f, presencePenalty));
        }
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

/**
 * On hitting the thinking budget, generation does not just splice in a bare `</think>` —
 * for a small model, an abrupt close with no explanation can produce a weak or empty answer,
 * since nothing told it reasoning is over. Qwen3's own technical report describes exactly
 * this "thinking budget" technique and its fix: insert a short natural-language stop
 * instruction before the closing tag, then let the model continue past it normally.
 * Source: Qwen3 Technical Report, §2.3.3 "Thinking Budget" (https://arxiv.org/abs/2505.09388).
 */
const std::string FORCE_THINK_CLOSE_TEXT =
    "\nConsidering the limited time by the user, I have to give the solution based on the "
    "thinking directly now.\n</think>\n\n";

/**
 * Tokenises [text] (no BOS — this is mid-context, never the start of a sequence; special
 * tokens are parsed, so a model with dedicated `</think>`-family tokens uses them rather
 * than spelling the tag out as plain text) and appends the result to [out].
 */
void tokenizeAppend(const llama_vocab *vocab, const std::string &text, std::vector<llama_token> &out) {
    const int needed = -llama_tokenize(
            vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, /* addSpecial */ false, true);
    if (needed <= 0) return;
    const size_t base = out.size();
    out.resize(base + needed);
    llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), out.data() + base, needed, false, true);
}

/** Queues [FORCE_THINK_CLOSE_TEXT] for nextToken() to emit — see its doc. */
void triggerForcedThinkClose(PamSession *session, const llama_vocab *vocab) {
    tokenizeAppend(vocab, FORCE_THINK_CLOSE_TEXT, session->forcedCloseTokens);
    session->forcedCloseIndex = 0;
    // Logically closed from here on, even though the forced tokens still have to trickle
    // out one nextToken() call at a time before the *stream* reflects it.
    session->inThinking = false;
    session->trackThinking = false;
    LOGI("pam_llama: forcing </think> close after %d thinking tokens (budget=%d)",
         session->thinkingTokenCount, session->thinkingBudgetTokens);
}

/**
 * Feeds [piece] — the text nextToken() is about to return — through the same `<think>` /
 * `</think>` detection `ThinkingStreamParser` (Kotlin) already does on the client side, but
 * natively: this is what lets generation *act* on crossing into a reasoning block, rather
 * than only classifying it after the fact. A rolling 32-character window is enough to catch
 * either tag split across any number of token pieces (`</think>` is 8 characters) without
 * holding the whole reply in memory.
 *
 * No-op once [PamSession::trackThinking] is false — set only for a chat turn with thinking
 * enabled (see sendChatMessage()), and cleared the moment thinking closes, naturally or
 * forced, so a turn's tail end (the answer) is never scanned for nothing.
 */
void updateThinkingState(PamSession *session, const llama_vocab *vocab, const std::string &piece) {
    if (!session->trackThinking) return;

    constexpr size_t kMaxKeep = 32; // » longest tag ("</think>", 8 chars), generous margin.
    auto trim = [&]() {
        if (session->tagScanBuffer.size() > kMaxKeep) {
            session->tagScanBuffer.erase(0, session->tagScanBuffer.size() - kMaxKeep);
        }
    };

    session->tagScanBuffer += piece;

    if (!session->sawThinkOpen) {
        const size_t pos = session->tagScanBuffer.find("<think>");
        if (pos == std::string::npos) {
            trim();
            return;
        }
        session->sawThinkOpen = true;
        session->inThinking = true;
        session->tagScanBuffer.erase(0, pos + 7); // strlen("<think>") — keep scanning below.
    }

    if (!session->inThinking) {
        trim();
        return;
    }

    session->thinkingTokenCount++;
    const size_t closePos = session->tagScanBuffer.find("</think>");
    if (closePos != std::string::npos) {
        session->inThinking = false;
        session->trackThinking = false; // closed naturally — nothing left to force.
        return;
    }
    trim();

    if (session->thinkingTokenCount >= session->thinkingBudgetTokens) {
        triggerForcedThinkClose(session, vocab);
    }
}

} // namespace

extern "C" {

// Set once deviceSupportsRequiredCpuFeatures() has run, so loadModel() (which can be
// called many times) does not need to re-derive it.
bool g_cpuFeaturesChecked = false;
bool g_cpuFeaturesOk = false;

JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_backendInit(JNIEnv *, jobject) {
    llama_backend_init();
    g_cpuFeaturesOk = deviceSupportsRequiredCpuFeatures();
    g_cpuFeaturesChecked = true;
    // Proves which CPU kernels are actually active on this device/build — see
    // CMakeLists.txt's comment on why this is a fixed -march rather than runtime
    // dispatch, and check for "DOTPROD = 1" / "FP16_VA = 1" in this line on device.
    LOGI("llama backend initialised — %s", llama_print_system_info());
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

    if (!g_cpuFeaturesChecked) {
        // Callers are expected to run backendInit() first; guard anyway so a missed
        // ordering fails safe (refuses to load) rather than risking a SIGILL.
        g_cpuFeaturesOk = deviceSupportsRequiredCpuFeatures();
        g_cpuFeaturesChecked = true;
    }
    if (!g_cpuFeaturesOk) {
        LOGE("pam_llama: refusing to load model — device lacks required CPU features (dotprod)");
        return 0;
    }

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
        jfloat temperature, jint topK, jfloat topP, jfloat presencePenalty, jlong seed, jstring grammar) {

    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return JNI_FALSE;

    releaseChain(session);
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->chatHistory.clear();
    session->chatPrevLen = 0;
    // Grammar-constrained one-shot generation never tracks a thinking budget — see
    // sendChatMessage()'s doc and PamSession::trackThinking. Reset defensively in case this
    // session previously ran a chat turn.
    session->trackThinking = false;

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

    session->chain = buildSamplerChain(vocab, temperature, topK, topP, presencePenalty, seed, grammarStd);
    session->hitLengthCap = false;
    session->pendingUtf8.clear();

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
    const double promptMs = elapsedMs(decodeStart);
    LOGI("pam_llama: one-shot prompt tokens=%d prompt_eval_ms=%.1f prompt_eval_%s",
         total, promptMs, tokensPerSecondText(total, promptMs).c_str());
    session->generated = 0;
    session->maxTokens = maxTokens;
    session->finished  = false;
    session->generationStart = std::chrono::steady_clock::now();
    return JNI_TRUE;
}

/**
 * Loads the multimodal projector (mmproj GGUF) for the model behind [handle]. Optional: a
 * session without it stays text-only and every other entry point is unchanged. Replaces a
 * previously loaded projector. The vision encoder always runs on CPU here (use_gpu=false),
 * matching the text model on this build.
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_loadVision(
        JNIEnv *env, jobject, jlong handle, jstring mmprojPath, jint threads) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr) return JNI_FALSE;
    const std::string path = jstringToStd(env, mmprojPath);

    if (session->mtmd != nullptr) {
        mtmd_free(session->mtmd);
        session->mtmd = nullptr;
    }
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu       = false;
    params.print_timings = false;
    params.n_threads     = threads;
    params.warmup        = false;
    const auto start = std::chrono::steady_clock::now();
    session->mtmd = mtmd_init_from_file(path.c_str(), session->model, params);
    if (session->mtmd == nullptr) {
        LOGE("pam_llama: failed to load mmproj %s", path.c_str());
        return JNI_FALSE;
    }
    if (!mtmd_support_vision(session->mtmd)) {
        LOGE("pam_llama: mmproj has no vision support");
        mtmd_free(session->mtmd);
        session->mtmd = nullptr;
        return JNI_FALSE;
    }
    LOGI("pam_llama: mmproj loaded in %.0f ms: %s", elapsedMs(start), path.c_str());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_freeVision(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->mtmd == nullptr) return;
    mtmd_free(session->mtmd);
    session->mtmd = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_hasVision(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    return (session != nullptr && session->mtmd != nullptr) ? JNI_TRUE : JNI_FALSE;
}

/** The media marker [startVisionGeneration] expects once per image in the prompt. */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_visionMarker(JNIEnv *env, jobject) {
    return env->NewStringUTF(mtmd_default_marker());
}

/** "imageTokens=N encodeMs=.. imageDecodeMs=.. textTokens=N textDecodeMs=.." of the last vision prompt. */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_lastVisionStats(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    return env->NewStringUTF(session == nullptr ? "" : session->lastVisionStats.c_str());
}

/**
 * Like startGeneration(), but [prompt] contains one media marker per entry of [imagePaths]
 * (files readable by this process). The images are decoded/resized by mtmd, encoded by the
 * vision tower and evaluated together with the text; tokens are then pulled with the
 * normal nextToken() with the same sampler chain (grammar included).
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_startVisionGeneration(
        JNIEnv *env, jobject, jlong handle, jstring prompt, jobjectArray imagePaths, jint maxTokens,
        jfloat temperature, jint topK, jfloat topP, jfloat presencePenalty, jlong seed, jstring grammar) {

    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->mtmd == nullptr) return JNI_FALSE;

    releaseChain(session);
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->chatHistory.clear();
    session->chatPrevLen = 0;
    session->trackThinking = false;
    session->lastVisionStats.clear();

    const std::string promptStd  = jstringToStd(env, prompt);
    const std::string grammarStd = jstringToStd(env, grammar);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);

    std::vector<mtmd_bitmap *> bitmaps;
    auto freeBitmaps = [&]() { for (auto *b : bitmaps) mtmd_bitmap_free(b); bitmaps.clear(); };
    const jsize nImages = imagePaths == nullptr ? 0 : env->GetArrayLength(imagePaths);
    for (jsize i = 0; i < nImages; i++) {
        auto *jpath = static_cast<jstring>(env->GetObjectArrayElement(imagePaths, i));
        const std::string p = jstringToStd(env, jpath);
        env->DeleteLocalRef(jpath);
        mtmd_helper_bitmap_wrapper w = mtmd_helper_bitmap_init_from_file(session->mtmd, p.c_str(), false);
        if (w.bitmap == nullptr) {
            LOGE("pam_llama: cannot load image %s", p.c_str());
            freeBitmaps();
            return JNI_FALSE;
        }
        bitmaps.push_back(w.bitmap);
    }

    mtmd_input_text text{};
    text.text          = promptStd.c_str();
    text.text_len      = promptStd.size();
    text.add_special   = true;
    text.parse_special = true;

    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    std::vector<const mtmd_bitmap *> bitmapPtrs(bitmaps.begin(), bitmaps.end());
    const int32_t tok = mtmd_tokenize(session->mtmd, chunks, &text, bitmapPtrs.data(), bitmapPtrs.size());
    freeBitmaps();
    if (tok != 0) {
        LOGE("pam_llama: mtmd_tokenize failed (%d)", tok);
        mtmd_input_chunks_free(chunks);
        return JNI_FALSE;
    }

    const int nBatch = (int) llama_n_batch(session->ctx);
    const size_t nChunks = mtmd_input_chunks_size(chunks);
    llama_pos nPast = 0;
    long imageTokens = 0, textTokens = 0;
    double encodeMs = 0, imageDecodeMs = 0, textDecodeMs = 0;
    bool ok = true;
    for (size_t i = 0; i < nChunks && ok; i++) {
        const mtmd_input_chunk *chunk = mtmd_input_chunks_get(chunks, i);
        const bool last = (i + 1 == nChunks);
        if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
            imageTokens += (long) mtmd_input_chunk_get_n_tokens(chunk);
            const auto t0 = std::chrono::steady_clock::now();
            if (mtmd_encode_chunk(session->mtmd, chunk) != 0) {
                LOGE("pam_llama: image encode failed");
                ok = false;
                break;
            }
            encodeMs += elapsedMs(t0);
            const auto t1 = std::chrono::steady_clock::now();
            llama_pos newPast = nPast;
            if (mtmd_helper_decode_image_chunk(session->mtmd, session->ctx, chunk,
                    mtmd_get_output_embd(session->mtmd), nPast, 0, nBatch, &newPast,
                    nullptr, nullptr) != 0) {
                LOGE("pam_llama: image decode failed");
                ok = false;
                break;
            }
            nPast = newPast;
            imageDecodeMs += elapsedMs(t1);
        } else {
            textTokens += (long) mtmd_input_chunk_get_n_tokens(chunk);
            const auto t0 = std::chrono::steady_clock::now();
            llama_pos newPast = nPast;
            if (mtmd_helper_eval_chunk_single(session->mtmd, session->ctx, chunk, nPast, 0, nBatch,
                    last, &newPast) != 0) {
                LOGE("pam_llama: text chunk decode failed");
                ok = false;
                break;
            }
            nPast = newPast;
            textDecodeMs += elapsedMs(t0);
        }
    }
    const bool lastIsText = nChunks > 0 &&
            mtmd_input_chunk_get_type(mtmd_input_chunks_get(chunks, nChunks - 1)) != MTMD_INPUT_CHUNK_TYPE_IMAGE;
    mtmd_input_chunks_free(chunks);
    if (!ok || !lastIsText) {
        if (ok) LOGE("pam_llama: vision prompt must end with text (logits)");
        llama_memory_clear(llama_get_memory(session->ctx), true);
        return JNI_FALSE;
    }

    char stats[192];
    snprintf(stats, sizeof(stats),
             "imageTokens=%ld encodeMs=%.0f imageDecodeMs=%.0f textTokens=%ld textDecodeMs=%.0f nPast=%d",
             imageTokens, encodeMs, imageDecodeMs, textTokens, textDecodeMs, (int) nPast);
    session->lastVisionStats = stats;
    LOGI("pam_llama: vision prompt %s", stats);

    session->chain = buildSamplerChain(vocab, temperature, topK, topP, presencePenalty, seed, grammarStd);
    session->hitLengthCap = false;
    session->pendingUtf8.clear();
    session->generated = 0;
    session->maxTokens = maxTokens;
    session->finished  = false;
    session->logitsReady = true;
    session->generationStart = std::chrono::steady_clock::now();
    return JNI_TRUE;
}

/**
 * @return the next token's text, or null when generation is complete.
 *
 * Normally samples one token from [PamSession::chain]. But when a thinking-budget forced
 * close is queued ([PamSession::forcedCloseTokens], set by [triggerForcedThinkClose] once
 * [updateThinkingState] sees the budget exceeded), this instead plays back the next queued
 * token in place of sampling — the model's own KV cache still absorbs it exactly like a
 * sampled token would (via the normal decode-then-advance sequence below), so the forced
 * text is indistinguishable from something the model generated once it resumes sampling
 * after the queue drains. The grammar-constrained one-shot path (startGeneration) never
 * sets [PamSession::trackThinking], so [forcedCloseTokens] is always empty there and this
 * is a pure pass-through — chat and extraction genuinely share this one function safely.
 */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_nextToken(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->finished || session->chain == nullptr) {
        return nullptr;
    }

    if (session->generated >= session->maxTokens) {
        session->hitLengthCap = true;
        releaseChain(session);
        return nullptr;
    }

    if (session->logitsReady) {
        // A vision prompt was already evaluated (logits for its last token are live).
        session->logitsReady = false;
    } else if (llama_decode(session->ctx, session->batch) != 0) {
        LOGE("decode failed at token %d", session->generated);
        releaseChain(session);
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const bool forced = session->forcedCloseIndex < session->forcedCloseTokens.size();
    llama_token next;
    if (forced) {
        next = session->forcedCloseTokens[session->forcedCloseIndex++];
    } else {
        // llama_sampler_sample() samples *and accepts* — calling llama_sampler_accept again
        // would advance the grammar state twice per token and abort the process.
        next = llama_sampler_sample(session->chain, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) {
            releaseChain(session);
            return nullptr;
        }
    }

    const std::string piece = tokenToPiece(vocab, next);
    updateThinkingState(session, vocab, piece);

    session->lastToken = next;
    session->batch     = llama_batch_get_one(&session->lastToken, 1);
    session->generated++;

    // A token can end in the middle of a multi-byte character (German umlauts, emoji are
    // often split across tokens). Handing those bytes to NewStringUTF is invalid Modified
    // UTF-8: CheckJNI aborts the whole :inference process on it. Only complete characters
    // go out; the tail waits for the next token.
    std::string out = session->pendingUtf8 + piece;
    const size_t complete = completeUtf8Prefix(out);
    session->pendingUtf8 = out.substr(complete);
    out.resize(complete);
    return utf8ToJString(env, out);
}

/** True when the last generation stopped at its token cap rather than at an end-of-generation token. */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_lastReplyHitLimit(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    return (session != nullptr && session->hitLengthCap) ? JNI_TRUE : JNI_FALSE;
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
    session->replyStartPos = -1;
    session->replyCheckpoint.clear();

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
    const double primeMs = elapsedMs(start);
    LOGI("pam_llama: session primed turns=%d tokens=%d ms=%.1f %s",
         (int) count, tokenCount, primeMs, tokensPerSecondText(tokenCount, primeMs).c_str());
    verifyKvConsistency(session, "prime");
    return JNI_TRUE;
}

/**
 * Begins a chat turn: appends [userText] to the session's history, renders the template
 * with an open assistant turn, and decodes only what is new since the last turn was
 * committed (see the class doc). Tokens are then pulled via the existing [nextToken].
 *
 * The reply is started with the same suffix Qwen3.5's template gives its generation prompt:
 * `<think>\n` when thinking is on (so it always begins inside a think block and the budget
 * always applies), `<think>\n\n</think>\n\n` when [noThink] is set. llama.cpp's
 * `llama_chat_apply_template` cannot pass the template's `enable_thinking` flag, and Qwen3.5
 * has no `/no_think` soft switch, so the suffix is decoded explicitly.
 *
 * On overflow (this turn would not fit in `n_ctx`) the oldest non-system turns are dropped
 * and the remaining history is fully re-decoded once — the one legitimate full re-decode
 * outside of [primeChatSession].
 *
 * ### Reply budget (was: "model finished thinking but produced no answer")
 *
 * [maxTokens] as received is a **cap**, not a promise — the actual per-turn budget is
 * `min(maxTokens, whatever still fits in n_ctx after this turn's prompt)`, so a
 * conversation near its context limit gets a smaller, still-safe budget rather than an
 * overflow. [thinkingBudgetTokens] (from `InferenceOverrides`'s Off/Low/High effort choice
 * on the Kotlin side — see `ConfigSpec.kt`) is a **sub-budget inside that same total**: it
 * can never eat into the last [kMinAnswerReserveTokens] tokens of it, so — even at the
 * highest thinking effort — there is always room left to answer once thinking closes,
 * forced or not. See [updateThinkingState] for how the forced close itself works.
 */
JNIEXPORT jboolean JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_sendChatMessage(
        JNIEnv *env, jobject, jlong handle, jstring userText, jint maxTokens,
        jfloat temperature, jint topK, jfloat topP, jfloat presencePenalty, jlong seed, jstring grammar, jboolean noThink,
        jint thinkingBudgetTokens) {

    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return JNI_FALSE;

    releaseChain(session);

    // Fresh per-turn thinking-budget state — see PamSession's doc on these fields. Reset
    // unconditionally (even when thinking is off) so a stale flag from an earlier turn can
    // never leak into this one.
    session->trackThinking      = false;
    session->sawThinkOpen       = false;
    session->inThinking         = false;
    session->thinkingTokenCount = 0;
    session->thinkingBudgetTokens = 0;
    session->tagScanBuffer.clear();
    session->forcedCloseTokens.clear();
    session->forcedCloseIndex = 0;

    // Qwen3.5 has no `/no_think` soft switch — thinking is chosen by what the generation
    // prompt ends with (its Jinja template appends `<think>\n` or `<think>\n\n</think>\n\n`).
    // llama_chat_apply_template cannot pass enable_thinking, so the same suffix is decoded
    // explicitly below; see kThinkOpenPrefill / kThinkClosedPrefill.
    std::string userStd = jstringToStd(env, userText);
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

    // History is only dropped to leave room for a normal-sized reply, not for the largest
    // thinking+answer cap: when the window is tight below that, the thinking budget shrinks
    // instead (see resolvedMaxTokens below) and the answer still keeps kMinAnswerReserveTokens.
    const int overflowReserve = std::min((int) maxTokens, kOverflowReserveTokens);
    if (nPast + diffTokens + overflowReserve > nCtx) {
        LOGI("pam_llama: context overflow (n_past=%d diff=%d reserve=%d n_ctx=%d) — dropping oldest turns",
             nPast, diffTokens, overflowReserve, nCtx);
        while (nPast + diffTokens + overflowReserve > nCtx && dropOldestChatTurn(session)) {
            llama_memory_clear(mem, true);
            session->chatPrevLen = 0;
            formatted = renderChatHistory(session, /* addAssistant */ true);
            nPast = (int) llama_memory_seq_pos_max(mem, 0) + 1; // always 0 right after clear
            diffTokens = currentDiffTokenCount();
        }
    }

    // The reply boundary is the end of the user turn (history rendered *without* the open
    // assistant tag). The tag and the prefill belong to the reply, so a stop or rewind returns
    // to exactly "history through this user turn", which is what chatPrevLen then records.
    const std::string userTurnText = renderChatHistory(session, /* addAssistant */ false);
    if (userTurnText.empty() || formatted.compare(0, userTurnText.size(), userTurnText) != 0) {
        invalidateKvCache(session, "sendChatMessage: template is not prefix-stable");
        return JNI_FALSE;
    }
    const std::string assistantOpen = formatted.substr(userTurnText.size());

    const size_t from = std::min((size_t) session->chatPrevLen, userTurnText.size());
    const std::string diff = userTurnText.substr(from);
    const bool isFirst = llama_memory_seq_pos_max(mem, 0) == -1;

    // The user turn is decoded in full *here* (not lazily by the first nextToken), so the
    // position and recurrent state captured below are exactly "right before this reply".
    int tokenCount = 0;
    const auto start = std::chrono::steady_clock::now();
    if (!decodeIntoSession(session, diff, isFirst, /* leaveRemainderForSampling */ false, &tokenCount)) {
        invalidateKvCache(session, "sendChatMessage: prompt decode failed");
        return JNI_FALSE;
    }
    const double promptMs = elapsedMs(start);
    LOGI("pam_llama: session decode new_tokens=%d n_past=%d prompt_eval_ms=%.1f prompt_eval_%s",
         tokenCount, nPast, promptMs, tokensPerSecondText(tokenCount, promptMs).c_str());

    // "Through the user turn" is what the KV holds; commitChatReply() extends this with the
    // answer, discardPendingReply() leaves it as is.
    session->chatPrevLen = (int) userTurnText.size();

    // Everything decoded from here on (assistant tag, prefill and the [nextToken] calls that
    // follow) is this reply's own — the position discardPendingReply()/commitChatReply()
    // roll back to. Recurrent models need their state saved at this exact point too.
    session->replyStartPos = (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1;
    takeReplyCheckpoint(session);

    // Thinking on: the reply starts *inside* an open think block, so both detectors (native
    // and the Kotlin ThinkingStreamParser) begin in thinking and the budget always applies,
    // however the model would have opened its answer. Off: an empty, closed block.
    const bool thinkingOpen = noThink == JNI_FALSE;
    const std::string &prefill = thinkingOpen ? kThinkOpenPrefill : kThinkClosedPrefill;
    // Its final chunk is left un-decoded for nextToken's first call to sample from.
    int prefillTokens = 0;
    if (!decodeIntoSession(session, assistantOpen + prefill, /* addSpecial */ false,
                           /* leaveRemainderForSampling */ true, &prefillTokens) || prefillTokens <= 0) {
        invalidateKvCache(session, "sendChatMessage: thinking prefill failed");
        return JNI_FALSE;
    }

    // The real per-turn ceiling: never more than what was requested, never more than what
    // still fits after this turn's prompt, floored so a near-full context still gets
    // *something* to answer with rather than 0.
    const int nPastAfterPrompt = (int) session->replyStartPos + prefillTokens;
    const int fitsInContext = std::max(kMinReplyTokens, nCtx - nPastAfterPrompt);
    const int resolvedMaxTokens = std::min((int) maxTokens, fitsInContext);

    // thinkingBudgetTokens is a sub-budget *inside* resolvedMaxTokens, never eating into the
    // last kMinAnswerReserveTokens of it — see this function's doc. Even a squeezed-out
    // budget stays at 1 rather than 0: the block is already open, so the forced close must
    // still be able to fire.
    session->sawThinkOpen = thinkingOpen;
    session->inThinking = thinkingOpen;
    session->thinkingBudgetTokens = (thinkingOpen && thinkingBudgetTokens > 0)
            ? std::max(1, std::min((int) thinkingBudgetTokens, resolvedMaxTokens - kMinAnswerReserveTokens))
            : 0;
    session->trackThinking = session->thinkingBudgetTokens > 0;
    LOGI("pam_llama: reply starts %s (prefill=%d tokens, thinking budget=%d, max=%d)",
         thinkingOpen ? "inside <think>" : "after an empty <think></think>",
         prefillTokens, session->thinkingBudgetTokens, resolvedMaxTokens);

    session->chain = buildSamplerChain(vocab, temperature, topK, topP, presencePenalty, seed, grammarStd);
    session->hitLengthCap = false;
    session->pendingUtf8.clear();
    session->generated = 0;
    session->maxTokens = resolvedMaxTokens;
    session->finished  = false;
    session->generationStart = std::chrono::steady_clock::now();
    return JNI_TRUE;
}

/**
 * Records the assistant's reply (thinking-stripped — see `SendChatMessageUseCase`'s KDoc on
 * why a reasoning trace never re-enters a future prompt) in the session's history, so the
 * *next* turn's template diff renders correctly.
 *
 * Always rewinds the raw reply (assistant tag, prefill, any thinking trace) and decodes the
 * clean `<|im_start|>assistant\n{answer}<|im_end|>\n` in its place, for thinking and
 * non-thinking turns alike. The KV then equals the rendered history, including the closing
 * `<|im_end|>\n` that sampling never decodes, and verifyKvConsistency() checks it.
 */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_commitChatReply(
        JNIEnv *env, jobject, jlong handle, jstring answer) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return;

    const int replyOpenTextLen = session->chatPrevLen; // text boundary right at replyStartPos.
    const llama_pos replyStartPos = session->replyStartPos;

    session->chatHistory.push_back({"assistant", jstringToStd(env, answer)});
    const std::string formatted = renderChatHistory(session, /* addAssistant */ false);
    // The reply is committed — nothing pending left to roll back.
    session->replyStartPos = -1;
    session->sawThinkOpen = false;

    if (replyStartPos < 0) {
        // No open reply: the KV was invalidated (or never primed) — re-prime on the next send.
        session->chatPrevLen = 0;
        session->replyCheckpoint.clear();
        llama_memory_clear(llama_get_memory(session->ctx), true);
        return;
    }

    // Uniform rule for thinking and non-thinking turns: the KV physically holds the raw reply
    // (open/empty think block included), never the clean text the history renders, so rewind
    // it and decode `<|im_start|>assistant\n{answer}<|im_end|>\n` in its place.
    if (!rollbackToReplyStart(session, replyStartPos)) {
        invalidateKvCache(session, "commitChatReply: could not rewind the reply");
        return;
    }
    session->replyCheckpoint.clear();

    const std::string closingDiff =
            formatted.substr(std::min((size_t) replyOpenTextLen, formatted.size()));
    int tokenCount = 0;
    const auto start = std::chrono::steady_clock::now();
    // Never the first-ever decode in this context — a reply always follows a user turn.
    if (!decodeIntoSession(session, closingDiff, /* addSpecial */ false,
                           /* leaveRemainderForSampling */ false, &tokenCount)) {
        // A stale KV would silently corrupt every later turn; start over instead.
        invalidateKvCache(session, "commitChatReply: failed to re-decode the clean turn");
        return;
    }
    session->chatPrevLen = (int) formatted.size();
    LOGI("pam_llama: commit re-decoded clean turn tokens=%d ms=%.1f", tokenCount, elapsedMs(start));
    verifyKvConsistency(session, "commit");
}

/**
 * Rolls back an interrupted reply: removes every token decoded since [sendChatMessage]
 * opened this turn (the reply's own tokens — the user's turn and everything before it are
 * left alone) from the KV cache, via `llama_memory_seq_rm(mem, 0, replyStartPos, -1)`.
 * `chatHistory` is untouched — the user's turn stays queued, no assistant turn is appended
 * — so the *next* [sendChatMessage] call naturally re-renders and decodes only the new user
 * turn, exactly as if this reply had never been generated. A no-op if no reply is open.
 */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_discardPendingReply(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->replyStartPos < 0) return;

    releaseChain(session);
    if (rollbackToReplyStart(session, session->replyStartPos)) {
        LOGI("pam_llama: discardPendingReply removed tokens from n_past=%d onward", (int) session->replyStartPos);
        session->replyStartPos = -1;
        session->replyCheckpoint.clear();
        // KV, chatHistory and chatPrevLen now all say "history through the last user turn".
        verifyKvConsistency(session, "stop");
    } else {
        invalidateKvCache(session, "discardPendingReply: rollback failed");
    }
}

/** Drops the standing chat session — its KV cache and history — e.g. on a conversation switch. */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_resetChatSession(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return;
    releaseChain(session);
    session->replyStartPos = -1;
    session->replyCheckpoint.clear();
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
