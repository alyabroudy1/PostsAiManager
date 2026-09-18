package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.common.result.PamResult
import kotlinx.coroutines.flow.Flow
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

    val state: StateFlow<AiEngineState>

    val isReady: Boolean

    /**
     * Loads a model, replacing any currently loaded one.
     *
     * @param contextTokens size of the context window; larger costs proportionally more
     *   memory, which on most devices is the binding constraint.
     */
    suspend fun load(modelPath: String, contextTokens: Int = DEFAULT_CONTEXT_TOKENS):
        PamResult<AiCapabilities>

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

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
    }
}

/**
 * One generation request.
 *
 * @param grammar GBNF source constraining the output. When present the sampler physically
 *   cannot emit violating text — verified on device, and the mechanism Phase 8's tool layer
 *   is built on. Null means unconstrained.
 */
data class AiRequest(
    val prompt: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
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

sealed interface AiEngineState {
    /** No model installed or selected. The document app remains fully usable. */
    data object NoModel : AiEngineState

    data object Loading : AiEngineState

    data class Ready(val capabilities: AiCapabilities) : AiEngineState

    data class Failed(val message: String) : AiEngineState
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

    /** Context window the chat model should be loaded with. */
    suspend fun activeModelContextTokens(): Int

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

    suspend fun extractionModelContextTokens(): Int
}
