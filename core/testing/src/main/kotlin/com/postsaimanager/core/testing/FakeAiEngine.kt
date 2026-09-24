package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.InferenceCrash
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * An [AiEngine] that returns whatever a test tells it to.
 *
 * Lets everything around generation — prompt construction, grammar selection, parsing,
 * failure handling — be tested without a multi-gigabyte model or a device. The tests that
 * need a real model are device tests and say so.
 */
class FakeAiEngine(
    override var isReady: Boolean = true,
) : AiEngine {

    private val _state = MutableStateFlow<ModelLoadState>(
        ModelLoadState.Ready(
            modelId = "fake",
            config = InferenceConfig(contextTokens = 4096, threads = 4),
            loadDurationMs = 0L,
        ),
    )
    override val state: StateFlow<ModelLoadState> = _state

    /**
     * Puts [state] into [ModelLoadState.Ready] for [modelId] with [config] — lets a test
     * simulate "the model is already loaded from a previous send" without going through
     * [load], e.g. to exercise a caller (like `CatalogActiveModelProvider`) that reads
     * [state] to decide whether to reuse part of a previously loaded config.
     */
    fun setReady(modelId: String, config: InferenceConfig, loadDurationMs: Long = 0L) {
        _state.value = ModelLoadState.Ready(modelId, config, loadDurationMs)
    }

    /** What [generate] emits, in chunks, to exercise streaming. */
    var response: String = ""

    /** When set, [generate] throws — for the "inference died" path. */
    var failWith: Exception? = null

    /** Captured so tests can assert what was asked, and how. */
    var lastRequest: AiRequest? = null
        private set

    var lastMessages: List<AiChatMessage> = emptyList()
        private set

    /** Every `(modelPath, config)` passed to [load], in order — the whole point being to
     * assert *how many times* and *with what* it was called, not just its return value. */
    val loadCalls = mutableListOf<Pair<String, InferenceConfig>>()

    /** When set, [load] returns this error instead of succeeding. */
    var loadFailsWith: PamResult.Error? = null

    override suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities> {
        loadCalls += modelPath to config
        loadFailsWith?.let { return it }
        isReady = true
        return PamResult.Success(
            AiCapabilities(true, config.contextTokens, "fake", hasNativeChatTemplate = true),
        )
    }

    override fun generate(request: AiRequest): Flow<String> = flow {
        lastRequest = request
        failWith?.let { throw it }
        // Emitted in pieces: a caller that assumes one emission per generation would pass a
        // single-chunk fake and fail against the real streaming engine.
        response.chunked(7).forEach { emit(it) }
    }

    /** Every `(conversationId, systemPrompt, history)` passed to [ensureChatSession], in order. */
    val ensureChatSessionCalls = mutableListOf<Triple<String, String, List<AiChatMessage>>>()

    /** The `history` from the most recent [ensureChatSession] call — what a test usually wants. */
    val lastSessionHistory: List<AiChatMessage>
        get() = ensureChatSessionCalls.lastOrNull()?.third.orEmpty()

    /** Every reply passed to [commitChatReply], in order. */
    val committedReplies = mutableListOf<String>()

    var chatSessionWasReset: Boolean = false
        private set

    var lastChatUserText: String? = null
        private set

    override suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean {
        ensureChatSessionCalls += Triple(conversationId, systemPrompt, history)
        return true
    }

    /**
     * When set, [sendChatMessage] emits [response] in full and *then* throws — unlike
     * [failWith], which fails before anything streams. Exists to exercise the "partial
     * reply persisted as incomplete" path (a crash mid-stream), which needs some text to
     * already be in flight before the failure.
     */
    var failAfterResponse: Exception? = null

    override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> = flow {
        lastChatUserText = userText
        lastRequest = request
        failWith?.let { throw it }
        response.chunked(7).forEach { emit(it) }
        failAfterResponse?.let { throw it }
    }

    override suspend fun commitChatReply(answer: String) {
        committedReplies += answer
    }

    /** Turns [discardPendingReply] was called for, in order — see the doc on [AiEngine]. */
    val discardedReplies: MutableList<Unit> = mutableListOf()

    override suspend fun discardPendingReply() {
        discardedReplies += Unit
    }

    override suspend fun resetChatSession() {
        chatSessionWasReset = true
    }

    override fun formatPrompt(messages: List<AiChatMessage>): String {
        lastMessages = messages
        return messages.joinToString("\n") { "${it.role.wireName}: ${it.content}" }
    }

    override suspend fun unload() {
        isReady = false
    }

    /** Overridable so a test can exercise GPU-aware resolution without a device. */
    var accelerators: Set<Accelerator> = setOf(Accelerator.CPU)

    override suspend fun availableAccelerators(): Set<Accelerator> = accelerators

    private val _crashEvents = MutableSharedFlow<InferenceCrash>(extraBufferCapacity = 4)
    override val crashEvents: SharedFlow<InferenceCrash> = _crashEvents

    /** Lets a test simulate [AiEngine.crashEvents] without a real process death. */
    suspend fun emitCrash(crash: InferenceCrash) = _crashEvents.emit(crash)
}

/**
 * An [com.postsaimanager.core.domain.ai.ActiveModelProvider] that reports a model on disk.
 *
 * `path = null` is the device with no model installed — the case that has to degrade rather
 * than fail, since it is every device before the first download.
 */
class FakeActiveModelProvider(
    var path: String? = "/data/local/tmp/fake-model.gguf",
    var contextTokens: Int = 4096,
    /** Null means "same as the chat model", which is the default the app ships. */
    var extractionPath: String? = null,
    /** Lets a test change the accelerator between sends — see `SendChatMessageUseCaseTest`. */
    var accelerator: Accelerator = Accelerator.CPU,
) : com.postsaimanager.core.domain.ai.ActiveModelProvider {
    /** Generous enough that [InferenceConfig.defaults]'s heuristic never clamps [contextTokens]. */
    private val device = DeviceCapability(
        totalRamBytes = 8L * 1024 * 1024 * 1024,
        availableRamBytes = 8L * 1024 * 1024 * 1024,
        freeStorageBytes = 8L * 1024 * 1024 * 1024,
        supportedAbis = listOf("arm64-v8a"),
    )

    override suspend fun activeModelPath(): String? = path
    override suspend fun activeModelConfig(): InferenceConfig =
        InferenceConfig.defaults(device, contextTokens).copy(
            accelerator = accelerator,
            gpuLayers = if (accelerator == Accelerator.GPU) -1 else 0,
        )

    override suspend fun extractionModelPath(): String? = extractionPath ?: path
    override suspend fun extractionModelConfig(): InferenceConfig =
        InferenceConfig.defaults(device, contextTokens).copy(
            accelerator = accelerator,
            gpuLayers = if (accelerator == Accelerator.GPU) -1 else 0,
        )
}
