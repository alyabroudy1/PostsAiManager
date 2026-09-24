package com.postsaimanager.core.ai.local

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.InferenceCrash
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * On-device inference over llama.cpp, run **in-process**.
 *
 * This is not the engine the app binds — [RemoteAiEngine] is, running the same JNI layer in
 * the isolated `:inference` process (see its KDoc for why: a native abort here is a process
 * kill that Kotlin cannot catch). This class exists for the instrumented tests that need to
 * drive the JNI surface directly without the AIDL boundary in the way — spike tests,
 * template/grammar regression tests, the chat pipeline test. Not Hilt-bound to [AiEngine] in
 * production; `internal` where nothing outside this module needs it.
 *
 * ### Design
 *
 * **One model loaded at a time.** A second would double resident memory, and
 * `ModelFit` already shows the device is often near its limit.
 *
 * **All native access is serialised** through [mutex]. The JNI layer is not thread-safe per
 * handle, and two concurrent generations sharing one context would corrupt each other's KV
 * cache — a failure that surfaces as nonsense output rather than an error. Load, context
 * recreation and unload — driven through [ModelLoadCoordinator] — also run under [mutex], so
 * they can never interleave with an in-flight generation touching the same handle.
 *
 * **Cancellation is cooperative and immediate.** The token loop checks
 * `ensureActive()` each iteration, so collecting the [Flow] in a cancellable scope stops
 * generation within one token — no flag, no race.
 */
