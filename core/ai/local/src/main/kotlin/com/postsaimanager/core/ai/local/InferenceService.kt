package com.postsaimanager.core.ai.local

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.postsaimanager.core.ai.litert.LiteRtChatEngine
import com.postsaimanager.core.ai.litert.skills.AssetSkillCatalog
import com.postsaimanager.core.domain.ai.ChatToolsRequest
import com.postsaimanager.core.domain.ai.ToolActionWire
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ToolTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts llama.cpp and LiteRT-LM in the `:inference` process.
 *
 * Two runtimes, one resident model: loading a model in one frees the other's, so the phone never holds both (the memory budget
 * is one model's, and reading a letter with llama.cpp after a LiteRT-LM chat simply loads the reader again). llama.cpp does
 * everything; LiteRT-LM (the AI Edge Gallery's engine) only chats. Both run on the one [executor] thread, so a chat reply
 * streaming on LiteRT-LM holds it and background reading waits behind it.
 *
 * Everything native happens on the far side of a process boundary, so a C++ abort — which
 * spike Q3 measured killing the whole app — takes only this process with it. The user
 * loses the answer in flight; their documents, scans and unsaved edits survive.
 *
 * Deliberately **not** Hilt-injected. A `@AndroidEntryPoint` service would drag the Hilt
 * graph, and with it Room and DataStore, into a second process — duplicating the database
 * connection and defeating the isolation. This process owns exactly one thing: the model.
 */
class InferenceService : Service() {

    /**
     * Single-threaded: llama.cpp is not thread-safe per context, and serialising here
     * means the binder threads never touch native state concurrently.
     */
    private val executor = Executors.newSingleThreadExecutor()

    private var handle: Long = 0L
    private val cancelled = AtomicBoolean(false)

    /**
     * The LiteRT-LM chat engine; holds no model until a `.litertlm` file is loaded. Its Agent Skills read the bundled skills
     * through [AssetSkillCatalog], the app's one owner of the skills (assets are readable from any process of the app), so the
     * catalogue is made here, not injected: this process is deliberately not Hilt-injected (see the class KDoc). Lazy because
     * the service has no Context before it is attached.
     */
    private val liteRt by lazy { LiteRtChatEngine(AssetSkillCatalog(applicationContext)) }
    private val liteRtScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var liteRtReply: Job? = null

    /** The warm-up in flight (a conversation being built ahead of the first message), and the count that tells a queued one it was cancelled. */
    @Volatile
    private var liteRtWarmUp: Job? = null
    private val warmUpToken = java.util.concurrent.atomic.AtomicInteger()

    private val binder = object : IInferenceService.Stub() {

        override fun loadModel(modelPath: String?, config: InferenceConfigParcel?): Boolean {
            if (modelPath == null || config == null) return false
            return submit {
                if (!LlamaNative.ensureLoaded()) return@submit false
                if (!File(modelPath).exists()) return@submit false

                // Free before load: the old model's weights are released before the new
                // ones are allocated, never after — never two resident models at once.
                // loadModel() in llama_jni.cpp guards this too (defense in depth), but the
                // Kotlin-side free is what makes it happen even before that JNI call starts.
                freeLiteRt()
                freeHandle()
                handle = LlamaNative.loadModel(
                    modelPath = modelPath,
                    contextTokens = config.contextTokens,
                    batchTokens = config.batchTokens,
                    threads = config.threads,
                    threadsBatch = config.threadsBatch,
                    useMmap = config.useMmap,
                    useMlock = config.useMlock,
                    flashAttention = config.flashAttention,
                    gpuLayers = config.gpuLayers,
                    accelerator = Accelerator.fromLabel(config.accelerator).ordinal,
                )
                handle != 0L
            } ?: false
        }

        override fun isReady(): Boolean = handle != 0L

        override fun recreateContext(config: InferenceConfigParcel?): Boolean {
            if (handle == 0L || config == null) return false
            return submit {
                LlamaNative.recreateContext(
                    handle = handle,
                    contextTokens = config.contextTokens,
                    batchTokens = config.batchTokens,
                    threads = config.threads,
                    threadsBatch = config.threadsBatch,
                    flashAttention = config.flashAttention,
                )
            } ?: false
        }

        override fun hasNativeChatTemplate(): Boolean =
            handle != 0L && (submit { LlamaNative.hasChatTemplate(handle) } ?: false)

        override fun formatChat(
            roles: Array<out String>?,
            contents: Array<out String>?,
            addAssistant: Boolean,
        ): String? {
            if (handle == 0L || roles == null || contents == null) return null
            return submit {
                LlamaNative.formatChat(
                    handle = handle,
                    roles = Array(roles.size) { roles[it] },
                    contents = Array(contents.size) { contents[it] },
                    addAssistant = addAssistant,
                )
            }
        }

        override fun startGeneration(
            prompt: String?,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            presencePenalty: Float,
            seed: Long,
            grammar: String?,
            callback: ITokenCallback?,
        ): Boolean {
            if (handle == 0L || prompt == null || callback == null) return false

            cancelled.set(false)

            // Queued rather than run inline: the binder thread returns immediately and
            // tokens arrive via the oneway callback, so the caller is never blocked.
            executor.execute {
                try {
                    val started = LlamaNative.startGeneration(
                        handle, prompt, maxTokens, temperature, topK, topP, presencePenalty, seed, grammar,
                    )
                    if (!started) {
                        callback.onError("The prompt could not be tokenised.")
                        return@execute
                    }

                    while (!cancelled.get()) {
                        val token = LlamaNative.nextToken(handle) ?: break
                        callback.onToken(token)
                    }
                    LlamaNative.stopGeneration(handle)
                    callback.onComplete()
                } catch (e: RemoteException) {
                    // The client vanished mid-stream. Stop generating for a listener that
                    // no longer exists.
                    Log.w(TAG, "client disconnected during generation", e)
                    runCatching { LlamaNative.stopGeneration(handle) }
                } catch (e: Throwable) {
                    // A native abort cannot be caught here — that is precisely why this
                    // runs in its own process. This handles the survivable failures.
                    Log.e(TAG, "generation failed", e)
                    runCatching { callback.onError(e.message ?: "Generation failed.") }
                }
            }
            return true
        }

        override fun cancelGeneration() {
            cancelled.set(true)
            // A blocking prompt-session answer has no token loop to observe the flag; the native side has its own.
            if (handle != 0L) runCatching { LlamaNative.promptCancel() }
        }

        override fun lastReplyHitLimit(): Boolean {
            if (handle == 0L) return false
            return submit { LlamaNative.lastReplyHitLimit(handle) } ?: false
        }

        override fun openChatSession(systemPrompt: String?): Boolean {
            if (handle == 0L) return false
            return submit { LlamaNative.openChatSession(handle, systemPrompt.orEmpty()) } ?: false
        }

        override fun primeChatSession(roles: Array<out String>?, contents: Array<out String>?): Boolean {
            if (handle == 0L || roles == null || contents == null) return false
            return submit {
                LlamaNative.primeChatSession(
                    handle,
                    Array(roles.size) { roles[it] },
                    Array(contents.size) { contents[it] },
                )
            } ?: false
        }

        override fun sendChatMessage(
            userText: String?,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            presencePenalty: Float,
            seed: Long,
            grammar: String?,
            noThink: Boolean,
            thinkingBudgetTokens: Int,
            callback: ITokenCallback?,
        ): Boolean {
            if (handle == 0L || userText == null || callback == null) return false

            cancelled.set(false)

            // Same pull-based streaming shape as startGeneration — see its doc.
            executor.execute {
                try {
                    val started = LlamaNative.sendChatMessage(
                        handle, userText, maxTokens, temperature, topK, topP, presencePenalty, seed, grammar, noThink,
                        thinkingBudgetTokens,
                    )
                    if (!started) {
                        callback.onError("The chat turn could not be started.")
                        return@execute
                    }

                    while (!cancelled.get()) {
                        val token = LlamaNative.nextToken(handle) ?: break
                        callback.onToken(token)
                    }
                    LlamaNative.stopGeneration(handle)
                    callback.onComplete()
                } catch (e: RemoteException) {
                    Log.w(TAG, "client disconnected during generation", e)
                    runCatching { LlamaNative.stopGeneration(handle) }
                } catch (e: Throwable) {
                    Log.e(TAG, "chat generation failed", e)
                    runCatching { callback.onError(e.message ?: "Generation failed.") }
                }
            }
            return true
        }

        override fun commitChatReply(answer: String?) {
            if (handle == 0L || answer == null) return
            submit { LlamaNative.commitChatReply(handle, answer) }
        }

        override fun discardPendingReply() {
            if (handle == 0L) return
            submit { LlamaNative.discardPendingReply(handle) }
        }

        override fun resetChatSession() {
            if (handle == 0L) return
            submit { LlamaNative.resetChatSession(handle) }
        }

        override fun promptOpen(prefix: String?): Int {
            if (handle == 0L || prefix == null) return -1
            return submit { LlamaNative.promptOpen(handle, prefix) } ?: -1
        }

        override fun promptAsk(question: String?, grammar: String?, maxTokens: Int): String? {
            if (handle == 0L || question == null) return null
            return submit { LlamaNative.promptAsk(handle, question, grammar, maxTokens) }
        }

        override fun promptScore(shared: String?, continuations: Array<String>?, yes: String?, no: String?): DoubleArray? {
            if (handle == 0L || shared == null || continuations == null || yes == null || no == null) return null
            return submit { LlamaNative.promptScore(handle, shared, continuations, yes, no) }
        }

        override fun promptScoreGrid(shared: String?, heads: Array<String>?, asks: Array<String>?, yes: String?, no: String?): DoubleArray? {
            if (handle == 0L || shared == null || heads == null || asks == null || yes == null || no == null) return null
            return submit { LlamaNative.promptScoreGrid(handle, shared, heads, asks, yes, no) }
        }

        override fun promptClose() {
            if (handle == 0L) return
            submit { LlamaNative.promptClose(handle) }
        }

        override fun countTokens(text: String?): Int {
            if (handle == 0L || text == null) return -1
            return submit { LlamaNative.countTokens(handle, text) } ?: -1
        }

        override fun unloadModel() {
            submit { freeHandle() }
        }

        override fun availableAccelerators(): IntArray {
            if (!LlamaNative.ensureLoaded()) return intArrayOf(0) // CPU only
            val accelerators = submit { LlamaNative.availableAccelerators() } ?: intArrayOf(0)
            val description = submit { LlamaNative.backendDescription() } ?: "<unavailable>"
            Log.i(
                TAG,
                "availableAccelerators() -> ${accelerators.toList()} ; backendDescription() -> $description",
            )
            return accelerators
        }

        override fun lastLoadDevices(): String =
            submit { LlamaNative.lastLoadDevices() } ?: "none"

        // ── LiteRT-LM ────────────────────────────────────────────────────────────────────────────────

        override fun loadLiteRtModel(modelPath: String?, config: InferenceConfigParcel?): String? {
            if (modelPath == null || config == null) return null
            return submit {
                if (!File(modelPath).exists()) return@submit null
                // One resident model: the llama.cpp one goes before the LiteRT-LM engine starts.
                freeHandle()
                val result = runBlocking { liteRt.load(modelPath, config.toInferenceConfig()) }
                if (result is com.postsaimanager.core.common.result.PamResult.Error) {
                    Log.e(TAG, "LiteRT-LM load failed: ${result.error.userMessage}")
                    null
                } else {
                    (liteRt.accelerator ?: Accelerator.CPU).name
                }
            }
        }

        override fun isLiteRtReady(): Boolean = liteRt.state.value is ModelLoadState.Ready

        override fun openLiteRtSession(
            conversationId: String?,
            systemPrompt: String?,
            roles: Array<out String>?,
            contents: Array<out String>?,
            toolTraces: Array<out String>?,
        ): Boolean {
            if (conversationId == null || roles == null || contents == null || !isLiteRtReady()) return false
            val history = roles.indices.map {
                AiChatMessage(roleOf(roles[it]), contents[it], ToolTrace.decode(toolTraces?.getOrNull(it)))
            }
            return submit {
                runBlocking {
                    liteRt.ensureChatSession(conversationId, systemPrompt.orEmpty(), history)
                    liteRt.isChatSessionPrimed(conversationId)
                }
            } ?: false
        }

        override fun isLiteRtSessionPrimed(conversationId: String?): Boolean {
            if (conversationId == null) return false
            return runBlocking { liteRt.isChatSessionPrimed(conversationId) }
        }

        override fun warmUpLiteRt(
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            toolsEnabled: Boolean,
            thinking: Boolean,
            callback: ILiteRtReplyCallback?,
        ): Boolean {
            if (callback == null || !isLiteRtReady()) return false
            val request = AiRequest(
                prompt = "",
                maxTokens = maxTokens,
                temperature = temperature,
                topK = topK,
                topP = topP,
                thinkingEnabled = thinking,
                tools = if (toolsEnabled) ChatToolsRequest(documentId = null) else null,
            )
            val token = warmUpToken.incrementAndGet()
            // Queued on the inference thread like a reply: a message sent meanwhile starts when the conversation is built.
            executor.execute {
                // Cancelled while it waited its turn (the user left the chat): nothing to build.
                if (warmUpToken.get() != token) {
                    runCatching { callback.onComplete() }
                    return@execute
                }
                val job = liteRtScope.launch {
                    try {
                        liteRt.warmUpChat(request)
                        callback.onComplete()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RemoteException) {
                        Log.w(TAG, "client disconnected during a LiteRT-LM warm-up", e)
                    } catch (e: Throwable) {
                        Log.w(TAG, "LiteRT-LM warm-up failed", e)
                        runCatching { callback.onError(e.message ?: "Warm-up failed.") }
                    }
                }
                liteRtWarmUp = job
                runBlocking { job.join() }
            }
            return true
        }

        override fun cancelLiteRtWarmUp() {
            warmUpToken.incrementAndGet()
            liteRtWarmUp?.cancel()
        }

        override fun sendLiteRtMessage(
            userText: String?,
            maxTokens: Int,
            temperature: Float,
            topK: Int,
            topP: Float,
            toolsEnabled: Boolean,
            toolsDocumentId: String?,
            thinking: Boolean,
            imagePaths: Array<out String>?,
            callback: ILiteRtReplyCallback?,
        ): Boolean {
            if (userText == null || callback == null || !isLiteRtReady()) return false
            val request = AiRequest(
                prompt = "",
                maxTokens = maxTokens,
                temperature = temperature,
                topK = topK,
                topP = topP,
                thinkingEnabled = thinking,
                tools = if (toolsEnabled) ChatToolsRequest(documentId = toolsDocumentId?.takeIf { it.isNotBlank() }) else null,
                imagePaths = imagePaths?.toList().orEmpty(),
            )
            // Queued behind whatever the thread is doing, then holds the thread until the reply is over: tokens arrive through the
            // oneway callback, and a llama.cpp call (background reading) queued behind this reply waits for it.
            executor.execute {
                val reply = liteRtScope.launch {
                    try {
                        // A proposed action goes to the app process as the model wrote it; it is never run in this process.
                        liteRt.sendChatMessage(
                            userText,
                            request,
                            onToolAction = { call ->
                                runCatching {
                                    callback.onAction(call.intent, call.parametersJson, ToolActionWire.documentIdToWire(call.documentId))
                                }
                            },
                            onToolExchange = { exchange ->
                                runCatching {
                                    callback.onToolExchange(exchange.name, exchange.argumentsJson, exchange.resultJson, exchange.shownJson)
                                }
                            },
                            // A script runs in the app process, in its offline WebView; the answer comes back by deliverJsResult.
                            onRunJs = { js ->
                                runCatching { callback.onRunJs(js.id, js.skillFolder, js.scriptName, js.data) }
                            },
                            onFallback = { runCatching { callback.onBackendFallback() } },
                        ).collect { callback.onToken(it) }
                        callback.onComplete()
                    } catch (e: CancellationException) {
                        // Stopped by cancelLiteRt: the client that asked for it is not waiting for a completion.
                        throw e
                    } catch (e: RemoteException) {
                        Log.w(TAG, "client disconnected during a LiteRT-LM reply", e)
                    } catch (e: Throwable) {
                        Log.e(TAG, "LiteRT-LM reply failed", e)
                        runCatching { callback.onError(e.message ?: "Generation failed.") }
                    }
                }
                liteRtReply = reply
                runBlocking { reply.join() }
            }
            return true
        }

        override fun deliverJsResult(requestId: String?, result: String?) {
            if (requestId == null) return
            // Not on the inference thread: it is busy streaming the reply that waits for this answer.
            runBlocking { liteRt.deliverJsResult(requestId, result.orEmpty()) }
        }

        override fun cancelLiteRt() {
            liteRtReply?.cancel()
        }

        override fun lastLiteRtReplyHitLimit(): Boolean = runBlocking { liteRt.lastReplyHitLimit() }

        override fun commitLiteRtReply(answer: String?) {
            if (answer == null) return
            submit { runBlocking { liteRt.commitChatReply(answer) } }
        }

        override fun discardLiteRtReply() {
            submit { runBlocking { liteRt.discardPendingReply() } }
        }

        override fun resetLiteRtSession() {
            submit { runBlocking { liteRt.resetChatSession() } }
        }

        override fun generateLiteRtOnce(system: String?, prompt: String?, maxTokens: Int, temperature: Float, topK: Int): String? {
            if (system == null || prompt == null || !isLiteRtReady()) return null
            // Quiet work is skipped, never queued behind a reply or a warm-up (the engine's own tryLock covers the rest).
            if (liteRtReply?.isActive == true || liteRtWarmUp?.isActive == true) return null
            val request = AiRequest(prompt = prompt, maxTokens = maxTokens, temperature = temperature, topK = topK, thinkingEnabled = false)
            return submit { runBlocking { liteRt.generateOnce(system, request) } }
        }

        override fun unloadLiteRt() {
            submit { freeLiteRt() }
        }
    }

    private fun roleOf(wireName: String): AiChatRole =
        AiChatRole.entries.firstOrNull { it.wireName == wireName } ?: AiChatRole.USER

    /** Frees the LiteRT-LM model, if one is resident. Runs on the inference thread. */
    private fun freeLiteRt() {
        runBlocking { liteRt.unload() }
    }

    /** Runs [block] on the inference thread and waits — binder calls are already off-main. */
    private fun <T> submit(block: () -> T): T? = try {
        executor.submit(block).get()
    } catch (e: Exception) {
        Log.e(TAG, "inference task failed", e)
        null
    }

    private fun freeHandle() {
        if (handle != 0L) {
            LlamaNative.freeModel(handle)
            handle = 0L
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Frees the model under real memory pressure.
     *
     * No separate "not loaded" flag is needed for [IInferenceService.isReady] to reflect
     * this — it already reads [handle] directly, so [RemoteAiEngine] sees `isReady() ==
     * false` on its very next call and reloads lazily through [ModelLoadCoordinator].
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            Log.w(TAG, "onTrimMemory($level) — freeing the resident model")
            submit {
                freeHandle()
                freeLiteRt()
            }
        }
    }

    override fun onDestroy() {
        liteRtScope.cancel()
        submit {
            freeHandle()
            freeLiteRt()
        }
        executor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "InferenceService"
    }
}
