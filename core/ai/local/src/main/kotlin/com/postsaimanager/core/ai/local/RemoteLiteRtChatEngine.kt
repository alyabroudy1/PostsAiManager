package com.postsaimanager.core.ai.local

import android.util.Log
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.domain.ai.ToolActionCall
import com.postsaimanager.core.domain.ai.ToolActionWire
import com.postsaimanager.core.domain.ai.InferenceCrash
import com.postsaimanager.core.domain.skills.JsSkillRequest
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.model.ToolExchange
import com.postsaimanager.core.model.ToolTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [ChatEngine] of LiteRT-LM models, as the app process sees it: every call goes over AIDL to the LiteRT-LM engine in the
 * `:inference` process (`LiteRtChatEngine`, behind `InferenceService`), the way [RemoteAiEngine] reaches llama.cpp. A GPU driver
 * crash therefore takes the answer in flight and not the app.
 *
 * It shares the binding, the call mutex and the "which runtime is resident" record with [RemoteAiEngine] through
 * [InferenceConnection]. That is what makes the two promises of the process design true:
 *
 * - **One engine resident at a time.** Loading here makes the service free the llama.cpp model, and the other way round, so
 *   this engine's own bookkeeping can go stale (a background read replaced the model). It is never trusted: [load] asks the
 *   service whether the model is really resident ([ModelLoadOps.isActuallyLoaded]) and reloads when it is not, and whether a
 *   session is primed ([isChatSessionPrimed]) is the service's answer, not a field here.
 * - **Background reading waits for a chat answer.** A reply streams under the shared mutex, and on the service's single
 *   inference thread, so a llama.cpp read queued behind it starts when the answer is over. Between two messages the model sits
 *   idle, and a quiet job that took it over then would cost the next message a reload and a full re-read of the conversation
 *   (tens of seconds on a phone CPU), so the chat counts as active for its whole session ([ChatSessionTracker], the one clock, read
 *   through [com.postsaimanager.core.domain.ai.ChatActivityGate]); quiet jobs wait for it to end, and are run again, not dropped.
 * - **The chat's load waits for a reading's call, never cuts it** ([ChatLoadPriority]).
 */
