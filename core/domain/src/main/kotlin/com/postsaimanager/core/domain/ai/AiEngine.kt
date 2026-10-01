package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.SamplingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The port through which the app asks a model to generate text.
 *
 * Lives in `:core:domain` because features are only permitted to see the domain layer
 * (architecture rule 1). `:core:ai:local` implements it over llama.cpp; a future
 * implementation could sit on anything, and no feature would change.
 *
 * Deliberately **not** implemented by online providers. Cloud escalation is a separate type
 * (`OnlineEscalationService`) taking an `ApprovedPayload`, precisely so cloud can never be
 * substituted here and quietly bypass the consent gate — see architecture rule 4.
 *
 * ### One native context, two callers
 *
 * `AiExtractionUseCase` (background document reading) and `SendChatMessageUseCase` (chat) both
 * call this same engine, and — since document processing moved to the background
 * (`DocumentProcessingWorker`) — can now genuinely overlap in wall-clock time: a chat reply can
 * be mid-stream while a queued document is being read, or a background read can land between
 * two chat sends in the same conversation. Every implementation guarantees:
 *
 * 1. **Never interleaved on the native context.** [generate], [sendChatMessage],
 *    [ensureChatSession], [commitChatReply], [discardPendingReply] and [resetChatSession] are
 *    serialised end-to-end — a caller's whole call (including the entire token stream for
 *    [generate]/[sendChatMessage], not just the request that starts it) completes before the
 *    next queued caller's begins. [LocalAiEngine][com.postsaimanager.core.ai.local.LocalAiEngine]
 *    does this with a `Mutex` held for the duration of each call;
 *    [RemoteAiEngine][com.postsaimanager.core.ai.local.RemoteAiEngine] mirrors it on the app-
 *    process side of the AIDL boundary for the same reason — see its KDoc for the specific bug
 *    this closes (`InferenceService`'s single-threaded executor already serialises native
 *    calls, but its cancellation flag is shared across callers, so an un-mutexed extraction
 *    call queued behind an in-flight chat stream could reset a Stop request meant for the chat
 *    turn). The mutex is FIFO-fair, so a chat send queued behind a document read waits at most
 *    for *that one* [generate] call to finish, never for the rest of a multi-page pipeline —
 *    each page/document is its own call.
 * 2. **A primed chat session never survives a call that could have changed what is in the KV
 *    cache.** [ensureChatSession] tracks which conversation's session is open; any [generate]
 *    call (extraction's one-shot grammar path always clears the KV cache — see
 *    `LlamaNative`/`llama_jni.cpp`'s `startGeneration` doc) or [load]-triggered reload/unload
 *    clears that tracking, so the *next* [ensureChatSession] for that conversation re-primes
 *    instead of silently decoding a diff against a cache that no longer holds what it thinks it
 *    holds. [isChatSessionPrimed] exposes this tracking read-only.
 */
interface AiEngine {

    /**
     * Where the model is in its load lifecycle — see [ModelLoadState]. Single-flight and
     * generation-guarded on the implementation side: concurrent `load` callers observe the
     * same in-flight transition rather than racing separate native calls.
     */
    val state: StateFlow<ModelLoadState>

    val isReady: Boolean

    /**
     * True while another caller currently holds the native context — a [generate] or
     * [sendChatMessage] call already in flight (see [AiEngine]'s class KDoc, "One native
     * context, two callers"). A cheap, non-suspending read of local state, never a native call
     * or an AIDL round trip, so a caller about to itself wait behind that in-flight call can
     * say so instead of leaving the wait unexplained — see
     * [SendChatMessageUseCase][com.postsaimanager.core.domain.usecase.SendChatMessageUseCase]'s
     * `ChatTurn.PreparingModel(reason = …)`. Approximate by nature (it can flip the instant
     * after being read); only ever used to decide what to *say*, never what to *do*.
     */
    val isBusy: Boolean

    /**
     * Loads a model, replacing any currently loaded one.
     *
     * @param config everything llama.cpp needs to load the model and sample from it — the
     *   single source of truth, see [InferenceConfig]. Callers get one from
     *   [ActiveModelProvider] rather than assembling loose ints themselves.
     */
    suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities>

    /**
     * Streams generated tokens. Cold — nothing runs until collection begins, and
     * cancelling the collector stops generation.
     */
    fun generate(request: AiRequest): Flow<String>

    /**
     * Formats a conversation into a single prompt using the loaded model's own chat
     * template.
     *
     * Templates are model-specific — Qwen uses ChatML, Gemma uses `<start_of_turn>`,
     * Llama 3 uses its own headers. Applying the wrong one does not error; it silently
     * degrades output. So the domain describes **messages** and the engine, which knows
     * what it loaded, decides the **format**.
     */
    fun formatPrompt(messages: List<AiChatMessage>): String

    /**
     * Opens or reuses a standing chat session for [conversationId] — the engine's KV cache
     * *is* the conversation from this point on (documentation/02-architecture.md §5.3).
     *
     * A no-op when this conversation's session is already open and valid: the implementation
     * tracks which conversation (and which model generation — a reload/crash invalidates the
     * KV cache) it last primed, and only re-sends [systemPrompt]/[history] when that no
     * longer holds. Callers are expected to call this on every turn, exactly as they already
     * call [load] on every turn — cheap when nothing changed, correct when it did.
     *
     * @param history prior turns, oldest first — replayed once (decoded in a single shot)
     *   when (re)priming is needed. Assistant turns must already be thinking-stripped; see
     *   [SendChatMessageUseCase][com.postsaimanager.core.domain.usecase.SendChatMessageUseCase]'s
     *   KDoc on why a reasoning trace never re-enters a future prompt.
     * @return true if the session was just (re)primed, false if an already-open session for
     *   this conversation was reused as-is. Informational only — callers do not need to
     *   branch on it.
     */
    suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean

    /**
     * True when [ensureChatSession] for [conversationId] would be a no-op right now — the
     * standing chat session is already open for this exact conversation and nothing since has
     * invalidated it (see [ensureChatSession]'s KDoc on what does: a reload, an unload, or a
     * one-shot [generate] call). A pure read of local bookkeeping — no native call, no IO —
     * so callers can cheaply decide *before* doing any work whether a re-prime is about to
     * happen:
     * [SendChatMessageUseCase][com.postsaimanager.core.domain.usecase.SendChatMessageUseCase]
     * uses it both to show "Preparing conversation…" only when priming will actually run, and
     * to skip rebuilding the grounding system prompt when the session already holds it.
     */
    suspend fun isChatSessionPrimed(conversationId: String): Boolean

    /**
     * Streams a reply to [userText] within the session opened by [ensureChatSession]. Only
     * the template text newly added since the previous turn is decoded — see
     * [ensureChatSession] and `LlamaNative.sendChatMessage`.
     *
     * The caller must call [commitChatReply] once the reply is known (even on failure/
     * cancellation, with whatever was produced) so the *next* turn's diff is computed
     * correctly — this method does not append the reply to the session itself, since the
     * caller may still need to strip a reasoning trace from it first.
     */
    fun sendChatMessage(userText: String, request: AiRequest): Flow<String>

    /**
     * True when the most recent [generate]/[sendChatMessage] stopped because it reached its
     * token cap rather than an end-of-generation token — the reply is cut off mid-thought.
     * Read once, right after the stream completes. Defaults to false for engines that cannot
     * tell.
     */
    suspend fun lastReplyHitLimit(): Boolean = false

    /** Appends [answer] (thinking-stripped) to the open chat session's history. See [sendChatMessage]. */
    suspend fun commitChatReply(answer: String)

    /**
     * Rolls back an interrupted (stopped, crashed, or otherwise cancelled) reply: removes
     * the reply's sampled tokens from the KV cache — everything decoded since the user's
     * turn was rendered in [sendChatMessage], via `llama_memory_seq_rm` — **without**
     * touching `chatHistory`. The user's turn stays; no assistant turn is appended.
     *
     * This is the counterpart to [commitChatReply] for a turn that is never committed: call
     * exactly one of the two once a turn's outcome is known. Without this, the KV cache
     * would keep the half-formed reply as if the model had actually said it, and the next
     * turn's diff would be decoded against a cache state `chatHistory` no longer describes
     * — the model would effectively see its own abandoned words as prior context.
     *
     * A no-op when no chat session is open (nothing to roll back).
     */
    suspend fun discardPendingReply()

    /** Drops the standing chat session — its KV cache and history. E.g. on conversation switch. */
    suspend fun resetChatSession()

    suspend fun unload()

    /**
     * Which accelerators the native backend reports as available on this device — a probe
     * run in the `:inference` process, not a static assumption.
     *
     * Callers that cannot reach the probe (the service is not bound yet, or the native
     * library failed to load) should treat that as "CPU only" rather than propagate the
     * failure — every model ships CPU-capable, so that default is always safe.
     */
    suspend fun availableAccelerators(): Set<Accelerator>

    /**
     * Emits when the engine observes generation ending in a crash rather than a normal
     * completion or cancellation — on [RemoteAiEngine][com.postsaimanager.core.ai.local
     * .RemoteAiEngine], a binder death of the `:inference` process. Carries the
     * model+config that was active when it happened, so a listener (today,
     * `InferenceCrashObserver` in `:core:ai:local`) can tell a GPU crash from a CPU one and
     * react — e.g. blocking GPU for that model via [ActiveModelProvider]'s backing
     * [com.postsaimanager.core.domain.repository.InferenceSettingsRepository].
     *
     * A `SharedFlow` rather than a suspend callback: a crash can happen with no collector
     * present (nobody has opened chat since app start), and the point is exactly that a
     * collector attaching later does not need to have been listening at the moment it
     * happened — replay/buffering is `RemoteAiEngine`'s concern, not each listener's.
     */
    val crashEvents: SharedFlow<InferenceCrash>

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
    }
}

