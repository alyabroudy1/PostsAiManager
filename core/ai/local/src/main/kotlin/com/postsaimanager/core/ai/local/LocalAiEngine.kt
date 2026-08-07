package com.postsaimanager.core.ai.local

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiEngineState
import com.postsaimanager.core.domain.ai.AiRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device inference over llama.cpp.
 *
 * ### Design
 *
 * **One model loaded at a time.** A second would double resident memory, and
 * `ModelFit` already shows the device is often near its limit.
 *
 * **All native access is serialised** through [mutex]. The JNI layer is not thread-safe per
 * handle, and two concurrent generations sharing one context would corrupt each other's KV
 * cache — a failure that surfaces as nonsense output rather than an error.
 *
 * **Cancellation is cooperative and immediate.** The token loop checks
 * `ensureActive()` each iteration, so collecting the [Flow] in a cancellable scope stops
 * generation within one token — no flag, no race.
 *
 * ### What this deliberately does not do
 *
 * Run in a separate process. Spike Q3 measured a native abort killing the entire app
 * (`SIGABRT`, `Zygote: Process exited`), so isolation via `android:process=":inference"`
 * is justified — but it is a distinct change with its own IPC surface, tracked as 7.3.12.
 * Until then a malformed model or bad grammar can still take the app down.
 */
@Singleton
class LocalAiEngine @Inject constructor(
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : AiEngine {

    private val mutex = Mutex()

    @Volatile
    private var handle: Long = 0L

    private val _state = MutableStateFlow<AiEngineState>(AiEngineState.NoModel)
    override val state: StateFlow<AiEngineState> = _state.asStateFlow()

    override val isReady: Boolean get() = handle != 0L

    /**
     * Loads a model, replacing any currently loaded one.
     *
     * @param threads defaults to half the available cores — using all of them starves the
     *   UI thread and makes the app feel frozen while generating.
     */
    override suspend fun load(
        modelPath: String,
        contextTokens: Int,
    ): PamResult<AiCapabilities> = loadFile(File(modelPath), contextTokens)

    suspend fun loadFile(
        modelFile: File,
        contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
        threads: Int = defaultThreadCount(),
    ): PamResult<AiCapabilities> = mutex.withLock {
        withContext(ioDispatcher) {
            if (!LlamaNative.ensureLoaded()) {
                val error = PamError.ModelNotLoaded(
                    "On-device AI is not supported on this device's processor.",
                )
                _state.value = AiEngineState.Failed(error.userMessage)
                return@withContext PamResult.Error(error)
            }

            if (!modelFile.exists()) {
                val error = PamError.FileNotFound(modelFile.absolutePath)
                _state.value = AiEngineState.Failed(error.userMessage)
                return@withContext PamResult.Error(error)
            }

            _state.value = AiEngineState.Loading
            unloadLocked()

            val newHandle = LlamaNative.loadModel(
                modelPath = modelFile.absolutePath,
                contextTokens = contextTokens,
                threads = threads,
            )

            if (newHandle == 0L) {
                // llama.cpp reports load failure by returning null rather than throwing;
                // the cause (corrupt file, unsupported architecture, OOM) is only in logcat.
                val error = PamError.ModelNotLoaded(
                    "Could not load ${modelFile.name}. The file may be corrupt or use an " +
                        "unsupported model architecture.",
                )
                _state.value = AiEngineState.Failed(error.userMessage)
                return@withContext PamResult.Error(error)
            }

            handle = newHandle
            val hasTemplate = LlamaNative.hasChatTemplate(newHandle)
            if (!hasTemplate) {
                // Not fatal, but worth knowing: the ChatML fallback may be wrong for this
                // model, and wrong templates degrade output silently rather than erroring.
                android.util.Log.w(
                    "LocalAiEngine",
                    "${modelFile.name} declares no chat template; falling back to ChatML",
                )
            }
            val capabilities = AiCapabilities(
                supportsGrammar = true, // verified on device — spike Q4
                contextTokens = contextTokens,
                modelName = modelFile.nameWithoutExtension,
                hasNativeChatTemplate = hasTemplate,
            )
            _state.value = AiEngineState.Ready(capabilities)
            PamResult.Success(capabilities)
        }
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

        mutex.withLock {
            val started = LlamaNative.startGeneration(
                handle = current,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
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
        generate(AiRequest(prompt, maxTokens, temperature, grammar))
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
        return chatMlFallback(messages)
    }

    private fun chatMlFallback(messages: List<AiChatMessage>): String = buildString {
        messages.forEach { message ->
            appendLine("<|im_start|>${message.role.wireName}")
            appendLine("${message.content}<|im_end|>")
        }
        appendLine("<|im_start|>assistant")
    }

    override suspend fun unload() = mutex.withLock {
        withContext(ioDispatcher) {
            unloadLocked()
            _state.value = AiEngineState.NoModel
        }
    }

    /** Caller must hold [mutex]. */
    private fun unloadLocked() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
        }
    }

    fun systemInfo(): String =
        if (LlamaNative.ensureLoaded()) LlamaNative.systemInfo() else "native library unavailable"

    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 4096
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.7f

        /**
         * Half the cores, at least two.
         *
         * Using every core measurably starves the UI thread — generation is CPU-bound and
         * will happily consume everything it is given.
         */
        fun defaultThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)
    }
}
