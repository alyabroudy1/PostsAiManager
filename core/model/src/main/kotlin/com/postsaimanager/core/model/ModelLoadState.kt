package com.postsaimanager.core.model

/**
 * Where a model is in its load lifecycle.
 *
 * Lives in `:core:model` — inert, no Android imports — so it can be the payload of
 * `AiEngine.state` (`:core:domain`) without either module reaching for the other's
 * dependencies. Richer than a plain "is it ready" boolean on purpose: [Ready] carries the
 * [com.postsaimanager.core.model.InferenceConfig] it was loaded with, which is exactly what
 * a caller needs to decide whether a *new* load request is a no-op, a context recreation, or
 * a full reload — see `InferenceConfig.requiresReload`.
 */
sealed interface ModelLoadState {

    /** No model installed or selected. The document app remains fully usable. */
    data object Idle : ModelLoadState

    /**
     * A load (or reload) is in flight for [modelId].
     *
     * @param startedAtNanos [System.nanoTime] when the load began, so [Ready.loadDurationMs]
     *   can be computed without a wall-clock dependency that a settings change could skew.
     */
    data class Loading(val modelId: String, val startedAtNanos: Long) : ModelLoadState

    /**
     * [modelId] is resident and was last loaded (or reconfigured) with [config].
     *
     * @param loadDurationMs how long the operation that produced this state took — a full
     *   load, or a context recreation, whichever last ran.
     */
    data class Ready(
        val modelId: String,
        val config: InferenceConfig,
        val loadDurationMs: Long,
    ) : ModelLoadState

    /**
     * [modelId] failed to load, or a previously loaded model stopped unexpectedly (a crashed
     * `:inference` process, for instance). [modelId] is null when the failure happened before
     * a model was even identified — a missing [com.postsaimanager.core.domain.ai.ActiveModelProvider]
     * path, for instance.
     */
    data class Failed(val modelId: String?, val error: String) : ModelLoadState
}
