package com.postsaimanager.core.ai.local

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import android.util.Log
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ReloadScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Drives [ModelLoadState] for one [AiEngine][com.postsaimanager.core.domain.ai.AiEngine].
 *
 * Three things this exists to get right, none of which is safe to leave to ad-hoc `if`
 * statements scattered through an engine:
 *
 * **Single-flight.** [load] is entered under [mutex], so a second caller arriving while a
 * load is in flight *waits* for it rather than racing a second native call. When it wakes it
 * re-reads the (now current) state rather than blindly reloading — which is what makes the
 * third point below actually save anything.
 *
 * **[ReloadScope] dispatch.** Asking to "load" a model that is already resident, with a
 * config that only changes sampling, does nothing native at all — [ReloadScope.NONE] means
 * exactly that. A context-only change ([ReloadScope.CONTEXT]) recreates the `llama_context`
 * and keeps the resident model. Anything else ([ReloadScope.MODEL], or a different model
 * entirely) does a full load.
 *
 * **Stale-generation guard.** Every state-changing operation this coordinator issues —
 * load, context recreation, unload — increments [generation]. Callers that observe a load
 * asynchronously (a binder death, a memory-pressure callback) capture the generation at the
 * moment they noticed something, via [currentGeneration], and pass it back to
 * [reportExternalFailure] — which is a no-op if a newer generation has since started. Without
 * this, a slow callback for an old load could stomp on a state a newer load already produced.
 */
internal class ModelLoadCoordinator(private val ops: ModelLoadOps) {

    private val mutex = Mutex()
    private val generationCounter = AtomicLong(0)

    private val _state = MutableStateFlow<ModelLoadState>(ModelLoadState.Idle)
    val state: StateFlow<ModelLoadState> = _state.asStateFlow()

    /** Capabilities behind the current [ModelLoadState.Ready], if any — see the [NONE][ReloadScope.NONE] path in [load]. */
    private var lastCapabilities: AiCapabilities? = null

    /**
     * The model+config most recently requested via [load], regardless of whether that
     * request succeeded, is still in flight, or was later invalidated by an external
     * failure (a binder death, a native crash callback). This is what [ensureLoaded] replays
     * to transparently recover a [ModelLoadState.Failed] state on the caller's next request —
     * the documented contract is that a crash is reported once and then silently recovered
     * from. Cleared only by an explicit [unload]; an external failure or a memory-pressure
     * unload both leave it set, since both are cases where the caller should resume with the
     * same model on the next request.
     */
    @Volatile
    private var lastRequested: Pair<String, InferenceConfig>? = null

    /** The generation of the most recent state-changing operation issued. */
    fun currentGeneration(): Long = generationCounter.get()

    /**
     * The model+config [lastRequested] — what a crash observed right now was almost
     * certainly running with. Used by callers (today, [RemoteAiEngine]'s death recipient)
     * that need to report *what* crashed, not just *that* something did.
     */
    fun lastRequestedSnapshot(): Pair<String, InferenceConfig>? = lastRequested

    /** [ModelLoadState.Ready.modelId] or [ModelLoadState.Loading.modelId] of the current state, if any. */
    fun currentModelId(): String? = when (val s = _state.value) {
        is ModelLoadState.Ready -> s.modelId
        is ModelLoadState.Loading -> s.modelId
        is ModelLoadState.Failed -> s.modelId
        ModelLoadState.Idle -> null
    }

