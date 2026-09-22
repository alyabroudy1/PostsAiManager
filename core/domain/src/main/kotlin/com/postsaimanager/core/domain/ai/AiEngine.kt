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
    val seed: Long? = SamplingConfig().seed,
    val grammar: String? = null,
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
