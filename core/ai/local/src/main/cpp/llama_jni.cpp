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
#include <atomic>
#include <chrono>
#include <cmath>
#include <string>
#include <vector>
#include <set>
#include <sys/auxv.h>
#include <asm/hwcap.h>

#include "llama.h"
#include "ggml-backend.h"

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

    // ── per-generation state ─────────────────────────────────────────────────
    llama_sampler *chain = nullptr;
    // The grammar sampler, when the request carries a grammar. Deliberately NOT part of [chain]:
    // nextToken() samples first and checks only the chosen token against it (see
    // sampleUnderGrammar), which is what keeps constrained decoding near unconstrained speed.
    llama_sampler *grammar = nullptr;
    // Scratch candidate array (one entry per vocabulary token), reused by sampleUnderGrammar.
    std::vector<llama_token_data> candidates;
    // How many sampled tokens the grammar rejected in this generation (each cost a full-vocabulary pass).
    int grammarRejections = 0;
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

    // ── prompt session ("read once, ask many short questions") ───────────────
    //
    // promptOpen: the KV cache holds exactly a decoded prefix and nothing else (see promptOpen()
    // below). promptPos is the KV position right after the prefix, promptCheckpoint the recurrent
    // state at that position (hybrid/recurrent models only). Every promptAsk() decodes its question
    // after the prefix, generates, then restores both, so the next question starts from the same
    // state. Anything else that touches the KV cache (a one-shot generation, a chat session, a new
    // context) clears promptOpen, and the Kotlin side re-opens on the next ask.
    bool promptOpen = false;
    llama_pos promptPos = 0;
    std::vector<uint8_t> promptCheckpoint;
    // The text and token count of the open prefix: opening the same text again while the cache still holds exactly it costs nothing
    // (the second stage of a reading opens the letter the first stage already read).
    std::string promptText;
    int promptTokenCount = 0;

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
    if (session->grammar != nullptr) {
        LOGI("pam_llama: grammar-constrained: %d of %d sampled tokens were rejected by the grammar and resampled",
             session->grammarRejections, session->generated);
    }
}