/** One crash observed by [AiEngine.crashEvents] — see its doc. */
data class InferenceCrash(
    /** The model that was loaded (or being loaded) when the crash happened, if known. */
    val modelId: String?,
    /** The config it was loaded/requested with — in particular, [InferenceConfig.accelerator]. */
    val config: InferenceConfig,
)

/**
 * One generation request.
 *
 * Sampling parameters default to [SamplingConfig]'s defaults — the same single source of
 * truth [InferenceConfig] centralises for loading — so a caller only overrides what it
 * actually needs to (`AiExtractionUseCase` overrides [temperature] for near-deterministic
 * output; nothing overrides [topK]/[topP]/[seed] yet).
 *
 * @param grammar GBNF source constraining the output. When present the sampler physically
 *   cannot emit violating text — verified on device, and the mechanism Phase 8's tool layer
 *   is built on. Null means unconstrained.
 */
data class AiRequest(
    val prompt: String,
    val maxTokens: Int = 512,
    val temperature: Float = SamplingConfig().temperature,
    val topK: Int = SamplingConfig().topK,
    val topP: Float = SamplingConfig().topP,
    /**
     * Presence penalty over the tokens already generated in this reply (0 = off). Only sees
     * generated tokens, not the prompt — see llama_jni.cpp's buildSamplerChain.
     */
    val presencePenalty: Float = SamplingConfig().presencePenalty,
    val seed: Long? = SamplingConfig().seed,
    val grammar: String? = null,
    /**
     * False disables Qwen3/3.5 reasoning for this turn (`/no_think`) — see
     * [com.postsaimanager.core.model.InferenceOverrides.thinkingEffort] and
     * documentation/02-architecture.md §5.3. Only meaningful for
     * [AiEngine.sendChatMessage]; the one-shot [AiEngine.generate] path ignores it.
     */
    val thinkingEnabled: Boolean = true,
    /**
     * The reasoning trace's own sub-budget, in tokens, inside [maxTokens] — how much of the
     * reply budget a turn may spend still inside `<think>` before generation forces the tag
     * closed (Qwen3 Technical Report's "thinking budget" technique) and moves on to the
     * answer. 0 (the default) disables the forced close: thinking, if any, may run
     * unconstrained up to [maxTokens] — only [SendChatMessageUseCase][com.postsaimanager
     * .core.domain.usecase.SendChatMessageUseCase] (via the user's Off/Low/High thinking
     * effort setting) ever sets this to something else. Ignored when [thinkingEnabled] is
     * false, and by the one-shot [AiEngine.generate] path, same as [thinkingEnabled] itself.
     */
    val thinkingBudgetTokens: Int = 0,
)

