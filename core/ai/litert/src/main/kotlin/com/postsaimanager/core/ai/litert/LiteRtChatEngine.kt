package com.postsaimanager.core.ai.litert

import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.TimingLog
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.skills.JsSkillRequest
import com.postsaimanager.core.domain.skills.SkillCatalog
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ToolExchange
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
import java.time.LocalDate
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
 * As in the Gallery, the live `Conversation` is what keeps the real history, tool-call turns included, from one reply to the
 * next. A rebuild must not lose them: the committed turns carry each reply's tool calls and results ([LiteRtTurn.tools], stored
 * with the message by the app), and [LiteRtMessages] replays them as tool-call turns. A history that shows "I created the reminder"
 * with no call in front of it teaches the model to claim actions without making them. Like the Gallery's compactor, a conversation
 * past 75% of its window is summarised by the model and restarted from that summary ([LiteRtContextCompactor]), with the newest
 * exchange kept after it; if no summary comes, from the newest turns that fit ([LiteRtTurns.compact]).
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
 * ### Thinking
 *
 * A reply whose [AiRequest.thinkingEnabled] is set runs with `enable_thinking=true`, and the thought channel is written into the
 * stream between think tags ([ThoughtStream]) for the app's existing parser. Off (the default of the chat) nothing changes:
 * `enable_thinking=false` and the channel is ignored.
 *
 * ### Pictures
 *
 * A reply with [AiRequest.imagePaths] sends the files, as the Gallery does (`Content.ImageBytes` in front of the text). The
 * engine starts its vision encoder only then: a model loaded for plain chat is restarted once with it ([withVision]), so a chat
 * that never carries a picture is exactly what it was. Only the current turn sends the pixels; a rebuilt conversation replays
 * the text (the app puts a marker in it).
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

    override val supportsThinking: Boolean get() = true

    override suspend fun deliverJsResult(requestId: String, result: String) {
        toolKit?.deliverJsResult(requestId, result)
    }

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

    /** The Gallery's summarise-and-reset decisions for a conversation past 75% of its window. */
    private val compactor = LiteRtContextCompactor()

    @Volatile
    private var hitLimit = false

    /** True when the reply in flight (or the last) ran with the tools, so [lastReplyToolExchanges] has a meaning. */
    @Volatile
    private var replyUsedTools = false

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
        onToolExchange: (ToolExchange) -> Unit = {},
        onRunJs: (JsSkillRequest) -> Unit = {},
    ): Flow<String> = serialised(replyFlow(userText, request, onToolAction, onFallback, onToolExchange, onRunJs))

    private fun replyFlow(
        userText: String,
        request: AiRequest,
        onToolAction: (ToolActionCall) -> Unit,
        onFallback: () -> Unit,
        onToolExchange: (ToolExchange) -> Unit,
        onRunJs: (JsSkillRequest) -> Unit,
    ): Flow<String> = callbackFlow {
        var live = instance
        if (live == null) {
            close(IllegalStateException("No model is loaded."))
            return@callbackFlow
        }
        TimingLog.mark()
        TimingLog.log(
            "engine: reply begins, backend=${live.accelerator} tools=${request.tools != null} thinking=${request.thinkingEnabled} " +
                "maxTokens=${request.maxTokens} text=${userText.length} chars committedTurns=${committed.size} system=${system.length} chars",
        )
        hitLimit = false
        // The pictures of this reply, read from the files the app stored; one that cannot be read is left out.
        val images = request.imagePaths.mapNotNull { path -> runCatching { File(path).readBytes() }.getOrNull() }
        if (images.isNotEmpty()) {
            try {
                live = withContext(Dispatchers.IO) { withVision(live!!) }
            } catch (e: Exception) {
                close(e)
                return@callbackFlow
            }
        }
        val current = live!!
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
                ensureConversation(current, Sampling(request.topK, request.topP, request.temperature), activeKit, toolPrompt)
            }
        } catch (e: Exception) {
            close(e)
            return@callbackFlow
        }
        TimingLog.at("engine: conversation ready (toolPrompt=${toolPrompt?.length ?: 0} chars)")
        pendingUser = userText
        replyUsedTools = activeKit != null
        activeKit?.bind(request.tools?.documentId, onToolAction, onToolExchange, onRunJs)

        val filter = ReplyTextFilter()
        val thoughts = ThoughtStream(enabled = request.thinkingEnabled)
        var chunks = 0
        var running = current
        var fellBack = false
        val finished = AtomicBoolean(false)
        val lastActivityNanos = AtomicLong(System.nanoTime())
        var firstChunkMs = -1L
        var lastChunkMs = 0L

        fun runOn(inst: LlmModelInstance) {
            TimingLog.at("engine: sendMessageAsync")
            running = inst
            lastActivityNanos.set(System.nanoTime())
            helper.runInference(
                instance = inst,
                input = userText,
                resultListener = { text, done, thinking ->
                    if (done) {
                        TimingLog.at(
                            "engine: DONE chunks=$chunks firstChunk=${firstChunkMs}ms decodeSpan=${lastChunkMs - firstChunkMs.coerceAtLeast(0)}ms " +
                                "backend=${inst.accelerator} toolsUsed=$replyUsedTools",
                        )
                        TimingLog.log("engine: " + LlmChatModelHelper.benchmarkLine(inst))
                        finished.set(true)
                        // Whatever the filter held back as a maybe-marker was ordinary text.
                        filter.finish().takeIf { it.isNotEmpty() }?.let { held ->
                            thoughts.closeBefore(held).takeIf { it.isNotEmpty() }?.let { trySend(it) }
                            trySend(held)
                        }
                        close()
                    } else if (!finished.get()) {
                        val nowMs = TimingLog.sinceMarkMs()
                        if (firstChunkMs < 0) {
                            firstChunkMs = nowMs
                            TimingLog.at("engine: first chunk (${text.length} chars): \"${text.take(40).replace("\n", " ")}\"")
                        } else if (nowMs - lastChunkMs > 1_500) {
                            TimingLog.at("engine: pause of ${nowMs - lastChunkMs}ms before chunk ${chunks + 1} (tool round or prefill)")
                        }
                        lastChunkMs = nowMs
                        lastActivityNanos.set(System.nanoTime())
                        chunks++
                        // The reasoning goes first, as think-tagged text, when this reply asked for it.
                        thoughts.thought(thinking).takeIf { it.isNotEmpty() }?.let { trySend(it) }
                        // Control tokens and tool-call text are the model's protocol, not part of the answer.
                        filter.accept(text).takeIf { it.isNotEmpty() }?.let { answer ->
                            thoughts.closeBefore(answer).takeIf { it.isNotEmpty() }?.let { trySend(it) }
                            trySend(answer)
                        }
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
                extraContext = mapOf("enable_thinking" to request.thinkingEnabled.toString()),
                images = images,
            )
        }
        runOn(current)
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
                supportImage = failed.supportsImage,
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
     * The instance with its vision encoder started: [live] itself when it has one, otherwise the same model restarted with it (on
     * the accelerator it runs on). The next [ensureConversation] rebuilds the conversation from the committed turns, as after any
     * restart. Runs on a worker thread: starting an engine reads gigabytes.
     */
    private fun withVision(live: LlmModelInstance): LlmModelInstance {
        if (live.supportsImage) return live
        val (path, config) = loaded ?: throw IllegalStateException("No model is loaded.")
        Log.i(TAG, "a picture is attached: restarting the engine with its vision encoder")
        helper.cleanUp(live)
        instance = null
        val withImages = try {
            helper.initialize(
                LlmModelConfig(
                    modelPath = path,
                    accelerator = live.accelerator,
                    maxTokens = config.contextTokens,
                    topK = config.sampling.topK,
                    topP = config.sampling.topP,
                    temperature = config.sampling.temperature,
                    supportImage = true,
                ),
            )
        } catch (e: Throwable) {
            // The old engine is gone: the next message loads the model again.
            loaded = null
            sessionId = null
            _state.value = ModelLoadState.Failed(path, "Could not start the image input: ${e.message}")
            throw IllegalStateException("The model could not start with image input: ${e.message}")
        }
        instance = withImages
        conversationSampling = null
        return withImages
    }

    /**
     * Makes the conversation of this reply: reuses the live one when its sampling and its tools are the same, rebuilds it
     * otherwise. With [kit] the system instruction is the letter grounding followed by [toolPrompt] (the skills), and the
     * conversation is given the tools.
     */
    private suspend fun ensureConversation(live: LlmModelInstance, sampling: Sampling, kit: LiteRtToolKit?, toolPrompt: String?) {
        // The day is part of the key: the phone's date is in the system instruction, and a conversation kept past midnight would
        // keep saying yesterday. The rebuild is faithful (tool calls included), so a new day costs one prefill and nothing else.
        val key = ConversationKey(sampling, withTools = kit != null, day = if (kit != null) LocalDate.now() else null)
        val config = loaded?.second
        val window = config?.contextTokens ?: 0
        if (conversationSampling == key) {
            // The Gallery's context compaction: past 75% of the window the conversation is restarted, from less history. A live
            // conversation otherwise grows until the engine refuses it ("Prefill input length exceeds available state entries").
            val used = helper.tokenCount(live)
            if (!compactor.isOverThreshold(used, window)) {
                TimingLog.at("engine: conversation REUSED (context $used of $window tokens)")
                return
            }
            // Summarise and restart (the Gallery's SummarizationContextCompactor). When the summary fails, or the check is backing
            // off after a failure, the newest whole turns that fit are what the conversation restarts from instead.
            val attempt = compactor.shouldCompact(used, window)
            val summary = if (attempt) helper.summarize(live, compactor.summaryPrompt(compactor.wordLimit(used, window))) else null
            val restart = if (summary != null) {
                Log.i(TAG, "context at $used of $window tokens: restarting the conversation from its summary")
                compactor.onSuccess()
                compactor.turnsAfterSummary(summary, committed)
            } else {
                Log.i(TAG, "context at $used of $window tokens: no summary, restarting from the newest turns")
                if (attempt) compactor.onFailure()
                LiteRtTurns.compact(committed, LiteRtTurns.rebuildBudgetChars(window))
            }
            committed.clear()
            committed += restart
        }
        val instruction = listOfNotNull(system.takeIf { it.isNotBlank() }, toolPrompt.takeIf { kit != null }).joinToString("\n\n")
        TimingLog.at(
            "engine: conversation REBUILT, why=${if (conversationSampling == null) "no live conversation (session start, discard or reset)" else "key changed or over 75% of window"} " +
                "committedTurns=${committed.size} instruction=${instruction.length} chars (system=${system.length}, toolPrompt=${if (kit != null) toolPrompt?.length ?: 0 else 0})",
        )
        val modelConfig = LlmModelConfig(
            modelPath = loaded?.first.orEmpty(),
            accelerator = live.accelerator,
            maxTokens = window,
            topK = sampling.topK,
            topP = sampling.topP,
            temperature = sampling.temperature,
        )
        val systemInstruction = instruction.takeIf { it.isNotBlank() }?.let { Contents.of(it) }
        try {
            helper.resetConversation(
                instance = live,
                config = modelConfig,
                systemInstruction = systemInstruction,
                initialMessages = LiteRtMessages.of(committed, withTools = kit != null),
                tools = kit?.providers.orEmpty(),
            )
        } catch (e: Exception) {
            // The history with its tool calls was refused (a template that cannot render it): continue from its text, which at
            // least is a conversation, rather than failing the reply.
            if (committed.none { it.tools.isNotEmpty() }) throw e
            Log.w(TAG, "the history with tool calls was refused (${e.message}); replaying its text only")
            helper.resetConversation(
                instance = live,
                config = modelConfig,
                systemInstruction = systemInstruction,
                initialMessages = LiteRtMessages.of(committed, withTools = false),
                tools = kit?.providers.orEmpty(),
            )
            conversationSampling = key
            return
        }
        if (committed.any { it.tools.isNotEmpty() }) Log.i(TAG, "the history with its tool calls was accepted (replayed as tool-call turns)")
        conversationSampling = key
    }

    override suspend fun lastReplyHitLimit(): Boolean = hitLimit

    override suspend fun lastReplyToolExchanges(): List<ToolExchange> = if (replyUsedTools) toolKit?.exchanges().orEmpty() else emptyList()

    override suspend fun commitChatReply(answer: String): Unit = mutex.withLock {
        // The model may have been replaced since the reply began (a document was read in between): nothing to record into.
        if (sessionId == null) return@withLock
        // A reply of only an action (no words) is no turn to replay: a blank model turn would break the alternation.
        if (answer.isBlank()) {
            pendingUser = null
            return@withLock
        }
        pendingUser?.let { committed += LiteRtTurn(fromUser = true, text = it) }
        // The calls the reply made stay with it, so a rebuilt conversation shows the model its own calls and not just its words.
        committed += LiteRtTurn(fromUser = false, text = answer, tools = lastReplyToolExchanges())
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

        const val TAG = "PamLiteRt"
    }

    private data class Sampling(val topK: Int, val topP: Float, val temperature: Float)

    /** What a live conversation was built with; any difference from the next reply's means a new conversation. */
    private data class ConversationKey(val sampling: Sampling, val withTools: Boolean, val day: LocalDate? = null)
}
