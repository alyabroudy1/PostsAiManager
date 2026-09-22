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
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.InferenceCrash
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
    @ApplicationContext private val context: Context,
) : AiEngine {

    @Volatile
    private var service: IInferenceService? = null

    private val ops = object : ModelLoadOps {
        override suspend fun loadModel(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> {
            val remote = connect() ?: return PamResult.Error(
                PamError.ModelNotLoaded("Could not start the AI engine."),
            )
            if (!File(modelId).exists()) {
                return PamResult.Error(PamError.FileNotFound(modelId))
            }
            val ok = runCatching {
                remote.loadModel(modelId, InferenceConfigParcel.from(config))
            }.getOrDefault(false)
            if (!ok) {
                return PamResult.Error(
                    PamError.ModelNotLoaded(
                        "Could not load ${File(modelId).name}. The file may be corrupt or use " +
                            "an unsupported model architecture.",
                    ),
                )
            }
            return PamResult.Success(capabilitiesOf(remote, modelId, config))
        }

        override suspend fun recreateContext(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> {
            val remote = connect() ?: return PamResult.Error(
                PamError.ModelNotLoaded("Could not reach the AI engine."),
            )
            val ok = runCatching { remote.recreateContext(InferenceConfigParcel.from(config)) }
                .getOrDefault(false)
            if (!ok) {
                return PamResult.Error(PamError.ModelNotLoaded("Could not apply the new settings."))
            }
            return PamResult.Success(capabilitiesOf(remote, modelId, config))
        }

        override suspend fun unloadModel() {
            runCatching { service?.unloadModel() }
        }

        override suspend fun isActuallyLoaded(): Boolean =
            runCatching { service?.isReady == true }.getOrDefault(false)
    }

    private val coordinator = ModelLoadCoordinator(ops)

    override val state: StateFlow<ModelLoadState> = coordinator.state

    override val isReady: Boolean
        get() = runCatching { service?.isReady == true }.getOrDefault(false)

    private val _crashEvents = MutableSharedFlow<InferenceCrash>(extraBufferCapacity = 4)
    override val crashEvents: SharedFlow<InferenceCrash> = _crashEvents.asSharedFlow()

    private val deathRecipient = IBinder.DeathRecipient {
        // The whole point of the boundary: observe the crash instead of dying with it.
        Log.e(TAG, "inference process died")
        service = null
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
        snapshot?.let { (modelId, config) -> _crashEvents.tryEmit(InferenceCrash(modelId, config)) }
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

    override fun generate(request: AiRequest): Flow<String> = callbackFlow {
        val remote = connect() ?: run {
            close(IllegalStateException("The AI engine is not running."))
            return@callbackFlow
        }

        // A previous crash (binder death moves the coordinator to Failed), or a
        // memory-pressure unload in the :inference process, can leave nothing actually
        // resident. Reload through the coordinator rather than issuing a bare loadModel
        // call, so the state machine (and the generation counter) stay accurate — and so
        // this recovers from Failed, not just from a stale Ready.
        if (!remote.isReady) {
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

        val started = runCatching {
            remote.startGeneration(
                request.prompt,
                request.maxTokens,
                request.temperature,
                request.topK,
                request.topP,
                request.seed ?: -1L,
                request.grammar,
                callback,
            )
        }.getOrDefault(false)

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
    override suspend fun availableAccelerators(): Set<Accelerator> {
        val remote = connect() ?: return setOf(Accelerator.CPU)
        val ordinals = runCatching { remote.availableAccelerators() }.getOrNull()
            ?: return setOf(Accelerator.CPU)
        val accelerators = ordinals.toList().mapNotNull { ordinal ->
            Accelerator.entries.getOrNull(ordinal)
        }.toSet()
        return accelerators.ifEmpty { setOf(Accelerator.CPU) }
    }

    /**
     * Diagnostic, not part of [AiEngine]: which devices the most recent successful `loadModel`
     * actually used in the `:inference` process — `"CPU"`, `"all"`, or `"none"`. `LlamaNative`
     * only ever loads inside that process, so this has to cross the same AIDL boundary as
     * everything else here rather than being called directly. Used by
     * `GpuSmokeTest.cpuAcceleratorNeverTouchesTheGpuDeviceOnAVulkanBuild`.
     */
    suspend fun lastLoadDevices(): String? {
        val remote = connect() ?: return null
        return runCatching { remote.lastLoadDevices() }.getOrNull()
    }

    /** See the `genStartNanos`/`tokenCount` doc comment in [generate]. */
    private fun logGenerationTiming(genStartNanos: Long, tokenCount: Int) {
        val seconds = (System.nanoTime() - genStartNanos) / 1_000_000_000.0
        val tokensPerSecond = if (seconds > 0) tokenCount / seconds else 0.0
        Log.i(TAG, "generate: tokens=$tokenCount seconds=$seconds tokensPerSecond=$tokensPerSecond")
    }

    private companion object {
        const val TAG = "RemoteAiEngine"
    }
}