data class AiCapabilities(
    val supportsGrammar: Boolean,
    val contextTokens: Int,
    val modelName: String,
    /**
     * True when the model declares its own chat template in GGUF metadata. False means the
     * engine falls back to ChatML, which may be wrong for that model — worth surfacing.
     */
    val hasNativeChatTemplate: Boolean = false,
)

/** One turn in a conversation, independent of any model's prompt format. */
data class AiChatMessage(
    val role: AiChatRole,
    val content: String,
)

enum class AiChatRole {
    SYSTEM,
    USER,
    ASSISTANT;

    /** Wire name expected by llama.cpp's template engine. */
    val wireName: String get() = name.lowercase()
}

/**
 * Supplies the model the user has chosen, without exposing the catalog subsystem.
 *
 * Keeps `:core:domain` free of any dependency on `:core:ai:catalog` — the domain needs to
 * know *which file* to load, not how models are downloaded, verified or stored.
 */
interface ActiveModelProvider {
    /** Absolute path of the chat model file, or null if none is installed. */
    suspend fun activeModelPath(): String?

    /**
     * Everything the chat model should be loaded with — context window, threads and the
     * rest of [InferenceConfig] — sized to what the device can currently afford. See
     * [InferenceConfig.defaults].
     */
    suspend fun activeModelConfig(): InferenceConfig

