package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.model.InferenceOverrides
import kotlinx.coroutines.flow.Flow

/**
 * Persists the user's edits to [com.postsaimanager.core.model.InferenceConfig], as
 * [InferenceOverrides] — a nullable-everything patch, not a full config, so it keeps
 * tracking [com.postsaimanager.core.model.InferenceConfig.defaults] wherever the user has
 * expressed no opinion. See [InferenceOverrides] for why.
 *
 * Implemented in `:core:ai:catalog` (Preferences DataStore, file `inference_settings`),
 * mirroring `UserPreferencesRepository`/`UserPreferencesRepositoryImpl` in
 * `:core:domain`/`:core:data` for app-wide settings. The implementation sits in
 * `:core:ai:catalog` rather than `:core:data` because that is already where
 * `CatalogActiveModelProvider` combines [InferenceOverrides] with device capability and the
 * active model's [com.postsaimanager.core.model.BackendSpec] into an effective
 * [com.postsaimanager.core.model.InferenceConfig] — putting the store there keeps that
 * combination local to one module instead of adding a `:core:data` → `:core:ai:catalog`
 * (or the reverse) dependency for a single settings flow.
 */
interface InferenceSettingsRepository {
    val overrides: Flow<InferenceOverrides>

    suspend fun update(overrides: InferenceOverrides)

    /** Clears every override, reverting to [com.postsaimanager.core.model.InferenceConfig.defaults]. */
    suspend fun reset()

    /**
     * Model file paths that have crashed the inference process while running on GPU on
     * this device, and so are no longer offered GPU acceleration — see
     * [com.postsaimanager.core.domain.ai.AiEngine.crashEvents] for how a crash is detected
     * and [blockGpu] for how it lands here.
     *
     * Keyed by the same model identifier `AiEngine.load` and `ActiveModelProvider` use
     * everywhere else — the installed file's absolute path — since that is the only
     * identifier that survives the boundary down to the `:inference` process, where the
     * crash is actually observed.
     */
    val gpuBlockedModels: Flow<Set<String>>

    /** Records that [modelId] crashed on GPU on this device; future loads fall back to CPU. */
    suspend fun blockGpu(modelId: String)

    /** Reverses [blockGpu] — offered so a future "try GPU again" affordance has somewhere to write. */
    suspend fun unblockGpu(modelId: String)
}