void releaseChain(PamSession *session) {
    if (session->chain != nullptr) {
        logGenerationEnd(session);
        llama_sampler_free(session->chain);
        session->chain = nullptr;
    }
    if (session->grammar != nullptr) {
        llama_sampler_free(session->grammar);
        session->grammar = nullptr;
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

/**
 * True for models whose memory has a recurrent component (Mamba/RWKV and hybrids such as
 * Qwen3.5's gated delta-net layers). That state is a running summary of every token so far,
 * so it cannot be trimmed back to an earlier position: `llama_memory_seq_rm(seq, p0, -1)` with
 * p0 > 0 returns false and changes nothing (see llama-memory-recurrent.cpp, seq_rm).
 */
bool hasRecurrentState(const PamSession *session) {
    return llama_model_is_hybrid(session->model) || llama_model_is_recurrent(session->model);
}

/**
 * Snapshots the recurrent state at the current position into [out] — llama-server's checkpoint
 * recipe. Leaves [out] empty for attention-only models (nothing to save) and on failure.
 */
void snapshotRecurrentState(PamSession *session, std::vector<uint8_t> &out) {
    out.clear();
    if (!hasRecurrentState(session)) return;
    const size_t size = llama_state_seq_get_size_ext(session->ctx, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
    std::vector<uint8_t> data(size);
    if (size == 0 ||
        llama_state_seq_get_data_ext(session->ctx, data.data(), size, 0, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) != size) {
        LOGE("pam_llama: could not checkpoint the recurrent state (size=%zu)", size);
        return;
    }
    out = std::move(data);
}

void takeReplyCheckpoint(PamSession *session) {
    snapshotRecurrentState(session, session->replyCheckpoint);
}

/**
 * Drops the KV cache back to [pos]. Attention-only models just trim; recurrent/hybrid ones first
 * restore [checkpoint] (taken at that position), then trim the attention cells beyond it
 * (llama-server does the same). Shared by the chat reply rewind and the prompt session.
 * @return false if the rollback could not be done, in which case nothing can be trusted.
 */
bool rollbackTo(PamSession *session, llama_pos pos, const std::vector<uint8_t> &checkpoint) {
    llama_memory_t mem = llama_get_memory(session->ctx);
    if (hasRecurrentState(session)) {
        if (checkpoint.empty()) return false;
        if (llama_state_seq_set_data_ext(session->ctx, checkpoint.data(), checkpoint.size(), 0,
                                         LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY) == 0) {
            return false;
        }
    }
    return llama_memory_seq_rm(mem, 0, pos, -1);
}

/** Drops the KV cache back to [pos] — where the open reply began. See [rollbackTo]. */
bool rollbackToReplyStart(PamSession *session, llama_pos pos) {
    return rollbackTo(session, pos, session->replyCheckpoint);
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
    session->promptOpen = false;
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

/**
 * Builds the sampler chain shared by the raw one-shot path and the chat-session path.
 *
 * The grammar sampler, if the request has a grammar and it parses, is returned through
 * [grammarOut] and is NOT in the chain: [sampleUnderGrammar] applies it. (Applied first in the
 * chain, it masked all ~150k vocabulary tokens on every token and cost about as much as the
 * model's own forward pass; see that function.) [grammarOut] is null when there is no grammar.
 */
llama_sampler *buildSamplerChain(const llama_vocab *vocab, float temperature, int topK, float topP,
                                  float presencePenalty, jlong seed, const std::string &grammarStd,
                                  llama_sampler **grammarOut) {
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());

    *grammarOut = nullptr;
    if (!grammarStd.empty()) {
        *grammarOut = llama_sampler_init_grammar(vocab, grammarStd.c_str(), "root");
        if (*grammarOut == nullptr) {
            LOGE("grammar failed to parse — continuing unconstrained");
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
 * Picks the next token under [PamSession::grammar], the way llama.cpp's own
 * `common_sampler_sample` does (grammar_first = false):
 *  1. run the ordinary chain (greedy, or top-k/top-p/temperature) on the raw logits;
 *  2. check only that one token against the grammar (a one-candidate array, cheap);
 *  3. if it is valid, take it; only if the grammar rejects it, apply the grammar to the full
 *     candidate array and run the chain again over what is left.
 * The result is the same token grammar-first would give, but step 3 is rare (a few percent of
 * tokens for a JSON answer), where grammar-first masked the whole vocabulary on every token.
 *
 * Then advances the grammar (and the chain's history samplers) with the token chosen. The
 * rejected first choice is never accepted anywhere. Must not be used with [llama_sampler_sample],
 * which would accept the token a second time.
 *
 * @return the token, or the end-of-sequence token when the grammar leaves no valid token at all.
 */
llama_token sampleUnderGrammar(PamSession *session, const llama_vocab *vocab) {
    const int32_t nVocab = llama_vocab_n_tokens(vocab);
    if ((int32_t) session->candidates.size() != nVocab) session->candidates.resize(nVocab);

    auto fill = [&](llama_token_data_array *array) {
        const float *logits = llama_get_logits_ith(session->ctx, -1);
        for (llama_token id = 0; id < nVocab; ++id) session->candidates[id] = {id, logits[id], 0.0f};
        *array = {session->candidates.data(), (size_t) nVocab, -1, false};
    };

    llama_token_data_array all;
    fill(&all);
    llama_sampler_apply(session->chain, &all);
    llama_token id = all.data[all.selected].id;

    llama_token_data single = {id, 1.0f, 0.0f};
    llama_token_data_array singleArray = {&single, 1, -1, false};
    llama_sampler_apply(session->grammar, &singleArray);
    if (singleArray.data[0].logit == -INFINITY) {
        session->grammarRejections++;
        fill(&all);
        llama_sampler_apply(session->grammar, &all);
        llama_sampler_apply(session->chain, &all);
        if (all.data[all.selected].logit == -INFINITY) {
            LOGE("grammar allows no token here — ending the reply");
            return llama_vocab_eos(vocab);
        }
        id = all.data[all.selected].id;
    }

    llama_sampler_accept(session->grammar, id);
    llama_sampler_accept(session->chain, id);
    return id;
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
    session->promptOpen = false; // a fresh context has an empty KV cache
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
    session->promptOpen = false; // the KV cache no longer holds a prompt session's prefix
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

    session->chain = buildSamplerChain(vocab, temperature, topK, topP, presencePenalty, seed, grammarStd,
                                       &session->grammar);
    session->grammarRejections = 0;
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

    if (llama_decode(session->ctx, session->batch) != 0) {
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
        if (session->grammar != nullptr) {
            // Sample first, check only the chosen token against the grammar; accepts it too.
            next = sampleUnderGrammar(session, vocab);
        } else {
            // llama_sampler_sample() samples *and accepts* — calling llama_sampler_accept again
            // would advance the sampler state twice per token.
            next = llama_sampler_sample(session->chain, session->ctx, -1);
        }
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
    session->promptOpen = false;
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
    session->promptOpen = false; // decoding history into the KV cache: no longer just a prompt prefix

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
    session->promptOpen = false; // a chat turn decodes into the KV cache

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

    session->chain = buildSamplerChain(vocab, temperature, topK, topP, presencePenalty, seed, grammarStd,
                                       &session->grammar);
    session->grammarRejections = 0;
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
    session->promptOpen = false;
    session->replyStartPos = -1;
    session->replyCheckpoint.clear();
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->chatHistory.clear();
    session->chatPrevLen = 0;
}

// ── prompt session: read once, ask many short questions ─────────────────────────────────
//
// The same KV machinery the chat rewind uses (a recurrent-state checkpoint plus an attention-cell
// trim, see rollbackTo), aimed at a different job: a long prefix (a letter and its candidate
// table) is decoded once, and each of many short questions is decoded after it, answered under its
// own grammar, and then rolled back, so every question starts from the same state and no question
// pays for the prefix again.
//
// Blocking by design: an answer is a few dozen tokens, so promptAsk() runs the whole generation
// and returns the text (no token stream, no callback). promptCancel() stops it between tokens.

std::atomic<bool> g_promptCancel{false};

// A prefix must leave at least this many tokens of the context window for the questions.
constexpr int kMinPromptReserveTokens = 64;

/**
 * Decodes [prefix] (already in the model's chat format; the caller rendered the template) into a
 * cleared KV cache and remembers where it ends: the position, and for hybrid/recurrent models the
 * recurrent state (a checkpoint). Takes the KV cache over from any chat session (its history is
 * dropped; the Kotlin side re-primes) and from a one-shot generation.
 *
 * @return the number of prefix tokens, -1 on a failure, -2 when the prefix leaves no room for questions.
 */
JNIEXPORT jint JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_promptOpen(JNIEnv *env, jobject, jlong handle, jstring prefix) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr) return -1;

    releaseChain(session);
    llama_memory_t mem = llama_get_memory(session->ctx);
    const std::string text = jstringToStd(env, prefix);
    // The same prefix is already open and the cache holds exactly it (every other user of the cache clears promptOpen): nothing to decode.
    if (session->promptOpen && !session->promptText.empty() && text == session->promptText &&
        (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1 == session->promptPos) {
        LOGI("pam_llama: prompt session reused tokens=%d n_past=%d (the same prefix was still open)",
             session->promptTokenCount, (int) session->promptPos);
        return session->promptTokenCount;
    }
    llama_memory_clear(mem, true);
    session->promptOpen = false;
    session->promptCheckpoint.clear();
    session->promptText.clear();
    session->chatHistory.clear();
    session->chatPrevLen = 0;
    session->replyStartPos = -1;
    session->replyCheckpoint.clear();
    session->trackThinking = false;

    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const int needed = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, true, true);
    if (needed <= 0) return -1;
    const int nCtx = (int) llama_n_ctx(session->ctx);
    if (needed + kMinPromptReserveTokens > nCtx) {
        LOGE("pam_llama: prompt prefix of %d tokens leaves no room in n_ctx=%d", needed, nCtx);
        return -2;
    }

    int tokenCount = 0;
    const auto start = std::chrono::steady_clock::now();
    if (!decodeIntoSession(session, text, /* addSpecial */ true, /* leaveRemainderForSampling */ false, &tokenCount)) {
        llama_memory_clear(mem, true);
        return -1;
    }
    session->promptPos = (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1;
    snapshotRecurrentState(session, session->promptCheckpoint);
    if (hasRecurrentState(session) && session->promptCheckpoint.empty()) {
        llama_memory_clear(mem, true);
        return -1;
    }
    session->promptOpen = true;
    session->promptText = text;
    session->promptTokenCount = tokenCount;
    const double ms = elapsedMs(start);
    LOGI("pam_llama: prompt session opened tokens=%d n_past=%d ms=%.1f prompt_eval_%s checkpoint=%zu bytes",
         tokenCount, (int) session->promptPos, ms, tokensPerSecondText(tokenCount, ms).c_str(),
         session->promptCheckpoint.size());
    return tokenCount;
}

/**
 * Decodes [question] after the prefix, generates greedily under [grammar] (null or empty for
 * none) up to [maxTokens], and rolls the KV cache back to the prefix. Returns the answer, or null
 * when the session was lost (the KV no longer holds exactly the prefix: something else used the
 * context since; the caller re-opens and asks again), the question did not fit, decoding failed or
 * [promptCancel] was called. The prefix is intact after a non-null answer; after a null one the
 * session is closed unless the rollback itself worked.
 */
JNIEXPORT jstring JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_promptAsk(
        JNIEnv *env, jobject, jlong handle, jstring question, jstring grammar, jint maxTokens) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr || !session->promptOpen) return nullptr;

    g_promptCancel.store(false);
    releaseChain(session);
    llama_memory_t mem = llama_get_memory(session->ctx);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const int nCtx = (int) llama_n_ctx(session->ctx);

    // Belt and braces: the flag says the prefix is there; the cache must agree.
    if ((llama_pos) llama_memory_seq_pos_max(mem, 0) + 1 != session->promptPos) {
        LOGE("pam_llama: prompt session lost (n_past=%d, prefix=%d)",
             (int) llama_memory_seq_pos_max(mem, 0) + 1, (int) session->promptPos);
        session->promptOpen = false;
        return nullptr;
    }

    auto rollback = [&]() {
        releaseChain(session);
        if (!rollbackTo(session, session->promptPos, session->promptCheckpoint) ||
            (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1 != session->promptPos) {
            LOGE("pam_llama: prompt rollback failed — closing the session; the next ask re-reads the prefix");
            llama_memory_clear(mem, true);
            session->promptOpen = false;
        }
    };

    const std::string questionStd = jstringToStd(env, question);
    const std::string grammarStd = jstringToStd(env, grammar);

    int questionTokens = 0;
    const auto start = std::chrono::steady_clock::now();
    if (!decodeIntoSession(session, questionStd, /* addSpecial */ false, /* leaveRemainderForSampling */ false,
                           &questionTokens) || questionTokens <= 0) {
        rollback();
        return nullptr;
    }
    const double questionMs = elapsedMs(start);
    const int budget = std::min((int) maxTokens, nCtx - ((int) session->promptPos + questionTokens));
    if (budget <= 0) {
        rollback();
        return nullptr;
    }

    session->chain = buildSamplerChain(vocab, /* temperature */ 0.0f, 40, 0.9f, 0.0f, -1, grammarStd, &session->grammar);
    session->grammarRejections = 0;
    session->hitLengthCap = false;
    session->pendingUtf8.clear();
    session->generated = 0;
    session->maxTokens = budget;
    session->finished = false;
    session->generationStart = std::chrono::steady_clock::now();

    std::string out;
    bool failed = false;
    while (session->generated < budget) {
        if (g_promptCancel.load()) {
            failed = true;
            break;
        }
        llama_token next;
        if (session->grammar != nullptr) {
            next = sampleUnderGrammar(session, vocab);
        } else {
            next = llama_sampler_sample(session->chain, session->ctx, -1);
        }
        if (llama_vocab_is_eog(vocab, next)) break;
        out += tokenToPiece(vocab, next);
        session->generated++;
        if (session->generated >= budget) {
            session->hitLengthCap = true;
            break;
        }
        session->lastToken = next;
        llama_batch one = llama_batch_get_one(&session->lastToken, 1);
        if (llama_decode(session->ctx, one) != 0) {
            LOGE("pam_llama: prompt ask decode failed at token %d", session->generated);
            failed = true;
            break;
        }
    }
    const int generated = session->generated;
    const double totalMs = elapsedMs(start);
    const int rejections = session->grammarRejections;
    const bool capped = session->hitLengthCap;
    rollback(); // frees the sampler chain (logs decode tok/s) and restores the prefix state
    LOGI("pam_llama: prompt ask question_tokens=%d question_ms=%.1f generated=%d total_ms=%.1f rejections=%d capped=%d %s",
         questionTokens, questionMs, generated, totalMs, rejections, capped ? 1 : 0,
         tokensPerSecondText(generated, totalMs - questionMs).c_str());
    if (failed) return nullptr;
    return utf8ToJString(env, out);
}

/** First token of [s] (special tokens not parsed, no BOS), or -1 when it tokenises to nothing. */
static llama_token firstTokenOf(const llama_vocab *vocab, const std::string &s) {
    if (s.empty()) return -1;
    std::vector<llama_token> toks(16);
    const int n = llama_tokenize(vocab, s.c_str(), (int32_t) s.size(), toks.data(), (int32_t) toks.size(),
                                 /* addSpecial */ false, /* parseSpecial */ false);
    return n > 0 ? toks[0] : -1;
}

/**
 * Label-free scoring after the open prefix. For each of [continuations]: decodes it after the prefix,
 * reads the logits at the last position, takes log-odds = logit([yes]) - logit([no]) (the log-odds of
 * the two answer tokens against each other, whatever else the model might say), and rolls back to the
 * prefix, so every continuation starts from the same state. No sampling, no grammar, no generation:
 * one forward pass per continuation. [yes] and [no] are text; the first token of each is used.
 *
 * [shared] (may be empty) is a second level of the prefix tree: decoded once after the prefix and
 * checkpointed (the recurrent state too, for hybrid models like Qwen3.5), it is what every continuation
 * is rolled back to instead of the bare prefix; after the last continuation the state is the prefix again.
 * The logits are those of the text `prefix + shared + continuation`, exactly as if it had been one piece.
 *
 * Logs, per call, the tokens, decode ms and rollback ms of every continuation (`tokens/decode_ms/rollback_ms`).
 *
 * @return one log-odds value per continuation, or null when the session was lost (re-open and retry),
 *   a token is missing, a continuation did not fit, decoding failed or [promptCancel] was called.
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_promptScore(
        JNIEnv *env, jobject, jlong handle, jstring shared, jobjectArray continuations, jstring yes, jstring no) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr || !session->promptOpen) return nullptr;

    g_promptCancel.store(false);
    releaseChain(session);
    llama_memory_t mem = llama_get_memory(session->ctx);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const int nCtx = (int) llama_n_ctx(session->ctx);

    if ((llama_pos) llama_memory_seq_pos_max(mem, 0) + 1 != session->promptPos) {
        LOGE("pam_llama: prompt session lost before scoring (n_past=%d, prefix=%d)",
             (int) llama_memory_seq_pos_max(mem, 0) + 1, (int) session->promptPos);
        session->promptOpen = false;
        return nullptr;
    }

    const llama_token yesTok = firstTokenOf(vocab, jstringToStd(env, yes));
    const llama_token noTok = firstTokenOf(vocab, jstringToStd(env, no));
    if (yesTok < 0 || noTok < 0) {
        LOGE("pam_llama: promptScore: the yes/no words do not tokenise");
        return nullptr;
    }

    // Restores the KV cache and recurrent state to a level of the prefix tree (position + the checkpoint taken there).
    // A failure closes the session: nothing after it can be trusted.
    auto restore = [&](llama_pos pos, const std::vector<uint8_t> &checkpoint) -> bool {
        if (!rollbackTo(session, pos, checkpoint) || (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1 != pos) {
            LOGE("pam_llama: prompt rollback failed while scoring — closing the session");
            llama_memory_clear(mem, true);
            session->promptOpen = false;
            return false;
        }
        return true;
    };

    const auto start = std::chrono::steady_clock::now();
    double sharedMs = 0.0;
    int sharedTokens = 0;

    // Level 2 of the prefix tree: the text every continuation shares, decoded once after the prefix with its own
    // checkpoint, so each continuation pays only for what is its own.
    llama_pos basePos = session->promptPos;
    std::vector<uint8_t> sharedCheckpoint;
    const std::vector<uint8_t> *baseCheckpoint = &session->promptCheckpoint;
    const std::string sharedText = jstringToStd(env, shared);
    if (!sharedText.empty()) {
        if (!decodeIntoSession(session, sharedText, /* addSpecial */ false, /* leaveRemainderForSampling */ false,
                               &sharedTokens)) {
            restore(session->promptPos, session->promptCheckpoint);
            return nullptr;
        }
        if (sharedTokens > 0) {
            basePos = (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1;
            snapshotRecurrentState(session, sharedCheckpoint);
            if (hasRecurrentState(session) && sharedCheckpoint.empty()) {
                restore(session->promptPos, session->promptCheckpoint);
                return nullptr;
            }
            baseCheckpoint = &sharedCheckpoint;
        }
        sharedMs = elapsedMs(start);
    }
    // Back to the prefix (every way out of here): the shared level is a scratch level of this call only.
    auto leave = [&]() -> bool {
        return basePos == session->promptPos ? true : restore(session->promptPos, session->promptCheckpoint);
    };

    const jsize count = env->GetArrayLength(continuations);
    std::vector<double> scores((size_t) count, 0.0);
    int totalTokens = 0;
    double decodeMs = 0.0;
    double rollbackMs = 0.0;
    std::string perScore;
    for (jsize i = 0; i < count; ++i) {
        if (g_promptCancel.load()) {
            leave();
            return nullptr;
        }
        auto js = (jstring) env->GetObjectArrayElement(continuations, i);
        const std::string text = jstringToStd(env, js);
        env->DeleteLocalRef(js);

        int tokens = 0;
        const auto decodeStart = std::chrono::steady_clock::now();
        if (!decodeIntoSession(session, text, /* addSpecial */ false, /* leaveRemainderForSampling */ false, &tokens) ||
            tokens <= 0) {
            if (restore(basePos, *baseCheckpoint)) leave();
            return nullptr;
        }
        if ((int) basePos + tokens >= nCtx) {
            if (restore(basePos, *baseCheckpoint)) leave();
            return nullptr;
        }
        totalTokens += tokens;
        const float *logits = llama_get_logits_ith(session->ctx, -1);
        if (logits == nullptr) {
            if (restore(basePos, *baseCheckpoint)) leave();
            return nullptr;
        }
        scores[(size_t) i] = (double) logits[yesTok] - (double) logits[noTok];
        const double d = elapsedMs(decodeStart);
        decodeMs += d;
        const auto rollbackStart = std::chrono::steady_clock::now();
        if (!restore(basePos, *baseCheckpoint)) return nullptr;
        const double r = elapsedMs(rollbackStart);
        rollbackMs += r;
        if (perScore.size() < 600) {
            char buf[48];
            snprintf(buf, sizeof(buf), " %d/%.0f/%.0f", tokens, d, r);
            perScore += buf;
        }
    }
    if (!leave()) return nullptr;
    LOGI("pam_llama: prompt score continuations=%d tokens=%d total_ms=%.1f shared_tokens=%d shared_ms=%.1f decode_ms=%.1f "
         "rollback_ms=%.1f tokens/decode_ms/rollback_ms:%s",
         (int) count, totalTokens, elapsedMs(start), sharedTokens, sharedMs, decodeMs, rollbackMs, perScore.c_str());

    jdoubleArray out = env->NewDoubleArray(count);
    if (out == nullptr) return nullptr;
    env->SetDoubleArrayRegion(out, 0, count, scores.data());
    return out;
}

/**
 * Label-free scoring of a grid after the open prefix: every one of [heads] followed by every one of [asks], as the text
 * `prefix + shared + head + ask`. A three-level prefix tree: [shared] is decoded once and checkpointed, each head is decoded once
 * after it and checkpointed, and each ask is decoded after its head, read and rolled back to the head. The work is
 * `shared + heads + heads * asks` instead of `heads * asks` times everything: what the questions about one value (the heads) under
 * several statements (the asks) and one zone block (shared) have in common is paid for once. Scores are logit(yes) - logit(no) as
 * in [promptScore].
 *
 * @return heads * asks scores, head-major (`scores[i * asks + j]` is head i under ask j), or null under the same conditions as
 *   [promptScore]; the state is the prefix again whatever happens (or the session is closed when that failed).
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_promptScoreGrid(
        JNIEnv *env, jobject, jlong handle, jstring shared, jobjectArray heads, jobjectArray asks, jstring yes, jstring no) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr || !session->promptOpen) return nullptr;

    g_promptCancel.store(false);
    releaseChain(session);
    llama_memory_t mem = llama_get_memory(session->ctx);
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    const int nCtx = (int) llama_n_ctx(session->ctx);

    if ((llama_pos) llama_memory_seq_pos_max(mem, 0) + 1 != session->promptPos) {
        LOGE("pam_llama: prompt session lost before grid scoring (n_past=%d, prefix=%d)",
             (int) llama_memory_seq_pos_max(mem, 0) + 1, (int) session->promptPos);
        session->promptOpen = false;
        return nullptr;
    }
    const llama_token yesTok = firstTokenOf(vocab, jstringToStd(env, yes));
    const llama_token noTok = firstTokenOf(vocab, jstringToStd(env, no));
    if (yesTok < 0 || noTok < 0) {
        LOGE("pam_llama: promptScoreGrid: the yes/no words do not tokenise");
        return nullptr;
    }

    auto restore = [&](llama_pos pos, const std::vector<uint8_t> &checkpoint) -> bool {
        if (!rollbackTo(session, pos, checkpoint) || (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1 != pos) {
            LOGE("pam_llama: prompt rollback failed while grid scoring — closing the session");
            llama_memory_clear(mem, true);
            session->promptOpen = false;
            return false;
        }
        return true;
    };
    auto strings = [&](jobjectArray array) {
        std::vector<std::string> out;
        const jsize n = env->GetArrayLength(array);
        for (jsize i = 0; i < n; ++i) {
            auto js = (jstring) env->GetObjectArrayElement(array, i);
            out.push_back(jstringToStd(env, js));
            env->DeleteLocalRef(js);
        }
        return out;
    };
    const std::vector<std::string> headTexts = strings(heads);
    const std::vector<std::string> askTexts = strings(asks);
    const size_t nHeads = headTexts.size();
    const size_t nAsks = askTexts.size();
    std::vector<double> scores(nHeads * nAsks, 0.0);

    const auto start = std::chrono::steady_clock::now();
    int totalTokens = 0;
    int sharedTokens = 0;
    double rollbackMs = 0.0;

    // Level 1: the shared text.
    llama_pos sharedPos = session->promptPos;
    std::vector<uint8_t> sharedCheckpoint;
    const std::string sharedText = jstringToStd(env, shared);
    if (!sharedText.empty()) {
        if (!decodeIntoSession(session, sharedText, false, false, &sharedTokens)) {
            restore(session->promptPos, session->promptCheckpoint);
            return nullptr;
        }
        if (sharedTokens > 0) {
            sharedPos = (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1;
            snapshotRecurrentState(session, sharedCheckpoint);
            if (hasRecurrentState(session) && sharedCheckpoint.empty()) {
                restore(session->promptPos, session->promptCheckpoint);
                return nullptr;
            }
        }
    }
    const std::vector<uint8_t> &level1 = sharedTokens > 0 ? sharedCheckpoint : session->promptCheckpoint;
    auto leave = [&]() -> bool { return sharedPos == session->promptPos ? true : restore(session->promptPos, session->promptCheckpoint); };

    // Reads the last decoded position's yes/no log-odds.
    auto readScore = [&](double *into) -> bool {
        const float *logits = llama_get_logits_ith(session->ctx, -1);
        if (logits == nullptr) return false;
        *into = (double) logits[yesTok] - (double) logits[noTok];
        return true;
    };

    for (size_t i = 0; i < nHeads; ++i) {
        if (g_promptCancel.load()) {
            if (restore(sharedPos, level1)) leave();
            return nullptr;
        }
        int tokens = 0;
        if (nAsks == 1) {
            // Nothing to share with a single ask: the head and the ask are one continuation.
            if (!decodeIntoSession(session, headTexts[i] + askTexts[0], false, false, &tokens) || tokens <= 0 ||
                (int) sharedPos + tokens >= nCtx || !readScore(&scores[i])) {
                if (restore(sharedPos, level1)) leave();
                return nullptr;
            }
            totalTokens += tokens;
            const auto r = std::chrono::steady_clock::now();
            if (!restore(sharedPos, level1)) return nullptr;
            rollbackMs += elapsedMs(r);
            continue;
        }

        // Level 2: the head, decoded once for all its asks.
        int headTokens = 0;
        if (!headTexts[i].empty() && !decodeIntoSession(session, headTexts[i], false, false, &headTokens)) {
            if (restore(sharedPos, level1)) leave();
            return nullptr;
        }
        totalTokens += headTokens;
        const llama_pos headPos = (llama_pos) llama_memory_seq_pos_max(mem, 0) + 1;
        std::vector<uint8_t> headCheckpoint;
        snapshotRecurrentState(session, headCheckpoint);
        if (hasRecurrentState(session) && headCheckpoint.empty()) {
            if (restore(sharedPos, level1)) leave();
            return nullptr;
        }
        for (size_t j = 0; j < nAsks; ++j) {
            int askTokens = 0;
            if (!decodeIntoSession(session, askTexts[j], false, false, &askTokens) || askTokens <= 0 ||
                (int) headPos + askTokens >= nCtx || !readScore(&scores[i * nAsks + j])) {
                if (restore(headPos, headCheckpoint) && restore(sharedPos, level1)) leave();
                return nullptr;
            }
            totalTokens += askTokens;
            const auto r = std::chrono::steady_clock::now();
            if (!restore(headPos, headCheckpoint)) return nullptr;
            rollbackMs += elapsedMs(r);
        }
        if (!restore(sharedPos, level1)) return nullptr;
    }
    if (!leave()) return nullptr;
    LOGI("pam_llama: prompt score grid heads=%d asks=%d tokens=%d shared_tokens=%d total_ms=%.1f rollback_ms=%.1f",
         (int) nHeads, (int) nAsks, totalTokens, sharedTokens, elapsedMs(start), rollbackMs);

    const jsize total = (jsize) scores.size();
    jdoubleArray out = env->NewDoubleArray(total);
    if (out == nullptr) return nullptr;
    env->SetDoubleArrayRegion(out, 0, total, scores.data());
    return out;
}

/** Stops a running [promptAsk] between tokens (it then returns null). Callable from any thread. */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_promptCancel(JNIEnv *, jobject) {
    g_promptCancel.store(true);
}

/** Drops the prompt session and its KV state. Safe when none is open. */
JNIEXPORT void JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_promptClose(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr) return;
    if (session->promptOpen) {
        releaseChain(session);
        llama_memory_clear(llama_get_memory(session->ctx), true);
    }
    session->promptOpen = false;
    session->promptText.clear();
    std::vector<uint8_t>().swap(session->promptCheckpoint);
}

/** [text] in tokens for the loaded model (special tokens parsed, no BOS), or -1 with no model. */
JNIEXPORT jint JNICALL
Java_com_postsaimanager_core_ai_local_LlamaNative_countTokens(JNIEnv *env, jobject, jlong handle, jstring text) {
    auto *session = reinterpret_cast<PamSession *>(handle);
    if (session == nullptr || session->model == nullptr) return -1;
    const std::string s = jstringToStd(env, text);
    if (s.empty()) return 0;
    const llama_vocab *vocab = llama_model_get_vocab(session->model);
    return -llama_tokenize(vocab, s.c_str(), (int32_t) s.size(), nullptr, 0, /* addSpecial */ false, true);
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