    /**
     * The catalogue id of the chat model (`AiModelDescriptor.id`), which picks its agent settings (`ModelProfiles`, the
     * form-filling chat); null when unknown or side-loaded, and the default agent settings are used.
     */
    suspend fun activeModelId(): String? = null

    /**
     * The model that reads documents, which need not be the one that chats.
     *
     * They are different jobs. Chat is interactive, so a reply that starts quickly matters
     * more than a perfect one. Reading a document runs in the background after a scan,
     * where nobody is waiting and a missed deadline is a real cost — measured on a German
     * letter, Gemma 4 E2B found the sender, the deadline and both references where
     * Qwen3.5 2B found no sender and no deadline at all, and took 247 seconds against 164.
     *
     * Defaults to [activeModelPath] when the user has not chosen separately, so the common
     * case stays one model and one load.
     */
    suspend fun extractionModelPath(): String?

    /** As [activeModelConfig], for the extraction model. */
    suspend fun extractionModelConfig(): InferenceConfig

    /**
     * The catalogue id of the extraction model (`AiModelDescriptor.id`), which picks its reading strategy
     * (`ModelProfiles`); null when unknown or side-loaded, and the strategy that needs no measurement is used.
     */
    suspend fun extractionModelId(): String? = null

    /**
     * The user-editable settings for the active model on this device — see
     * [com.postsaimanager.core.model.inferenceConfigSchema].
     *
     * Defaults to empty so the existing test doubles (`FakeActiveModelProvider`,
     * `StubActiveModel` in the `:core:ai:local` device tests) need no change; only
     * `CatalogActiveModelProvider`, which is what the real Settings screen reads, overrides
     * it with device- and model-aware bounds.
     */
    suspend fun activeModelSchema(): List<ConfigSpec> = emptyList()
}
