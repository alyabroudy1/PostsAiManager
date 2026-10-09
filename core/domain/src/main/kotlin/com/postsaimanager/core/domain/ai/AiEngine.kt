package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.SamplingConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

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
interface AiEngine : ChatEngine {

    // The chat half of this engine (state, isBusy, load, the chat session, unload) is the [ChatEngine] port it extends.
    // Load is single-flight and generation-guarded on the implementation side: concurrent `load` callers observe the same
    // in-flight transition rather than racing separate native calls.

    val isReady: Boolean

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

    // On llama.cpp the chat session is the KV cache: [ensureChatSession] decodes the grounding and the history once and
    // [sendChatMessage] decodes only the turn's new text; [discardPendingReply] removes a stopped reply's tokens from the
    // cache (`llama_memory_seq_rm`) without touching the history; any one-shot [generate] clears the cache, so the next
    // [ensureChatSession] re-primes (documentation/02-architecture.md §5.3).

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
    /**
     * Non-null when this chat reply may call the Agent Skills tools (`load_skill`, `run_intent`): only a tool-capable engine
     * (LiteRT-LM) acts on it, the llama.cpp engines ignore it. See [ChatToolsRequest] and `ChatToolsPolicy`.
     */
    val tools: ChatToolsRequest? = null,
    /**
     * Files of the pictures the user attached to this turn's message (copied into the app's own storage by `ChatImageStore`).
     * Only an image-capable engine (LiteRT-LM, [com.postsaimanager.core.model.InferenceConfig.supportsImages]) looks at them; the
     * llama.cpp engines ignore it. Never replayed into a rebuilt conversation: the model sees the picture for the reply it was
     * attached to.
     */
    val imagePaths: List<String> = emptyList(),
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
    /**
     * False when the engine probed the model right after loading it and its chat template does not render (a one-message history
     * came back empty): the model cannot chat, whatever the catalogue says. True for an engine that does not probe (it chats) and
     * for a model that rendered. A model that cannot chat may still read letters.
     */
    val canChat: Boolean = true,
    /** The accelerator the engine says it started on (a requested GPU may have fallen back to the CPU); null when it does not say. */
    val runningAccelerator: Accelerator? = null,
)

/** One turn in a conversation, independent of any model's prompt format. */
data class AiChatMessage(
    val role: AiChatRole,
    val content: String,
    /**
     * For an assistant turn: the tool calls it made before [content] and what they returned. A tool-capable engine replays them as
     * tool-call turns so the history never shows an action claimed without its call; the others ignore it.
     */
    val toolTrace: List<com.postsaimanager.core.model.ToolExchange> = emptyList(),
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
     * The model the form-filling agent runs on: the first installed model of [com.postsaimanager.core.domain.extraction.zones.ModelProfiles.FORM_AGENT_MODELS]
     * (a tool-calling agent needs more than the smallest chat model), otherwise the chat model. The engine loads it for the run; the next
     * chat message loads the chat model again.
     */
    suspend fun formModelPath(): String? = activeModelPath()

    /** As [activeModelConfig], for the form model. */
    suspend fun formModelConfig(): InferenceConfig = activeModelConfig()

    /** The catalogue id of the form model (picks its agent settings, see [activeModelId]). */
    suspend fun formModelId(): String? = activeModelId()

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

    /**
     * Whether any model is there to read documents: the llama.cpp reader ([extractionModelPath]), or the chat model alone. A
     * LiteRT-LM chat model (Gemma) is never the llama.cpp reader, so [extractionModelPath] is null when Gemma is the only model
     * installed; the Gemma reader runs on the chat model, so letters are still read, and callers that ask "is there anything to read
     * with" must use this rather than [extractionModelPath].
     */
    suspend fun canReadDocuments(): Boolean = extractionModelPath() != null || activeModelPath() != null

    /** As [activeModelConfig], for the extraction model. */
    suspend fun extractionModelConfig(): InferenceConfig

    /**
     * The config a document READING loads the chat model with: [activeModelConfig] with the accelerator of the "Reading" setting
     * ([ReadingAcceleratorSetting], CPU by default, so by default it is the chat's own config and nothing reloads). Chat keeps
     * [activeModelConfig]; [loadForUse] reloads the engine when the two differ.
     */
    suspend fun readingModelConfig(): InferenceConfig = activeModelConfig()

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