    /**
     * Loads [modelId] with [config], reusing whatever of the current state it can.
     *
     * Single-flight via [mutex]: a caller that arrives while another load is in progress
     * waits here rather than issuing a second native call, and — since it re-checks the
     * (now-current) state after acquiring the lock — may find there is nothing left to do.
     */
    suspend fun load(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> {
        lastRequested = modelId to config
        return loadLocked(modelId, config)
    }

    private suspend fun loadLocked(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> = mutex.withLock {
        val current = _state.value
        val sameModelReady = current is ModelLoadState.Ready && current.modelId == modelId

        // A silent out-of-band unload (the :inference process freeing the model under
        // memory pressure) can leave this recorded as Ready when nothing is actually
        // resident — never trust the fast paths below without checking.
        if (sameModelReady && ops.isActuallyLoaded()) {
            val readyState = current as ModelLoadState.Ready
            val scope = readyState.config.requiresReload(config)
            logScope(scope, readyState.config, config)
            return when (scope) {
                ReloadScope.NONE -> noReloadNeeded(readyState, config)
                ReloadScope.CONTEXT -> recreateContextLocked(modelId, config)
                ReloadScope.MODEL -> fullLoadLocked(modelId, config)
            }
        }
        logScope(ReloadScope.MODEL, current = null, requested = config)
        return fullLoadLocked(modelId, config)
    }

    /**
     * One line per [load] call, so "why did it reload" is diagnosable from `adb logcat`
     * without reaching for a debugger — see the on-device repro in
     * `SendChatMessageUseCase`'s KDoc, where this call happens on every send by design and a
     * scope other than [ReloadScope.NONE] on an unchanged config is exactly the bug this
     * pins down. [Log.i] rather than [Log.d]: cheap, and worth having in a release-debuggable
     * build without a debug flag.
     */
    private fun logScope(scope: ReloadScope, current: InferenceConfig?, requested: InferenceConfig) {
        val reason = if (current == null) {
            "no resident model"
        } else {
            diff(current, requested)
        }
        runCatching { Log.i(TAG, "load requested scope=$scope reason=$reason") }
    }

    /** Which top-level fields differ between [a] and [b] — the "why" behind a [ReloadScope]. */
    private fun diff(a: InferenceConfig, b: InferenceConfig): String {
        val fields = buildList {
            if (a.contextTokens != b.contextTokens) add("contextTokens ${a.contextTokens}->${b.contextTokens}")
            if (a.batchTokens != b.batchTokens) add("batchTokens ${a.batchTokens}->${b.batchTokens}")
            if (a.threads != b.threads) add("threads ${a.threads}->${b.threads}")
            if (a.threadsBatch != b.threadsBatch) add("threadsBatch ${a.threadsBatch}->${b.threadsBatch}")
            if (a.useMmap != b.useMmap) add("useMmap ${a.useMmap}->${b.useMmap}")
            if (a.useMlock != b.useMlock) add("useMlock ${a.useMlock}->${b.useMlock}")
            if (a.flashAttention != b.flashAttention) add("flashAttention ${a.flashAttention}->${b.flashAttention}")
            if (a.accelerator != b.accelerator) add("accelerator ${a.accelerator}->${b.accelerator}")
            if (a.gpuLayers != b.gpuLayers) add("gpuLayers ${a.gpuLayers}->${b.gpuLayers}")
            if (a.sampling != b.sampling) add("sampling ${a.sampling}->${b.sampling}")
        }
        return if (fields.isEmpty()) "no field differs" else fields.joinToString(", ")
    }

    /** Caller must hold [mutex]. Nothing native changes — sampling is per-request anyway. */
    private fun noReloadNeeded(current: ModelLoadState.Ready, config: InferenceConfig): PamResult<AiCapabilities> {
        val caps = lastCapabilities
        _state.value = current.copy(config = config)
        return if (caps != null) {
            PamResult.Success(caps)
        } else {
            // Should not happen — Ready implies a prior success recorded capabilities —
            // but a coordinator bug here must not crash the caller.
            PamResult.Error(PamError.ModelNotLoaded("Model state is inconsistent; reloading."))
        }
    }

    /**
     * Caller must hold [mutex].
     *
     * A model may be resident on exactly one accelerator at a time, never two — so whatever
     * might already be resident (a different model, or the same model on a different
     * accelerator/mmap/mlock config, both [ReloadScope.MODEL]) is released *before* the new
     * one is requested. Skipped only from [ModelLoadState.Idle], where there is genuinely
     * nothing to release; from [ModelLoadState.Ready] or [ModelLoadState.Failed] the unload
     * runs unconditionally rather than trusting [ModelLoadState] to mean something really is
     * loaded — same reasoning as the out-of-band-unload guard in [loadLocked], and a no-op on
     * [ModelLoadOps.unloadModel] costs nothing when there is nothing to free.
     */
    private suspend fun fullLoadLocked(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> {
        val myGeneration = generationCounter.incrementAndGet()
        val startedAt = System.nanoTime()

        if (_state.value != ModelLoadState.Idle) {
            ops.unloadModel()
        }
        _state.value = ModelLoadState.Loading(modelId, startedAt)

        val result = ops.loadModel(modelId, config)
        applyResult(myGeneration, modelId, config, startedAt, result)
        return result
    }

    /** Caller must hold [mutex]. */
    private suspend fun recreateContextLocked(modelId: String, config: InferenceConfig): PamResult<AiCapabilities> {
        val myGeneration = generationCounter.incrementAndGet()
        val startedAt = System.nanoTime()
        _state.value = ModelLoadState.Loading(modelId, startedAt)

        val result = ops.recreateContext(modelId, config)
        applyResult(myGeneration, modelId, config, startedAt, result)
        return result
    }

    private fun applyResult(
        myGeneration: Long,
        modelId: String,
        config: InferenceConfig,
        startedAtNanos: Long,
        result: PamResult<AiCapabilities>,
    ) {
        // A newer load/unload has already superseded this one (should not happen while we
        // hold the mutex for the whole operation, but the check costs nothing and keeps the
        // invariant explicit rather than implied by lock scoping alone).
        if (myGeneration != generationCounter.get()) return

        when (result) {
            is PamResult.Success -> {
                lastCapabilities = result.data
                val durationMs = (System.nanoTime() - startedAtNanos) / 1_000_000
                _state.value = ModelLoadState.Ready(modelId, config, durationMs)
            }
            is PamResult.Error -> {
                lastCapabilities = null
                _state.value = ModelLoadState.Failed(modelId, result.error.userMessage)
            }
        }
    }

    suspend fun unload() {
        mutex.withLock {
            generationCounter.incrementAndGet()
            ops.unloadModel()
            lastCapabilities = null
            _state.value = ModelLoadState.Idle
        }
        // An explicit unload is the one case where there is nothing to resume — the caller
        // asked for the model to go away, so the next generate() must fail loudly rather
        // than silently reloading it.
        lastRequested = null
    }

    /**
     * Memory-pressure unload. Skips rather than blocks when a load is mid-flight — the
     * point of the generation guard is that a stale callback should back off, not stall the
     * caller (a `ComponentCallbacks2` dispatch) waiting for an unrelated load to finish.
     */
    suspend fun unloadOnMemoryPressure() {
        if (!mutex.tryLock()) return
        try {
            if (_state.value is ModelLoadState.Ready) {
                generationCounter.incrementAndGet()
                ops.unloadModel()
                lastCapabilities = null
                _state.value = ModelLoadState.Idle
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Reports a failure observed outside the normal load/unload path — a binder death, a
     * native crash callback. Applied only if [issuedGeneration] (captured by the caller via
     * [currentGeneration] at the moment it noticed the failure) is still the current
     * generation; otherwise a newer load has already moved on and this is a stale signal.
     */
    fun reportExternalFailure(issuedGeneration: Long, modelId: String?, message: String) {
        if (issuedGeneration != generationCounter.get()) return
        lastCapabilities = null
        _state.value = ModelLoadState.Failed(modelId, message)
    }

    /**
     * Ensures a model is resident, replaying [lastRequested] through [load] when needed.
     *
     * Deliberately does not special-case [ModelLoadState.Ready]: [load] itself checks
     * [ModelLoadOps.isActuallyLoaded] before taking a fast path, which is exactly what
     * covers the "state says Ready but the process crashed or froze it under memory
     * pressure" case too. So this always replays the last request when one exists — the
     * mutex and [ReloadScope] dispatch in [load] make that free when nothing has actually
     * changed.
     *
     * @return the result of the replayed [load], or `null` if nothing has ever been
     *   requested (or the last request was cleared by an explicit [unload]) — the caller's
     *   cue that there is genuinely no model to fall back on.
     */
    suspend fun ensureLoaded(): PamResult<AiCapabilities>? {
        val (modelId, config) = lastRequested ?: return null
        return load(modelId, config)
    }

    private companion object {
        const val TAG = "ModelLoadCoordinator"
    }
}