@Singleton
class RemoteLiteRtChatEngine @Inject constructor(
    private val connection: InferenceConnection,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ChatEngine {

    private val ops = object : ModelLoadOps {
        override suspend fun loadModel(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> =
            withContext(ioDispatcher) {
                val remote = connection.connect() ?: return@withContext PamResult.Error(
                    PamError.ModelNotLoaded("Could not start the AI engine."),
                )
                if (!File(modelId).exists()) return@withContext PamResult.Error(PamError.FileNotFound(modelId))
                // The service frees the llama.cpp model before it starts this one, whether or not this load succeeds.
                val startedOn = runCatching { remote.loadLiteRtModel(modelId, InferenceConfigParcel.from(config)) }.getOrNull()
                connection.resident = if (startedOn != null) ModelRuntime.LITERT_LM else null
                if (startedOn == null) {
                    return@withContext PamResult.Error(
                        PamError.ModelNotLoaded(
                            "Could not load ${File(modelId).name}. The file may be corrupt, or this phone cannot run it.",
                        ),
                    )
                }
                Log.i(TAG, "LiteRT-LM model loaded on $startedOn (asked for ${config.accelerator})")
                // The GPU engine could not start and the service fell back to the CPU: remember that, so the next load asks the CPU.
                if (config.accelerator == Accelerator.GPU && startedOn == Accelerator.CPU.name) {
                    connection.reportCrash(InferenceCrash(modelId, config))
                }
                PamResult.Success(
                    AiCapabilities(
                        supportsGrammar = false,
                        contextTokens = config.contextTokens,
                        modelName = File(modelId).nameWithoutExtension,
                        hasNativeChatTemplate = true,
                        // What the engine reports it started on, so the UI names the backend that runs, not the one asked for.
                        runningAccelerator = Accelerator.entries.firstOrNull { it.name == startedOn },
                    ),
                )
            }

        // LiteRT-LM fixes its window when the engine starts: a changed window is a full load.
        override suspend fun recreateContext(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> =
            loadModel(modelId, config)

        override suspend fun unloadModel() = withContext(ioDispatcher) {
            // Only LiteRT-LM's own model: a llama.cpp one is that engine's to free.
            if (connection.resident == ModelRuntime.LITERT_LM) {
                runCatching { connection.service?.unloadLiteRt() }
                connection.resident = null
            }
            Unit
        }

        override suspend fun isActuallyLoaded(): Boolean = withContext(ioDispatcher) {
            runCatching { connection.service?.isLiteRtReady == true }.getOrDefault(false)
        }
    }

    private val coordinator = ModelLoadCoordinator(ops)

    override val state: StateFlow<ModelLoadState> = coordinator.state

    /** True while this engine's own [warmUpChat] holds the model: that is the chat's own work, not another caller's. */
    @Volatile
    private var warmingUp = false

    // "Busy" says another caller (a document being read) holds the model, so the chat's own warm-up does not count: a message sent
    // while it runs waits for it as for any preparation, and must not be told a document is being read.
    override val isBusy: Boolean get() = connection.engineMutex.isLocked && !warmingUp

    // The engine streams the thought channel between think tags when the reply asks for thinking (Off by default in the chat).
    override val supportsThinking: Boolean get() = true

    /** The `run_js` calls the service delivers while a reply streams: the app runs the script and answers by [deliverJsResult]. */
    private val scripts = MutableSharedFlow<JsSkillRequest>(extraBufferCapacity = ACTION_BUFFER)

    override val jsRequests: Flow<JsSkillRequest> = scripts.asSharedFlow()

    override suspend fun deliverJsResult(requestId: String, result: String) {
        withContext(ioDispatcher) {
            runCatching { connection.service?.deliverJsResult(requestId, result) }
        }
    }

    /** The `run_intent` calls the service delivers while a reply streams. Hot, no replay; a collector (the chat) attaches once. */
    private val actions = MutableSharedFlow<ToolActionCall>(extraBufferCapacity = ACTION_BUFFER)

    override val toolActions: Flow<ToolActionCall> = actions.asSharedFlow()

    /** The tool calls (with results) of the reply in flight or the last one: what [lastReplyToolExchanges] hands to the chat. */
    private val replyExchanges = mutableListOf<ToolExchange>()

    // When the `:inference` process dies while it holds a LiteRT-LM model, the crash (the model and config that were running) goes to
    // the shared `connection.crashes`, so `InferenceCrashObserver` blocks the GPU for that model and the next load uses the CPU.
    init {
        connection.onDeath { heldBy ->
            if (heldBy != ModelRuntime.LITERT_LM) return@onDeath
            val generation = coordinator.currentGeneration()
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
            snapshot?.let { (modelId, config) -> connection.reportCrash(InferenceCrash(modelId, config)) }
        }
    }

    private val loadPriority = ChatLoadPriority(connection.engineMutex)

    override suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities> {
        val wanted = config.forLiteRt()
        // A reading's native call in flight is waited for, never cut (see [ChatLoadPriority]); the other runtime having taken the
        // model since (a Qwen reading) counts as a replacement too, whatever this coordinator last recorded.
        val replaces = coordinator.wouldReplaceResident(modelPath, wanted) || connection.resident != ModelRuntime.LITERT_LM
        return loadPriority.load(replaces) { coordinator.load(modelPath, wanted) }
    }

    /**
     * [config] with every llama.cpp-only setting fixed, so that a change the engine ignores (threads, batch, mmap, flash
     * attention) is never mistaken for a reason to restart a multi-gigabyte GPU engine.
     */
    private fun InferenceConfig.forLiteRt() = copy(
        batchTokens = InferenceConfig.DEFAULT_BATCH_TOKENS,
        threads = 1,
        threadsBatch = 1,
        useMmap = true,
        useMlock = false,
        flashAttention = false,
        gpuLayers = 0,
    )

    override suspend fun isChatSessionPrimed(conversationId: String): Boolean = withContext(ioDispatcher) {
        val remote = connection.service ?: return@withContext false
        runCatching { remote.isLiteRtSessionPrimed(conversationId) }.getOrDefault(false)
    }

    override suspend fun ensureChatSession(
        conversationId: String,
        systemPrompt: String,
        history: List<AiChatMessage>,
    ): Boolean {
        if (isChatSessionPrimed(conversationId)) return false
        // Held across the call: a read must not slip in and replace the model between opening the session and the reply.
        return connection.engineMutex.withLock {
            withContext(ioDispatcher) {
                if (isPrimedLocked(conversationId)) return@withContext false
                val remote = connection.connect() ?: return@withContext false
                if (!runCatching { remote.isLiteRtReady }.getOrDefault(false) && coordinator.ensureLoaded().let { it == null || it is PamResult.Error }) {
                    return@withContext false
                }
                val opened = runCatching {
                    remote.openLiteRtSession(
                        conversationId,
                        systemPrompt,
                        history.map { it.role.wireName }.toTypedArray(),
                        history.map { it.content }.toTypedArray(),
                        history.map { ToolTrace.encode(it.toolTrace) }.toTypedArray(),
                    )
                }.getOrDefault(false)
                if (opened) Log.i(TAG, "ensureChatSession: opened a session with ${history.size} prior turns")
                opened
            }
        }
    }

    private val generateOnceCall by lazy { RemoteGenerateOnce(connection.engineMutex, { connection.service }, ioDispatcher) }

    /**
     * The notes' generation, through the service ([IInferenceService.generateLiteRtOnce]): skipped (null) when anything holds the
     * model, never queued behind it, and never counted as the chat's activity (it is quiet work). See [RemoteGenerateOnce].
     */
    override suspend fun generateOnce(system: String, request: AiRequest): String? = generateOnceCall(system, request)

    private val generateStructuredCall by lazy { RemoteGenerateStructured(connection.engineMutex, { connection.service }, ioDispatcher) }

    /** "Gemma reads the letter", through the service ([IInferenceService.generateLiteRtStructured]); quiet work like [generateOnce]. */
    override suspend fun generateStructured(request: StructuredRequest): String? = generateStructuredCall(request)

    override suspend fun warmUpChat(request: AiRequest) {
        // Another caller holds the model (a document being read, a reply in flight): this is never worth queueing behind, nor
        // worth taking the model over for. The first message prepares the conversation itself, as it always did.
        if (connection.engineMutex.isLocked) return
        connection.engineMutex.withLock {
            warmingUp = true
            try {
                warmUpLocked(request)
            } finally {
                warmingUp = false
            }
        }
    }

    private suspend fun warmUpLocked(request: AiRequest) {
        val remote = withContext(ioDispatcher) { connection.connect() } ?: return
        val finished = CompletableDeferred<Unit>()
        val callback = object : ILiteRtReplyCallback.Stub() {
            override fun onToken(token: String?) {}
            override fun onComplete() {
                finished.complete(Unit)
            }
            override fun onError(message: String?) {
                Log.w(TAG, "warm-up failed: $message")
                finished.complete(Unit)
            }
            override fun onAction(intent: String?, parametersJson: String?, documentId: String?) {}
            override fun onToolExchange(name: String?, argumentsJson: String?, resultJson: String?, shownJson: String?) {}
            override fun onRunJs(requestId: String?, skillFolder: String?, scriptName: String?, data: String?) {}
            override fun onBackendFallback() {}
        }
        // The service does nothing when no LiteRT-LM model is resident or no session is open.
        val started = withContext(ioDispatcher) {
            runCatching {
                remote.warmUpLiteRt(
                    request.maxTokens,
                    request.temperature,
                    request.topK,
                    request.topP,
                    request.tools != null,
                    request.thinkingEnabled,
                    callback,
                )
            }.getOrDefault(false)
        }
        if (!started) return
        try {
            // Bounded: a service that died mid-warm-up never calls back, and the mutex must not stay held for it.
            if (withTimeoutOrNull(WARM_UP_TIMEOUT_MS) { finished.await() } == null) {
                withContext(NonCancellable + ioDispatcher) { runCatching { remote.cancelLiteRtWarmUp() } }
            }
        } catch (e: CancellationException) {
            // The user left the chat, or the app went to the background: the service stops what it can of the warm-up.
            withContext(NonCancellable + ioDispatcher) { runCatching { remote.cancelLiteRtWarmUp() } }
            throw e
        }
    }

    private fun isPrimedLocked(conversationId: String): Boolean =
        runCatching { connection.service?.isLiteRtSessionPrimed(conversationId) == true }.getOrDefault(false)

    override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> =
        connection.engineMutex.serialised(replyFlow(userText, request))

    private fun replyFlow(userText: String, request: AiRequest): Flow<String> = callbackFlow {
        val remote = withContext(ioDispatcher) { connection.connect() } ?: run {
            close(IllegalStateException("The AI engine is not running."))
            return@callbackFlow
        }

        val startedNanos = System.nanoTime()
        synchronized(replyExchanges) { replyExchanges.clear() }
        var chunks = 0
        val callback = object : ILiteRtReplyCallback.Stub() {
            override fun onToken(token: String?) {
                token?.let {
                    chunks++
                    trySend(it)
                }
            }

            override fun onBackendFallback() {
                // The GPU engine failed this reply and the service answered from the CPU: block the GPU for this model, as a
                // crash does, so the next load goes straight to the CPU.
                Log.w(TAG, "the GPU engine failed the reply; answered on the CPU")
                coordinator.lastRequestedSnapshot()?.let { (modelId, config) ->
                    connection.reportCrash(InferenceCrash(modelId, config))
                }
            }

            override fun onAction(intent: String?, parametersJson: String?, documentId: String?) {
                // Only a proposal: it becomes a card in the app, and runs nothing until the user opens it.
                Log.i(TAG, "action received: $intent")
                ToolActionWire.fromWire(intent, parametersJson, documentId)?.let { actions.tryEmit(it) }
            }

            override fun onToolExchange(name: String?, argumentsJson: String?, resultJson: String?, shownJson: String?) {
                if (name == null) return
                synchronized(replyExchanges) {
                    replyExchanges += ToolExchange(name, argumentsJson.orEmpty(), resultJson.orEmpty(), shownJson.orEmpty())
                }
            }

            override fun onRunJs(requestId: String?, skillFolder: String?, scriptName: String?, data: String?) {
                if (requestId == null || skillFolder == null || scriptName == null) return
                Log.i(TAG, "script requested: $skillFolder/$scriptName")
                scripts.tryEmit(JsSkillRequest(requestId, skillFolder, scriptName, data.orEmpty()))
            }

            override fun onComplete() {
                logTiming(startedNanos, chunks)
                close()
            }

            override fun onError(message: String?) {
                Log.w(TAG, "reply failed after $chunks chunks: $message")
                logTiming(startedNanos, chunks)
                close(IllegalStateException(message ?: "Generation failed."))
            }
        }

        val started = withContext(ioDispatcher) {
            runCatching {
                remote.sendLiteRtMessage(
                    userText,
                    request.maxTokens,
                    request.temperature,
                    request.topK,
                    request.topP,
                    request.tools != null,
                    ToolActionWire.documentIdToWire(request.tools?.documentId),
                    request.thinkingEnabled,
                    request.imagePaths.toTypedArray(),
                    callback,
                )
            }.getOrDefault(false)
        }
        if (!started) {
            // The model was replaced since the session was opened (a document was read in between): the next send re-primes.
            close(IllegalStateException("The chat model was unloaded in the meantime. Please send the message again."))
            return@callbackFlow
        }

        // Same death watch as RemoteAiEngine: a binder death mid-reply must end the stream, not hang it.
        val deathWatch = launch {
            state.collect { s -> if (s is ModelLoadState.Failed) close(IllegalStateException(s.error)) }
        }

        awaitClose {
            deathWatch.cancel()
            // Normal completion and a collector that stops early both end here: a stopped stream must not leave the far side
            // generating into the void.
            runCatching { remote.cancelLiteRt() }
        }
    }

    override suspend fun lastReplyToolExchanges(): List<ToolExchange> = synchronized(replyExchanges) { replyExchanges.toList() }

    override suspend fun lastReplyHitLimit(): Boolean = withContext(ioDispatcher) {
        val remote = connection.service ?: return@withContext false
        runCatching { remote.lastLiteRtReplyHitLimit() }.getOrDefault(false)
    }

    override suspend fun commitChatReply(answer: String) = connection.engineMutex.withLock {
        withContext(ioDispatcher) {
            runCatching { connection.service?.commitLiteRtReply(answer) }
            Unit
        }
    }

    override suspend fun discardPendingReply() = connection.engineMutex.withLock {
        withContext(ioDispatcher) {
            runCatching { connection.service?.discardLiteRtReply() }
            Unit
        }
    }

    override suspend fun resetChatSession() = connection.engineMutex.withLock {
        withContext(ioDispatcher) {
            runCatching { connection.service?.resetLiteRtSession() }
            Unit
        }
    }

    override suspend fun unload() = coordinator.unload()

    /** Called by [InferenceMemoryPressureObserver] on app-process memory pressure. */
    internal suspend fun onTrimMemory() = coordinator.unloadOnMemoryPressure()

    private fun logTiming(startedNanos: Long, chunks: Int) {
        val seconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0
        Log.i(TAG, "reply: chunks=$chunks seconds=$seconds")
    }

    private companion object {
        const val TAG = "RemoteLiteRtChatEngine"

        /** Far longer than a prefill of the longest grounding on a slow CPU takes. */
        const val WARM_UP_TIMEOUT_MS = 3 * 60_000L

        /** Calls waiting for the chat to collect them: a reply makes a handful at most. */
        const val ACTION_BUFFER = 16
    }
}
