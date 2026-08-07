package com.postsaimanager.core.ai.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiEngineState
import com.postsaimanager.core.domain.ai.AiRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * [AiEngine] that runs inference in the `:inference` process.
 *
 * This is the implementation the app binds. `LocalAiEngine` remains the in-process engine
 * and is still what the service ultimately drives through the JNI layer — but nothing on
 * the UI side touches native code directly any more.
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
 */
@Singleton
class RemoteAiEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : AiEngine {

    private val mutex = Mutex()

    @Volatile
    private var service: IInferenceService? = null

    private val _state = MutableStateFlow<AiEngineState>(AiEngineState.NoModel)
    override val state: StateFlow<AiEngineState> = _state.asStateFlow()

    override val isReady: Boolean
        get() = runCatching { service?.isReady == true }.getOrDefault(false)

    /** Remembered so a crashed process can be reloaded transparently. */
    @Volatile
    private var loadedModelPath: String? = null

    @Volatile
    private var loadedContextTokens: Int = AiEngine.DEFAULT_CONTEXT_TOKENS

    private val deathRecipient = IBinder.DeathRecipient {
        // The whole point of the boundary: observe the crash instead of dying with it.
        Log.e(TAG, "inference process died")
        service = null
        _state.value = AiEngineState.Failed(
            "The AI engine stopped unexpectedly. Your documents are unaffected — " +
                "send the message again to restart it.",
        )
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IInferenceService.Stub.asInterface(binder)
            runCatching { binder?.linkToDeath(deathRecipient, 0) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private suspend fun connect(): IInferenceService? {
        service?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val once = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    connection.onServiceConnected(name, binder)
                    if (continuation.isActive) continuation.resume(service)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    connection.onServiceDisconnected(name)
                }
            }
            val intent = Intent(context, InferenceService::class.java)
            val bound = context.bindService(intent, once, Context.BIND_AUTO_CREATE)
            if (!bound && continuation.isActive) continuation.resume(null)
        }
    }

    override suspend fun load(
        modelPath: String,
        contextTokens: Int,
    ): PamResult<AiCapabilities> = mutex.withLock {
        val remote = connect() ?: return PamResult.Error(
            PamError.ModelNotLoaded("Could not start the AI engine."),
        )

        if (!File(modelPath).exists()) {
            val error = PamError.FileNotFound(modelPath)
            _state.value = AiEngineState.Failed(error.userMessage)
            return PamResult.Error(error)
        }

        _state.value = AiEngineState.Loading

        val ok = runCatching {
            remote.loadModel(modelPath, contextTokens, LocalAiEngine.defaultThreadCount())
        }.getOrDefault(false)

        if (!ok) {
            val error = PamError.ModelNotLoaded(
                "Could not load ${File(modelPath).name}. The file may be corrupt or use an " +
                    "unsupported model architecture.",
            )
            _state.value = AiEngineState.Failed(error.userMessage)
            return PamResult.Error(error)
        }

        loadedModelPath = modelPath
        loadedContextTokens = contextTokens

        val capabilities = AiCapabilities(
            supportsGrammar = true,
            contextTokens = contextTokens,
            modelName = File(modelPath).nameWithoutExtension,
            hasNativeChatTemplate = runCatching { remote.hasNativeChatTemplate() }
                .getOrDefault(false),
        )
        _state.value = AiEngineState.Ready(capabilities)
        PamResult.Success(capabilities)
    }

    override fun generate(request: AiRequest): Flow<String> = callbackFlow {
        val remote = connect() ?: run {
            close(IllegalStateException("The AI engine is not running."))
            return@callbackFlow
        }

        // A previous crash leaves the new process with no model; reload rather than
        // failing a request the user has every reason to expect to work.
        if (!remote.isReady) {
            val path = loadedModelPath
            if (path == null) {
                close(IllegalStateException("No model is loaded"))
                return@callbackFlow
            }
            if (!remote.loadModel(path, loadedContextTokens, LocalAiEngine.defaultThreadCount())) {
                close(IllegalStateException("The AI engine could not reload the model."))
                return@callbackFlow
            }
        }

        val callback = object : ITokenCallback.Stub() {
            override fun onToken(token: String?) {
                token?.let { trySend(it) }
            }

            override fun onComplete() {
                close()
            }

            override fun onError(message: String?) {
                close(IllegalStateException(message ?: "Generation failed."))
            }
        }

        val started = runCatching {
            remote.startGeneration(
                request.prompt,
                request.maxTokens,
                request.temperature,
                request.grammar,
                callback,
            )
        }.getOrDefault(false)

        if (!started) {
            close(IllegalArgumentException("Prompt produced no tokens"))
            return@callbackFlow
        }

        awaitClose {
            // Covers both normal completion and collector cancellation, so a stopped
            // stream does not leave the far side generating into the void.
            runCatching { remote.cancelGeneration() }
        }
    }

    override fun formatPrompt(messages: List<AiChatMessage>): String {
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
        runCatching { service?.unloadModel() }
        loadedModelPath = null
        _state.value = AiEngineState.NoModel
    }

    private companion object {
        const val TAG = "RemoteAiEngine"
    }
}
