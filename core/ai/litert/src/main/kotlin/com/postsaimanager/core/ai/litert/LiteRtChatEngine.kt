package com.postsaimanager.core.ai.litert

import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.skills.SkillCatalog
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The LiteRT-LM chat engine, in-process: the Gallery's [LlmChatModelHelper] behind the app's [ChatEngine] port.
 *
 * It runs inside the `:inference` process, driven through AIDL by `RemoteLiteRtChatEngine` exactly as llama.cpp is, so a GPU
 * driver crash costs the answer and not the app. It only chats: reading letters (token scoring, KV prefix reuse) stays on
 * llama.cpp.
 *
 * ### How the port maps onto a LiteRT-LM conversation
 *
 * LiteRT-LM keeps the conversation (its history and KV state) inside a `Conversation` object, whose sampler is fixed when it is
 * made. So this engine keeps the *committed* history itself ([committed]) and builds the `Conversation` lazily, at the next send,
 * from three things: the system instruction ([ensureChatSession]'s grounding), the committed turns, and the sampling of that
 * send's request. A conversation is rebuilt from the same three when the sampling changes, after a conversation switch, and after
 * a discarded reply (a stopped reply may have left a half-formed turn inside the `Conversation`, and the app's contract is that
 * an abandoned reply never becomes something the model said).
 *
 * The text is handed over as plain messages: the model file carries its chat template and LiteRT-LM applies it, so nothing here
 * adds turn markers.
 *
 * ### Tools (Agent Skills)
 *
 * A reply whose [AiRequest.tools] is set runs in a conversation that has the `load_skill` and `run_intent` tools and the skills
 * in its system instruction ([LiteRtToolKit]). LiteRT-LM calls the tools itself and feeds their results back (automatic tool
 * calling), so one send is one stream of the model's final text. `run_intent` only proposes: its action goes to the callback
 * of [sendChatMessage], never to the system. [ReplyTextFilter] keeps tool protocol out of the stream.
 *
 * ### Not yet
 *
 * Thinking is off ([supportsThinking] is false, `enable_thinking=false`), and the thought channel is ignored.
 *
 * ### Reply cap
 *
 * LiteRT-LM has no per-reply token cap in the conversation API, and the app's contract needs to know a reply was cut off by it,
 * so the cap is counted here, per streamed chunk (a chunk is one decoding step, at least one token), and generation is stopped
 * when it is reached; [lastReplyHitLimit] then says so.
 */
class LiteRtChatEngine internal constructor(
    private val helper: LlmModelHelper,
    /** The Agent Skills tools; null for an engine that only chats. */
    private val toolKit: LiteRtToolKit?,
) : ChatEngine {

    /** The engine with the Agent Skills tools over [skills], the one owner of the skills. */
    constructor(skills: SkillCatalog) : this(LlmChatModelHelper, LiteRtToolKit(skills))

    /** Serialises every call that touches the model, the whole token stream included (the [ChatEngine] contract's point 5). */
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ModelLoadState>(ModelLoadState.Idle)
    override val state: StateFlow<ModelLoadState> = _state.asStateFlow()

    override val isBusy: Boolean get() = mutex.isLocked

    override val supportsThinking: Boolean get() = false

    private var instance: LlmModelInstance? = null
    private var loaded: Pair<String, InferenceConfig>? = null

    /** Which conversation's session is open, or null. */
    private var sessionId: String? = null
    private var system: String = ""

    /** The turns the model has really said and heard, oldest first: what a rebuilt conversation continues from. */
    private val committed = mutableListOf<LiteRtTurn>()

    /** The user message of the reply in flight; becomes part of [committed] with its reply, and is dropped with a discarded one. */
    private var pendingUser: String? = null

    /** The sampling the live conversation was made with; null when there is none to reuse (the next send builds one). */
    private var conversationSampling: ConversationKey? = null

    @Volatile
    private var hitLimit = false

    /** The accelerator the engine started on, for the log and the diagnostics. Null with no model loaded. */
    val accelerator: Accelerator? get() = instance?.accelerator

    override suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities> = mutex.withLock {
        val current = loaded
        if (instance != null && current != null && current.first == modelPath && sameEngine(current.second, config)) {
            // Sampling is per reply, so nothing native changes.
            loaded = modelPath to config
            _state.value = (_state.value as? ModelLoadState.Ready)?.copy(config = config)
                ?: ModelLoadState.Ready(modelPath, config, 0L)
            return@withLock PamResult.Success(capabilities(modelPath, config))
        }

        release()
        val startedAt = System.nanoTime()
        _state.value = ModelLoadState.Loading(modelPath, startedAt)
        try {
            val modelConfig = LlmModelConfig(
                modelPath = modelPath,
                accelerator = config.accelerator,
                maxTokens = config.contextTokens,
                topK = config.sampling.topK,
                topP = config.sampling.topP,
                temperature = config.sampling.temperature,
            )
            // Engine initialisation reads gigabytes and compiles GPU kernels: never on the caller's thread.
            instance = withContext(Dispatchers.IO) { helper.initialize(modelConfig) }
            conversationSampling = ConversationKey(
                Sampling(config.sampling.topK, config.sampling.topP, config.sampling.temperature),
                withTools = false,
            )
            loaded = modelPath to config
            _state.value = ModelLoadState.Ready(modelPath, config, (System.nanoTime() - startedAt) / 1_000_000)
            PamResult.Success(capabilities(modelPath, config))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: a phone whose native library cannot load throws an Error, and that is a failed load too.
            val message = "Could not load ${File(modelPath).name} with the LiteRT-LM engine: ${e.message}"
            _state.value = ModelLoadState.Failed(modelPath, message)
            PamResult.Error(PamError.ModelNotLoaded(message))
        }
    }

    /** A different accelerator or window needs a fresh engine; everything else (the sampling) does not. */
    private fun sameEngine(a: InferenceConfig, b: InferenceConfig): Boolean =
        a.accelerator == b.accelerator && a.contextTokens == b.contextTokens

    private fun capabilities(modelPath: String, config: InferenceConfig) = AiCapabilities(
        supportsGrammar = false,
        contextTokens = config.contextTokens,
        modelName = File(modelPath).nameWithoutExtension,
        hasNativeChatTemplate = true,
    )

    override suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean = mutex.withLock {
        if (sessionId == conversationId && instance != null) return@withLock false
        sessionId = conversationId
        system = systemPrompt
        committed.clear()
        committed += LiteRtTurns.from(history)
        pendingUser = null
        // Built at the next send, with that reply's sampling, so it is made once.
        conversationSampling = null
        true
    }

    override suspend fun isChatSessionPrimed(conversationId: String): Boolean =
        sessionId == conversationId && instance != null

    override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> =
        sendChatMessage(userText, request, onToolAction = {})

    /**
     * [sendChatMessage] with the action channel: every `run_intent` call the model makes during the reply (only when
     * [AiRequest.tools] is set and the skills are available) is handed to [onToolAction], before the stream ends. The service
     * forwards it over AIDL; this engine never executes it.
     */
    fun sendChatMessage(
        userText: String,
        request: AiRequest,
        onToolAction: (ToolActionCall) -> Unit,
        onFallback: () -> Unit = {},
    ): Flow<String> = serialised(replyFlow(userText, request, onToolAction, onFallback))

    private fun replyFlow(
        userText: String,
        request: AiRequest,
        onToolAction: (ToolActionCall) -> Unit,
        onFallback: () -> Unit,
    ): Flow<String> = callbackFlow {
        val live = instance
        if (live == null) {
            close(IllegalStateException("No model is loaded."))
            return@callbackFlow
        }
        hitLimit = false
        val kit = toolKit?.takeIf { request.tools != null }
        val toolPrompt = try {
            kit?.systemPrompt()
        } catch (e: Exception) {
            null
        }
        // Without a skill to name, the model gets no tools.
        val activeKit = kit?.takeIf { toolPrompt != null }
        try {
            withContext(Dispatchers.IO) {
                ensureConversation(live, Sampling(request.topK, request.topP, request.temperature), activeKit, toolPrompt)
            }
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }
        pendingUser = userText
        activeKit?.bind(request.tools?.documentId, onToolAction)

        val filter = ReplyTextFilter()
        var chunks = 0
        var running = live
        var fellBack = false
        val finished = AtomicBoolean(false)
        val lastActivityNanos = AtomicLong(System.nanoTime())

        fun runOn(inst: LlmModelInstance) {
            running = inst
            lastActivityNanos.set(System.nanoTime())
            helper.runInference(
                instance = inst,
                input = userText,
                resultListener = { text, done, _ ->
                    if (done) {
                        finished.set(true)
                        // Whatever the filter held back as a maybe-marker was ordinary text.
                        filter.finish().takeIf { it.isNotEmpty() }?.let { trySend(it) }
                        close()
                    } else if (!finished.get()) {
                        lastActivityNanos.set(System.nanoTime())
                        chunks++
                        // Control tokens and tool-call text are the model's protocol, not part of the answer.
                        filter.accept(text).takeIf { it.isNotEmpty() }?.let { trySend(it) }
                        if (chunks >= request.maxTokens) {
                            hitLimit = true
                            finished.set(true)
                            helper.stopResponse(inst)
                            close()
                        }
                    }
                },
                onError = { message ->
                    // The GPU engine can start and still fail its first reply (a missing OpenCL driver, a kernel that does not
                    // compile): once, before anything was said, reload on the CPU and answer from there.
                    if (!fellBack && chunks == 0 && inst.accelerator == Accelerator.GPU && GpuFailure.matches(message)) {
                        fellBack = true
                        launch(Dispatchers.IO) {
                            try {
                                val cpu = reloadOnCpu(inst)
                                onFallback()
                                ensureConversation(cpu, Sampling(request.topK, request.topP, request.temperature), activeKit, toolPrompt)
                                runOn(cpu)
                            } catch (e: Throwable) {
                                finished.set(true)
                                close(IllegalStateException(message))
                            }
                        }
                    } else {
                        finished.set(true)
                        close(IllegalStateException(message))
                    }
                },
                // Phase 1: no reasoning trace. Phase 3 brings the Gallery's thinking UI.
                extraContext = mapOf("enable_thinking" to "false"),
            )
        }
        runOn(live)
        // A reply that stops producing anything (a hung GPU driver) would hold this engine, and the service's one inference thread,
        // forever, and every later load would spin behind it. Idle for too long: stop it and say so.
        launch {
            while (!finished.get()) {
                delay(WATCHDOG_STEP_MS)
                if (!finished.get() && System.nanoTime() - lastActivityNanos.get() > REPLY_IDLE_TIMEOUT_MS * 1_000_000L) {
                    finished.set(true)
                    helper.stopResponse(running)
                    close(IllegalStateException("The AI engine stopped responding. Please try again."))
                }
            }
        }
        awaitClose {
            // A collector that stops early (the user's Stop) stops the native generation too.
            if (!finished.get()) helper.stopResponse(running)
            activeKit?.release()
        }
    }

    /**
     * Replaces a GPU engine that failed with a CPU one, in place: the instance, the recorded config (so the next [load] of the
     * CPU config is a no-op) and the state now say CPU. The conversation is rebuilt by the next [ensureConversation].
     */
    private fun reloadOnCpu(failed: LlmModelInstance): LlmModelInstance {
        val (path, config) = loaded ?: throw IllegalStateException("No model is loaded.")
        helper.cleanUp(failed)
        val cpu = helper.initialize(
            LlmModelConfig(
                modelPath = path,
                accelerator = Accelerator.CPU,
                maxTokens = config.contextTokens,
                topK = config.sampling.topK,
                topP = config.sampling.topP,
                temperature = config.sampling.temperature,
            ),
        )
        instance = cpu
        conversationSampling = null
        val cpuConfig = config.copy(accelerator = Accelerator.CPU)
        loaded = path to cpuConfig
        _state.value = ModelLoadState.Ready(path, cpuConfig, 0L)
        return cpu
    }

    /**
     * Makes the conversation of this reply: reuses the live one when its sampling and its tools are the same, rebuilds it
     * otherwise. With [kit] the system instruction is the letter grounding followed by [toolPrompt] (the skills), and the
     * conversation is given the tools.
     */
    private suspend fun ensureConversation(live: LlmModelInstance, sampling: Sampling, kit: LiteRtToolKit?, toolPrompt: String?) {
        val key = ConversationKey(sampling, withTools = kit != null)
        if (conversationSampling == key) return
        val config = loaded?.second
        val instruction = listOfNotNull(system.takeIf { it.isNotBlank() }, toolPrompt.takeIf { kit != null }).joinToString("\n\n")
        helper.resetConversation(
            instance = live,
            config = LlmModelConfig(
                modelPath = loaded?.first.orEmpty(),
                accelerator = live.accelerator,
                maxTokens = config?.contextTokens ?: 0,
                topK = sampling.topK,
                topP = sampling.topP,
                temperature = sampling.temperature,
            ),
            systemInstruction = instruction.takeIf { it.isNotBlank() }?.let { Contents.of(it) },
            initialMessages = committed.map { if (it.fromUser) Message.user(it.text) else Message.model(it.text) },
            tools = kit?.providers.orEmpty(),
        )
        conversationSampling = key
    }

    override suspend fun lastReplyHitLimit(): Boolean = hitLimit

    override suspend fun commitChatReply(answer: String): Unit = mutex.withLock {
        // The model may have been replaced since the reply began (a document was read in between): nothing to record into.
        if (sessionId == null) return@withLock
        // A reply of only an action (no words) is no turn to replay: a blank model turn would break the alternation.
        if (answer.isBlank()) {
            pendingUser = null
            return@withLock
        }
        pendingUser?.let { committed += LiteRtTurn(fromUser = true, text = it) }
        committed += LiteRtTurn(fromUser = false, text = answer)
        pendingUser = null
    }

    override suspend fun discardPendingReply(): Unit = mutex.withLock {
        if (sessionId == null) return@withLock
        // The abandoned question and its half-formed reply are not history; the conversation that holds them is rebuilt.
        pendingUser = null
        conversationSampling = null
    }

    override suspend fun resetChatSession(): Unit = mutex.withLock {
        sessionId = null
        system = ""
        committed.clear()
        pendingUser = null
        conversationSampling = null
    }

    override suspend fun unload(): Unit = mutex.withLock {
        release()
        _state.value = ModelLoadState.Idle
    }

    /** Frees the engine and forgets everything that lived in it. Caller holds [mutex]. */
    private fun release() {
        instance?.let { helper.cleanUp(it) }
        instance = null
        loaded = null
        sessionId = null
        system = ""
        committed.clear()
        pendingUser = null
        conversationSampling = null
    }

    /** Holds [mutex] for the whole of [source]'s collection, as the llama.cpp engines do (see their `serialised`). */
    private fun serialised(source: Flow<String>): Flow<String> = flow { mutex.withLock { emitAll(source) } }

    private companion object {
        /** How often the idle watchdog looks, and how long a reply may stay silent (a tool call or a slow CPU prefill included). */
        const val WATCHDOG_STEP_MS = 5_000L
        const val REPLY_IDLE_TIMEOUT_MS = 180_000L
    }

    private data class Sampling(val topK: Int, val topP: Float, val temperature: Float)

    /** What a live conversation was built with; any difference from the next reply's means a new conversation. */
    private data class ConversationKey(val sampling: Sampling, val withTools: Boolean)
}
