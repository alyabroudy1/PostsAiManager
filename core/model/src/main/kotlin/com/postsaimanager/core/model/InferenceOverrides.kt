package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * What the user has chosen to change from [InferenceConfig.defaults], persisted.
 *
 * Every field is nullable and null means "no opinion — use the default". This is why the
 * type is not just an [InferenceConfig]: a persisted default would freeze whatever the
 * heuristic produced on the day the user first opened Settings, rather than continuing to
 * track [InferenceConfig.defaults] as the device's available memory changes.
 *
 * Applied against a live [InferenceConfig] with [applying], which clamps every field to
 * what the device and model can actually do — a persisted override is user intent, not a
 * guarantee, and the RAM/thread/accelerator ceilings exist to keep a stale or hand-edited
 * value from crashing the inference process.
 */
@Serializable
data class InferenceOverrides(
    val threads: Int? = null,
    val contextTokens: Int? = null,
    val accelerator: Accelerator? = null,
    val temperature: Float? = null,
    val topK: Int? = null,
    val topP: Float? = null,
    val flashAttention: Boolean? = null,
) {
    companion object {
        val NONE = InferenceOverrides()
    }
}

/**
 * Applies [overrides] on top of `this` config, clamped to what [deviceCapability] and
 * [model] can actually support.
 *
 * `this` is expected to already be an [InferenceConfig.defaults] result: its
 * [InferenceConfig.contextTokens] is the affordable ceiling for the device, and
 * [overrides.contextTokens] is only ever clamped *down* to it, never above — the RAM
 * heuristic that produced it is a safety limit against a native abort
 * (see [InferenceConfig.defaults]), not a suggestion the user can override upward.
 *
 * @param model the active model's [BackendSpec], used to resolve the accelerator the same
 *   way [resolveAccelerator] does everywhere else. Defaults to CPU-only when the caller has
 *   no model loaded yet (nothing to load, so nothing to offload).
 */
fun InferenceConfig.applying(
    overrides: InferenceOverrides,
    deviceCapability: DeviceCapability,
    model: BackendSpec = BackendSpec(),
): InferenceConfig {
    val maxThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    val clampedThreads = overrides.threads?.coerceIn(1, maxThreads) ?: threads
    // contextTokens on `this` is already the affordable ceiling — see the doc above.
    val clampedContext = overrides.contextTokens
        ?.coerceIn(1, contextTokens)
        ?: contextTokens
    val resolvedAccelerator = resolveAccelerator(overrides.accelerator, deviceCapability.accelerators, model)

    return copy(
        contextTokens = clampedContext,
        threads = clampedThreads,
        threadsBatch = clampedThreads,
        flashAttention = overrides.flashAttention ?: flashAttention,
        accelerator = resolvedAccelerator,
        gpuLayers = resolveGpuLayers(resolvedAccelerator, model),
        sampling = sampling.copy(
            temperature = overrides.temperature?.coerceIn(0f, 2f) ?: sampling.temperature,
            topK = overrides.topK?.coerceIn(1, 100) ?: sampling.topK,
            topP = overrides.topP?.coerceIn(0f, 1f) ?: sampling.topP,
        ),
    )
}