internal class LocalAiEngine @Inject constructor(
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : AiEngine {

    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    /** See `RemoteAiEngine.sessionConversationId` — same tracking, in-process. */
    @Volatile
    private var sessionConversationId: String? = null

    private val ops = object : ModelLoadOps {
        override suspend fun loadModel(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> =
            mutex.withLock {
                withContext(ioDispatcher) {
                    if (!LlamaNative.ensureLoaded()) {
                        return@withContext PamResult.Error(
                            PamError.ModelNotLoaded("On-device AI is not supported on this device's processor."),
                        )
                    }
                    val file = File(modelId)
                    if (!file.exists()) {
                        return@withContext PamResult.Error(PamError.FileNotFound(modelId))
                    }

                    sessionConversationId = null
                    freeHandleLocked()
                    val newHandle = LlamaNative.loadModel(
                        modelPath = file.absolutePath,
                        contextTokens = config.contextTokens,
                        batchTokens = config.batchTokens,
                        threads = config.threads,
                        threadsBatch = config.threadsBatch,
                        useMmap = config.useMmap,
                        useMlock = config.useMlock,
                        flashAttention = config.flashAttention,
                        gpuLayers = config.gpuLayers,
                        accelerator = config.accelerator.ordinal,
                    )
                    if (newHandle == 0L) {
                        // llama.cpp reports load failure by returning null rather than
                        // throwing; the cause is only in logcat.
                        return@withContext PamResult.Error(
                            PamError.ModelNotLoaded(
                                "Could not load ${file.name}. The file may be corrupt or use " +
                                    "an unsupported model architecture.",
                            ),
                        )
                    }
                    handle = newHandle
                    PamResult.Success(capabilitiesOf(file, config, newHandle))
                }
            }

        override suspend fun recreateContext(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> =
            mutex.withLock {
                withContext(ioDispatcher) {
                    val current = handle
                    if (current == 0L) {
                        return@withContext PamResult.Error(
                            PamError.ModelNotLoaded("No model resident to apply the new settings to."),
                        )
                    }
                    sessionConversationId = null
                    val ok = LlamaNative.recreateContext(
                        handle = current,
                        contextTokens = config.contextTokens,
                        batchTokens = config.batchTokens,
                        threads = config.threads,
                        threadsBatch = config.threadsBatch,
                        flashAttention = config.flashAttention,
                    )
                    if (!ok) {
                        return@withContext PamResult.Error(PamError.ModelNotLoaded("Could not apply the new settings."))
                    }
                    PamResult.Success(capabilitiesOf(File(modelId), config, current))
                }
            }

        override suspend fun unloadModel() = mutex.withLock {
            withContext(ioDispatcher) {
                sessionConversationId = null
                freeHandleLocked()
            }
        }

        override suspend fun isActuallyLoaded(): Boolean = handle != 0L
    }

    private val coordinator = ModelLoadCoordinator(ops)

    override val state: StateFlow<ModelLoadState> = coordinator.state

    override val isReady: Boolean get() = handle != 0L

    // In-process: a native abort here kills the whole app process (see the class doc), so
    // there is no callback to observe — nothing ever survives to emit on this. It exists
    // only to satisfy AiEngine for the instrumented tests that use this engine directly.
    override val crashEvents: SharedFlow<InferenceCrash> = MutableSharedFlow()

    /** Loads a model, replacing any currently loaded one. See [InferenceConfig]. */
    override suspend fun load(
        modelPath: String,
        config: InferenceConfig,
    ): PamResult<AiCapabilities> = loadFile(File(modelPath), config)

    suspend fun loadFile(
        modelFile: File,
        config: InferenceConfig = InferenceConfig(
            contextTokens = DEFAULT_CONTEXT_TOKENS,
            threads = InferenceConfig.defaultThreadCount(),
        ),
    ): PamResult<AiCapabilities> = coordinator.load(modelFile.absolutePath, config)

    private fun capabilitiesOf(file: File, config: InferenceConfig, handle: Long): AiCapabilities {
        val hasTemplate = LlamaNative.hasChatTemplate(handle)
        if (!hasTemplate) {
            // Not fatal, but worth knowing: the ChatML fallback may be wrong for this
            // model, and wrong templates degrade output silently rather than erroring.
            android.util.Log.w(TAG, "${file.name} declares no chat template; falling back to ChatML")
        }
        return AiCapabilities(
            supportsGrammar = true, // verified on device — spike Q4
            contextTokens = config.contextTokens,
            modelName = file.nameWithoutExtension,
            hasNativeChatTemplate = hasTemplate,
        )
    }

    /**
     * Streams generated tokens.
     *
     * The flow is cold: nothing runs until collection starts, and cancelling the collector
     * stops generation within one token.
     *
     * @param grammar GBNF source. With one present the sampler physically cannot emit
     *   violating output — how Phase 8 gets reliable tool calls from small models.
     */
    override fun generate(request: AiRequest): Flow<String> = flow {
        val prompt = request.prompt
        val maxTokens = request.maxTokens
        val temperature = request.temperature
        val grammar = request.grammar

        val current = handle
        if (current == 0L) {
            throw IllegalStateException("No model is loaded")
        }

        // See RemoteAiEngine.generate's doc: a one-shot generation clears the KV cache on
        // the native side, taking any primed chat session with it.
        sessionConversationId = null

        mutex.withLock {
            val started = LlamaNative.startGeneration(
                handle = current,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                topK = request.topK,
                topP = request.topP,
                // null means "no seed requested"; the JNI layer treats negative as that.
                seed = request.seed ?: -1L,
                grammar = grammar,
            )
            if (!started) {
                throw IllegalArgumentException("Prompt produced no tokens")
            }

            try {
                while (true) {
                    // Cancellation is checked before each token, so collecting in a
                    // cancellable scope stops generation promptly.
                    currentCoroutineContext().ensureActive()
                    val token = LlamaNative.nextToken(current) ?: break
                    emit(token)
                }
            } finally {
                // Runs on normal completion *and* cancellation, so native generation
                // state is never left dangling.
                LlamaNative.stopGeneration(current)
            }
        }
    }.flowOn(ioDispatcher)

    /** Convenience wrapper: collects [generate] into one string. */
    suspend fun generateOnce(
        prompt: String,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Float = DEFAULT_TEMPERATURE,
        grammar: String? = null,
    ): PamResult<String> = try {
        val builder = StringBuilder()
        generate(AiRequest(prompt = prompt, maxTokens = maxTokens, temperature = temperature, grammar = grammar))
            .collect { builder.append(it) }
        PamResult.Success(builder.toString())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        PamResult.Error(PamError.InferenceError(e.message ?: "Generation failed", e))
    }

    /**
     * Uses the model's own template where it declares one, falling back to ChatML.
     *
     * The fallback is a genuine compromise, not a default: a Gemma model without metadata
     * would be formatted as Qwen and produce quietly worse answers. [AiCapabilities
     * .hasNativeChatTemplate] reports which path was taken.
     */
    override fun formatPrompt(messages: List<AiChatMessage>): String {
        val current = handle
        if (current != 0L && messages.isNotEmpty()) {
            val native = LlamaNative.formatChat(
                handle = current,
                roles = messages.map { it.role.wireName }.toTypedArray(),
                contents = messages.map { it.content }.toTypedArray(),
                addAssistant = true,
            )
            if (native != null) return native
        }
        return ChatTemplateFallback.chatMl(messages)
    }

    override suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean {
        if (sessionConversationId == conversationId) return false
        val current = handle
        if (current == 0L) return false

        return mutex.withLock {
            withContext(ioDispatcher) {
                if (!LlamaNative.openChatSession(current, systemPrompt)) return@withContext false
                if (history.isNotEmpty()) {
                    val primed = LlamaNative.primeChatSession(
                        current,
                        history.map { it.role.wireName }.toTypedArray(),
                        history.map { it.content }.toTypedArray(),
                    )
                    if (!primed) return@withContext false
                }
                sessionConversationId = conversationId
                true
            }
        }
    }

    override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> = flow {
        val current = handle
        if (current == 0L) throw IllegalStateException("No model is loaded")

        mutex.withLock {
            val started = LlamaNative.sendChatMessage(
                handle = current,
                userText = userText,
                maxTokens = request.maxTokens,
                temperature = request.temperature,
                topK = request.topK,
                topP = request.topP,
                seed = request.seed ?: -1L,
                grammar = request.grammar,
                noThink = !request.thinkingEnabled,
            )
            if (!started) throw IllegalArgumentException("The chat turn could not be started")

            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val token = LlamaNative.nextToken(current) ?: break
                    emit(token)
                }
            } finally {
                LlamaNative.stopGeneration(current)
            }
        }
    }.flowOn(ioDispatcher)

    override suspend fun commitChatReply(answer: String) {
        val current = handle
        if (current == 0L) return
        mutex.withLock { withContext(ioDispatcher) { LlamaNative.commitChatReply(current, answer) } }
    }

    override suspend fun discardPendingReply() {
        val current = handle
        if (current == 0L) return
        mutex.withLock { withContext(ioDispatcher) { LlamaNative.discardPendingReply(current) } }
    }

    override suspend fun resetChatSession() {
        sessionConversationId = null
        val current = handle
        if (current == 0L) return
        mutex.withLock { withContext(ioDispatcher) { LlamaNative.resetChatSession(current) } }
    }

    override suspend fun unload() = coordinator.unload()

    /** Caller must hold [mutex]. */
    private fun freeHandleLocked() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
        }
    }

    fun systemInfo(): String =
        if (LlamaNative.ensureLoaded()) LlamaNative.systemInfo() else "native library unavailable"

    /** See [AiEngine.availableAccelerators]. Runs in-process — this variant has no probe to bind. */
    override suspend fun availableAccelerators(): Set<Accelerator> {
        if (!LlamaNative.ensureLoaded()) return setOf(Accelerator.CPU)
        val ordinals = withContext(ioDispatcher) { LlamaNative.availableAccelerators() }
        val accelerators = ordinals.toList().mapNotNull { Accelerator.entries.getOrNull(it) }.toSet()
        return accelerators.ifEmpty { setOf(Accelerator.CPU) }
    }

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.7f
        private const val TAG = "LocalAiEngine"
    }
}
