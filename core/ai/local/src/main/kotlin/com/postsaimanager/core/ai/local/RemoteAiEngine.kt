package com.postsaimanager.core.ai.local

import android.content.Context
import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.InferenceCrash
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [AiEngine] that runs inference in the `:inference` process.
 *
 * This is the implementation the app binds. `LocalAiEngine` remains the in-process engine
 * used by instrumented tests — nothing on the UI side touches native code directly.
 *
 * ### Why the boundary exists
 *
 * Spike Q3: a C++ abort inside llama.cpp produced `SIGABRT` and
 * `Zygote: Process exited due to signal 6`, killing the whole app. It cannot be caught in
 * Kotlin. Isolated, the same abort kills only the inference process — [DeathRecipient]
 * observes it, the engine reports a typed failure, and the next request rebinds.
 *
 * The cost is IPC latency per token. At 83–173 tok/s the binder overhead is far below the
 * generation cost, so it does not change how the stream feels.
 *
 * ### Lifecycle
 *
 * All load/reload/unload orchestration — single-flight, [com.postsaimanager.core.model
 * .ReloadScope] dispatch, the stale-generation guard — lives in [ModelLoadCoordinator],
 * driven here through a [ModelLoadOps] that speaks AIDL to the `:inference` process.
 */
@Singleton
class RemoteAiEngine @Inject constructor(
    @ApplicationContext context: Context,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
    /** The binding, the call mutex and the resident-runtime record, shared with the LiteRT-LM engine. */
    private val connection: InferenceConnection = InferenceConnection(context),
) : AiEngine, PromptSession {

    private val service: IInferenceService? get() = connection.service

    /**
     * Which conversation's chat session is currently primed in the `:inference` process's
     * KV cache, or null when none is (nothing opened yet, or the most recent generation was
     * a one-shot [generate] call — e.g. `AiExtractionUseCase` — or the KV cache was
     * invalidated by a reload/unload). See [ensureChatSession].
     */
    @Volatile
    private var sessionConversationId: String? = null

    /**
     * Serialises every call that touches the native context on the app-process side of the
     * AIDL boundary — see [AiEngine]'s class KDoc, "One native context, two callers", for the
     * full guarantee this is half of.
     *
     * `InferenceService`'s single-threaded `executor` already serialises the native calls
     * themselves, but that alone is not enough: `startGeneration`/`sendChatMessage` there
     * return immediately after *queueing* work and reset a single shared `cancelled` flag on
     * the calling (binder) thread before the queued work runs. Two callers racing that AIDL
     * boundary — an extraction [generate] call arriving while a chat [sendChatMessage] stream
     * is in flight — could have the extraction call's `cancelled.set(false)` land between the
     * user tapping Stop and the chat's token loop observing it, silently defeating the Stop.
     * Holding this mutex for the *entire* duration of each call (including the whole token
     * stream, not just the request that starts it) makes that interleaving impossible: only
     * one logical caller is ever "in flight" against the service at a time, so the shared flag
     * is never touched by two callers at once. FIFO-fair, so a caller queued behind an
     * in-flight [generate] waits only for that one call, never for a whole multi-document
     * pipeline (`AiExtractionUseCase` makes one [generate] call per document and awaits it
     * before the next).
     *
     * Deliberately **not** taken by `ops.loadModel`/`recreateContext`/`unloadModel` below
     * (driven by [ModelLoadCoordinator]): [generate]'s own crash-recovery path calls
     * `coordinator.ensureLoaded()` — and so `ops.loadModel` — *while already holding* this
     * mutex, and a non-reentrant [Mutex] would deadlock on itself. Those calls are already
     * safe without it: [ModelLoadCoordinator] has its own single-flight mutex, and
     * `InferenceService`'s blocking, single-threaded `executor` cannot run a load concurrently
     * with an in-flight generation on the native side regardless — see its class KDoc.
     */
    private val engineMutex: Mutex get() = connection.engineMutex

    override suspend fun isChatSessionPrimed(conversationId: String): Boolean =
        sessionConversationId == conversationId

    // Every method below crosses the binder to the `:inference` process and blocks the
    // calling thread until the far side replies — `loadModel` in particular can take
    // seconds. `ModelLoadCoordinator.load` (and everything upstream of it, ultimately
    // `ChatViewModel.sendMessage`'s `viewModelScope.launch`, which defaults to
    // `Dispatchers.Main.immediate`) is otherwise perfectly happy to run these suspend
    // functions on whatever dispatcher the caller used — Kotlin does not move a coroutine
    // to a background thread just because it awaits IPC. Without `withContext(ioDispatcher)`
    // here, a cold-start model load freezes the whole UI (composer, scrolling, everything)
    // for the entire load: this was defect 1's root cause. Wrapping it here, once, is what
    // makes every caller correct without each of them having to remember to dispatch.
    private val ops = object : ModelLoadOps {
        override suspend fun loadModel(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> =
            withContext(ioDispatcher) {
                val remote = connect() ?: return@withContext PamResult.Error(
                    PamError.ModelNotLoaded("Could not start the AI engine."),
                )
                if (!File(modelId).exists()) {
                    return@withContext PamResult.Error(PamError.FileNotFound(modelId))
                }
                val ok = runCatching {
                    remote.loadModel(modelId, InferenceConfigParcel.from(config))
                }.getOrDefault(false)
                // A model load recreates the llama_context, taking the KV cache — and any
                // primed chat session — with it, whether or not the load itself succeeded.
                sessionConversationId = null
                // The service frees a LiteRT-LM model before it loads this one.
                connection.resident = if (ok) ModelRuntime.LLAMA_CPP else null
                if (!ok) {
                    return@withContext PamResult.Error(
                        PamError.ModelNotLoaded(
                            "Could not load ${File(modelId).name}. The file may be corrupt or use " +
                                "an unsupported model architecture.",
                        ),
                    )
                }
                PamResult.Success(capabilitiesOf(remote, modelId, config))
            }

        override suspend fun recreateContext(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> =
            withContext(ioDispatcher) {
                val remote = connect() ?: return@withContext PamResult.Error(
                    PamError.ModelNotLoaded("Could not reach the AI engine."),
                )
                val ok = runCatching { remote.recreateContext(InferenceConfigParcel.from(config)) }
                    .getOrDefault(false)
                // Same reasoning as loadModel(): a fresh llama_context has an empty KV cache.
                sessionConversationId = null
                if (!ok) {
                    return@withContext PamResult.Error(PamError.ModelNotLoaded("Could not apply the new settings."))
                }
                PamResult.Success(capabilitiesOf(remote, modelId, config))
            }

        override suspend fun unloadModel() = withContext(ioDispatcher) {
            sessionConversationId = null
            // Only llama.cpp's own model: a LiteRT-LM one is that engine's to free (a fullLoad here is about to replace it anyway).
            if (connection.resident != ModelRuntime.LITERT_LM) {
                runCatching { service?.unloadModel() }
                connection.resident = null
            }
            Unit
        }

        override suspend fun isActuallyLoaded(): Boolean = withContext(ioDispatcher) {
            runCatching { service?.isReady == true }.getOrDefault(false)
        }
    }

    private val coordinator = ModelLoadCoordinator(ops)

    override val state: StateFlow<ModelLoadState> = coordinator.state

    override val isReady: Boolean
        get() = runCatching { service?.isReady == true }.getOrDefault(false)

    override val isBusy: Boolean get() = engineMutex.isLocked

    override val crashEvents: SharedFlow<InferenceCrash> get() = connection.crashes

    private fun onInferenceProcessDied(heldBy: ModelRuntime?) {
        // The boundary did its job: observe the crash instead of dying with it.
        sessionConversationId = null
        // A LiteRT-LM model was the one resident: that engine reports its own crash, and this engine's next load finds nothing
        // resident (`isActuallyLoaded`) and loads again. Reporting a failure here would blame llama.cpp's model for it.
        if (heldBy == ModelRuntime.LITERT_LM) return
        val generation = coordinator.currentGeneration()
        // Captured *before* reportExternalFailure, which is keyed off the same
        // lastRequested the coordinator would otherwise replay unchanged — this is what a
        // listener needs to tell a GPU crash from a CPU one and react (see crashEvents).
        val snapshot = coordinator.lastRequestedSnapshot()
        coordinator.reportExternalFailure(
            generation,
            coordinator.currentModelId(),
            "The AI engine crashed while generating. " +
                (
                    if (snapshot?.second?.accelerator == Accelerator.GPU) {
                        "Switched to CPU — please retry."
                    } else {
                        "Your documents are unaffected — send the message again to restart it."
                    }
                ),
        )
        // Nothing to report if no model was ever requested — there is no config a
        // listener could act on.
        snapshot?.let { (modelId, config) -> connection.reportCrash(InferenceCrash(modelId, config)) }
    }

    init {
        connection.onDeath(::onInferenceProcessDied)
    }

    /** Binds `:inference`, or returns the already-bound service — see [InferenceConnection.connect]. */
    private suspend fun connect(): IInferenceService? = connection.connect()

    private fun capabilitiesOf(remote: IInferenceService, modelId: String, config: InferenceConfig) =
        AiCapabilities(
            supportsGrammar = true,
            contextTokens = config.contextTokens,
            modelName = File(modelId).nameWithoutExtension,
            hasNativeChatTemplate = runCatching { remote.hasNativeChatTemplate() }.getOrDefault(false),
        )

    override suspend fun load(
        modelPath: String,
        config: InferenceConfig,
    ): PamResult<AiCapabilities> = coordinator.load(modelPath, config)

    override fun generate(request: AiRequest): Flow<String> = engineMutex.serialised(generateFlow(request))

    private fun generateFlow(request: AiRequest): Flow<String> = callbackFlow {
        // `callbackFlow`'s producer block runs in the collector's context — a plain
        // `Dispatchers.Main.immediate` for anything reached from `viewModelScope.launch` —
        // so every blocking binder call below is explicitly moved to `ioDispatcher`. See
        // the doc on `ops` above.
        val remote = withContext(ioDispatcher) { connect() } ?: run {
            close(IllegalStateException("The AI engine is not running."))
            return@callbackFlow
        }

        // A one-shot generation (AiExtractionUseCase's grammar path) clears the KV cache on
        // the native side — see llama_jni.cpp's startGeneration doc — which takes any
        // primed chat session with it. Marking it gone here (rather than only after a
        // reload) is what makes the *next* chat turn re-prime instead of silently decoding
        // a diff against a cache that no longer holds what it thinks it holds.
        sessionConversationId = null

        // A previous crash (binder death moves the coordinator to Failed), or a
        // memory-pressure unload in the :inference process, can leave nothing actually
        // resident. Reload through the coordinator rather than issuing a bare loadModel
        // call, so the state machine (and the generation counter) stay accurate — and so
        // this recovers from Failed, not just from a stale Ready.
        val isReady = withContext(ioDispatcher) { remote.isReady }
        if (!isReady) {
            when (val reloaded = coordinator.ensureLoaded()) {
                null -> {
                    // Nothing has ever been requested (or it was cleared by an explicit
                    // unload()) — there is genuinely no model to fall back on.
                    close(IllegalStateException("No model is loaded"))
                    return@callbackFlow
                }
                is PamResult.Error -> {
                    close(IllegalStateException("The AI engine could not reload the model."))
                    return@callbackFlow
                }
                is PamResult.Success -> Unit
            }
        }

        // Info-level so a manual GPU-vs-CPU comparison (e.g. the Adreno Vulkan workaround
        // investigation, documentation/02-architecture.md §5.3) doesn't need a debug build —
        // `adb logcat -s RemoteAiEngine` on a release-debuggable build is enough.
        val genStartNanos = System.nanoTime()
        var tokenCount = 0
        val callback = object : ITokenCallback.Stub() {
            override fun onToken(token: String?) {
                token?.let {
                    tokenCount++
                    trySend(it)
                }
            }

            override fun onComplete() {
                logGenerationTiming(genStartNanos, tokenCount)
                close()
            }

            override fun onError(message: String?) {
                logGenerationTiming(genStartNanos, tokenCount)
                close(IllegalStateException(message ?: "Generation failed."))
            }
        }

        val started = withContext(ioDispatcher) {
            runCatching {
                remote.startGeneration(
                    request.prompt,
                    request.maxTokens,
                    request.temperature,
                    request.topK,
                    request.topP,
                    request.presencePenalty,
                    request.seed ?: -1L,
                    request.grammar,
                    callback,
                )
            }.getOrDefault(false)
        }

        if (!started) {
            close(IllegalArgumentException("Prompt produced no tokens"))
            return@callbackFlow
        }

        // A binder death mid-generation (the Vulkan pipeline abort this exists for) kills
        // the :inference process *after* startGeneration() has already returned true —
        // the death recipient updates `state` to Failed, but nothing else here observes
        // it, so without this the token callback we are waiting on simply never arrives
        // and the flow — and the "Thinking…" state above it — hangs forever. Watching
        // `state` (rather than binding a second DeathRecipient) reuses the coordinator's
        // own generation-guarded failure reporting instead of adding a second source of
        // truth for the same event. Started only now, once generation is actually under
        // way: it is an infinite collector (state never completes on its own), so it must
        // be launched somewhere `awaitClose` is guaranteed to run and cancel it — an
        // earlier early return would otherwise leave it running forever, since a
        // callbackFlow's producer coroutine cannot complete while a child job is alive.
        val deathWatch = launch {
            state.collect { s ->
                if (s is ModelLoadState.Failed) {
                    close(IllegalStateException(s.error))
                }
            }
        }

        awaitClose {
            deathWatch.cancel()
            // Covers both normal completion and collector cancellation, so a stopped
            // stream does not leave the far side generating into the void.
            runCatching { remote.cancelGeneration() }
        }
    }

    override suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean {
        if (sessionConversationId == conversationId) return false

        // Held for both AIDL calls below (openChatSession + primeChatSession) — without this,
        // an extraction generate() queued on InferenceService's executor between the two could
        // clear the KV cache mid-prime, and primeChatSession would then replay history onto a
        // context that had just been wiped out from under it. See engineMutex's doc.
        return engineMutex.withLock {
            withContext(ioDispatcher) {
                // Re-checked under the lock: a second caller (prime-on-open racing a send)
                // that passed the unlocked check above while the first was still priming must
                // join that prime rather than decode the whole conversation a second time.
                if (sessionConversationId == conversationId) return@withContext false
                val remote = connect() ?: return@withContext false
                val opened = runCatching { remote.openChatSession(systemPrompt) }.getOrDefault(false)
                if (!opened) return@withContext false

                if (history.isNotEmpty()) {
                    val primed = runCatching {
                        remote.primeChatSession(
                            history.map { it.role.wireName }.toTypedArray(),
                            history.map { it.content }.toTypedArray(),
                        )
                    }.getOrDefault(false)
                    if (!primed) return@withContext false
                }
                sessionConversationId = conversationId
                Log.i(TAG, "ensureChatSession: primed conversation with ${history.size} prior turns")
                true
            }
        }
    }

    override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> =
        engineMutex.serialised(sendChatMessageFlow(userText, request))

    private fun sendChatMessageFlow(userText: String, request: AiRequest): Flow<String> = callbackFlow {
        // See the note in generate() — this producer block otherwise inherits the
        // collector's (often Main) dispatcher.
        val remote = withContext(ioDispatcher) { connect() } ?: run {
            close(IllegalStateException("The AI engine is not running."))
            return@callbackFlow
        }

        val genStartNanos = System.nanoTime()
        var tokenCount = 0
        val callback = object : ITokenCallback.Stub() {
            override fun onToken(token: String?) {
                token?.let {
                    tokenCount++
                    trySend(it)
                }
            }

            override fun onComplete() {
                logGenerationTiming(genStartNanos, tokenCount, "sendChatMessage")
                close()
            }

            override fun onError(message: String?) {
                logGenerationTiming(genStartNanos, tokenCount, "sendChatMessage")
                close(IllegalStateException(message ?: "Generation failed."))
            }
        }

        val started = withContext(ioDispatcher) {
            runCatching {
                remote.sendChatMessage(
                    userText,
                    request.maxTokens,
                    request.temperature,
                    request.topK,
                    request.topP,
                    request.presencePenalty,
                    request.seed ?: -1L,
                    request.grammar,
                    !request.thinkingEnabled,
                    request.thinkingBudgetTokens,
                    callback,
                )
            }.getOrDefault(false)
        }

        if (!started) {
            close(IllegalArgumentException("The chat turn could not be started"))
            return@callbackFlow
        }

        // Same death-watch as generate() — see its doc.
        val deathWatch = launch {
            state.collect { s ->
                if (s is ModelLoadState.Failed) close(IllegalStateException(s.error))
            }
        }

        awaitClose {
            deathWatch.cancel()
            runCatching { remote.cancelGeneration() }
        }
    }

    override suspend fun lastReplyHitLimit(): Boolean = withContext(ioDispatcher) {
        val remote = service ?: return@withContext false
        runCatching { remote.lastReplyHitLimit() }.getOrDefault(false)
    }

    override suspend fun commitChatReply(answer: String) = engineMutex.withLock {
        withContext(ioDispatcher) {
            val remote = service ?: return@withContext
            runCatching { remote.commitChatReply(answer) }
            Unit
        }
    }

    /**
     * See [AiEngine.discardPendingReply]. Skipped entirely — rather than calling the AIDL
     * method with no session open — when nothing has been primed, since there is then
     * nothing native to roll back.
     */
    override suspend fun discardPendingReply() = engineMutex.withLock {
        withContext(ioDispatcher) {
            val remote = service ?: return@withContext
            runCatching { remote.discardPendingReply() }
            Unit
        }
    }

    override suspend fun resetChatSession() = engineMutex.withLock {
        withContext(ioDispatcher) {
            sessionConversationId = null
            val remote = service ?: return@withContext
            runCatching { remote.resetChatSession() }
            Unit
        }
    }

    // ── PromptSession: read once, ask many short questions ───────────────────────────────

    /**
     * The open prompt session's prefix, kept here so that a lost KV state (a chat turn or a one-shot
     * generation ran between two questions, or the process was restarted) is repaired by decoding it
     * again instead of failing the question. Null when no session is open.
     */
    @Volatile
    private var promptPrefix: String? = null

    /**
     * Each call below takes [engineMutex] for its own duration only, so a chat turn can run between two
     * questions of one session; see [PromptSession]'s KDoc for what that costs (one re-read of the prefix).
     */
    override suspend fun open(prefix: String): PamResult<Int> = engineMutex.withLock {
        withContext(ioDispatcher) { openLocked(prefix) }
    }

    private suspend fun openLocked(prefix: String): PamResult<Int> {
        val remote = connect() ?: return PamResult.Error(PamError.ModelNotLoaded("Could not reach the AI engine."))
        if (!remote.isReady && coordinator.ensureLoaded().let { it == null || it is PamResult.Error }) {
            return PamResult.Error(PamError.ModelNotLoaded("No model is loaded."))
        }
        // Reading the prefix takes the KV cache over from any primed chat session, like a one-shot generate().
        sessionConversationId = null
        val tokens = runCatching { remote.promptOpen(prefix) }.getOrDefault(-1)
        return when {
            tokens >= 0 -> {
                promptPrefix = prefix
                Log.i(TAG, "prompt session opened: $tokens prefix tokens")
                PamResult.Success(tokens)
            }
            tokens == -2 -> PamResult.Error(PamError.InferenceError("the letter is too long for the model's context window"))
            else -> PamResult.Error(PamError.InferenceError("the model could not read the letter"))
        }
    }

    override suspend fun ask(question: String, grammar: String, maxTokens: Int): PamResult<String> =
        engineMutex.withLock {
            withContext(ioDispatcher) {
                val remote = service ?: connect()
                    ?: return@withContext PamResult.Error(PamError.ModelNotLoaded("Could not reach the AI engine."))
                val prefix = promptPrefix
                    ?: return@withContext PamResult.Error(PamError.InferenceError("no prompt session is open"))

                // A coroutine cancelled mid-answer stops the native loop instead of leaving it to run out.
                withCancelHook({ runCatching { remote.cancelGeneration() } }) {
                    val started = System.nanoTime()
                    var answer = runCatching { remote.promptAsk(question, grammar, maxTokens) }.getOrNull()
                    if (answer == null && coroutineContext.isActive) {
                        // Lost: something else used the KV cache since the last question. Read the prefix again, once.
                        Log.i(TAG, "prompt session lost — reading the prefix again")
                        if (openLocked(prefix) is PamResult.Success) {
                            answer = runCatching { remote.promptAsk(question, grammar, maxTokens) }.getOrNull()
                        }
                    }
                    Log.i(TAG, "ask: ${(System.nanoTime() - started) / 1_000_000} ms, ${answer?.length ?: -1} chars")
                    if (answer == null) {
                        PamResult.Error(PamError.InferenceError("the question could not be answered"))
                    } else {
                        PamResult.Success(answer)
                    }
                }
            }
        }

    override suspend fun score(continuations: List<String>, yes: String, no: String, shared: String): PamResult<List<Double>> =
        engineMutex.withLock {
            withContext(ioDispatcher) {
                val remote = service ?: connect()
                    ?: return@withContext PamResult.Error(PamError.ModelNotLoaded("Could not reach the AI engine."))
                val prefix = promptPrefix
                    ?: return@withContext PamResult.Error(PamError.InferenceError("no prompt session is open"))
                if (continuations.isEmpty()) return@withContext PamResult.Success(emptyList())
                val array = continuations.toTypedArray()
                withCancelHook({ runCatching { remote.cancelGeneration() } }) {
                    var scores = runCatching { remote.promptScore(shared, array, yes, no) }.getOrNull()
                    if (scores == null && coroutineContext.isActive) {
                        Log.i(TAG, "prompt session lost while scoring — reading the prefix again")
                        if (openLocked(prefix) is PamResult.Success) {
                            scores = runCatching { remote.promptScore(shared, array, yes, no) }.getOrNull()
                        }
                    }
                    if (scores == null) {
                        PamResult.Error(PamError.InferenceError("the continuations could not be scored"))
                    } else {
                        PamResult.Success(scores.toList())
                    }
                }
            }
        }

    override suspend fun scoreGrid(shared: String, heads: List<String>, asks: List<String>, yes: String, no: String): PamResult<List<List<Double>>> =
        engineMutex.withLock {
            withContext(ioDispatcher) {
                val remote = service ?: connect()
                    ?: return@withContext PamResult.Error(PamError.ModelNotLoaded("Could not reach the AI engine."))
                val prefix = promptPrefix
                    ?: return@withContext PamResult.Error(PamError.InferenceError("no prompt session is open"))
                if (heads.isEmpty() || asks.isEmpty()) return@withContext PamResult.Success(heads.map { emptyList() })
                val headArray = heads.toTypedArray()
                val askArray = asks.toTypedArray()
                withCancelHook({ runCatching { remote.cancelGeneration() } }) {
                    var flat = runCatching { remote.promptScoreGrid(shared, headArray, askArray, yes, no) }.getOrNull()
                    if (flat == null && coroutineContext.isActive) {
                        Log.i(TAG, "prompt session lost while scoring a grid — reading the prefix again")
                        if (openLocked(prefix) is PamResult.Success) {
                            flat = runCatching { remote.promptScoreGrid(shared, headArray, askArray, yes, no) }.getOrNull()
                        }
                    }
                    if (flat == null || flat.size != heads.size * asks.size) {
                        PamResult.Error(PamError.InferenceError("the grid could not be scored"))
                    } else {
                        PamResult.Success(heads.indices.map { i -> asks.indices.map { j -> flat[i * asks.size + j] } })
                    }
                }
            }
        }

    override suspend fun close() = engineMutex.withLock {
        withContext(ioDispatcher) {
            if (promptPrefix == null) return@withContext
            promptPrefix = null
            val remote = service ?: return@withContext
            runCatching { remote.promptClose() }
            Unit
        }
    }

    override suspend fun countTokens(text: String): Int? = engineMutex.withLock {
        withContext(ioDispatcher) {
            val remote = service ?: return@withContext null
            runCatching { remote.countTokens(text) }.getOrNull()?.takeIf { it >= 0 }
        }
    }

    override fun formatPrompt(messages: List<AiChatMessage>): String {
        // Debug-only, and roles + lengths rather than content: this is the one place a
        // caller can verify "what is sent to the model" (see SendChatMessageUseCase's KDoc
        // on that rule) without shipping a log line that captures document text or a
        // reasoning trace into logcat at a level anyone would see by default.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            val turns = messages.joinToString(", ") { "${it.role.wireName}(${it.content.length}c)" }
            Log.d(TAG, "formatPrompt: ${messages.size} turns -> [$turns]")
        }
        val remote = service
        if (remote != null && messages.isNotEmpty()) {
            val native = runCatching {
                remote.formatChat(
                    messages.map { it.role.wireName }.toTypedArray(),
                    messages.map { it.content }.toTypedArray(),
                    true,
                )
            }.getOrNull()
            if (native != null) return native
        }
        return ChatTemplateFallback.chatMl(messages)
    }

    override suspend fun unload() = coordinator.unload()

    /** Called by [InferenceMemoryPressureObserver] on app-process memory pressure. */
    internal suspend fun onTrimMemory() = coordinator.unloadOnMemoryPressure()

    /**
     * Probes the `:inference` process for available accelerators.
     *
     * Binds the service if it is not already bound — the probe needs no model loaded — but
     * defaults to CPU-only rather than propagating a failure: a service that cannot be
     * reached is exactly the case where "assume no GPU" is the safe answer.
     */
    override suspend fun availableAccelerators(): Set<Accelerator> = withContext(ioDispatcher) {
        val remote = connect() ?: return@withContext setOf(Accelerator.CPU)
        val ordinals = runCatching { remote.availableAccelerators() }.getOrNull()
            ?: return@withContext setOf(Accelerator.CPU)
        val accelerators = ordinals.toList().mapNotNull { ordinal ->
            Accelerator.entries.getOrNull(ordinal)
        }.toSet()
        accelerators.ifEmpty { setOf(Accelerator.CPU) }
    }

    /**
     * Diagnostic, not part of [AiEngine]: which devices the most recent successful `loadModel`
     * actually used in the `:inference` process — `"CPU"`, `"all"`, or `"none"`. `LlamaNative`
     * only ever loads inside that process, so this has to cross the same AIDL boundary as
     * everything else here rather than being called directly. Used by
     * `GpuSmokeTest.cpuAcceleratorNeverTouchesTheGpuDeviceOnAVulkanBuild`.
     */
    suspend fun lastLoadDevices(): String? = withContext(ioDispatcher) {
        val remote = connect() ?: return@withContext null
        runCatching { remote.lastLoadDevices() }.getOrNull()
    }

    /**
     * See the `genStartNanos`/`tokenCount` doc comment in [generate]. Shared by [generate]
     * and [sendChatMessage] — [label] tells the two apart in logcat, since the native-side
     * `prompt_eval_ms`/`new_tokens` lines already distinguish the one-shot and chat-session
     * decode paths and this only needs to add generation throughput on top.
     */
    private fun logGenerationTiming(genStartNanos: Long, tokenCount: Int, label: String = "generate") {
        val seconds = (System.nanoTime() - genStartNanos) / 1_000_000_000.0
        val tokensPerSecond = if (seconds > 0) tokenCount / seconds else 0.0
        Log.i(TAG, "$label: tokens=$tokenCount seconds=$seconds tokensPerSecond=$tokensPerSecond")
    }

    private companion object {
        const val TAG = "RemoteAiEngine"
    }
}
