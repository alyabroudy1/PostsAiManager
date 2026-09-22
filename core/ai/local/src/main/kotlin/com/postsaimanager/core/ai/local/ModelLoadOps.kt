package com.postsaimanager.core.ai.local

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.model.InferenceConfig

/**
 * The native/IPC operations [ModelLoadCoordinator] drives.
 *
 * Pulled out as an interface so the coordinator — single-flight dedup, [ReloadScope]
 * dispatch, the generation counter — is testable on the JVM with a fake, independent of
 * whether the real implementation is JNI calls in-process ([LocalAiEngine]) or AIDL calls
 * across the `:inference` process boundary ([RemoteAiEngine]).
 */
internal interface ModelLoadOps {

    /** Full load: model weights and context, from disk. */
    suspend fun loadModel(modelId: String, config: InferenceConfig): PamResult<AiCapabilities>

    /**
     * Recreates the `llama_context` for the already-resident model named [modelId], applying
     * [config]'s context/batch/thread/flash-attention fields. The model itself is not
     * reloaded — see [com.postsaimanager.core.model.ReloadScope.CONTEXT].
     */
    suspend fun recreateContext(modelId: String, config: InferenceConfig): PamResult<AiCapabilities>

    suspend fun unloadModel()

    /**
     * Whether a model is *actually* resident right now, checked out-of-band from whatever
     * state [ModelLoadCoordinator] last recorded.
     *
     * Exists because the recorded state can go stale without the coordinator's involvement —
     * the `:inference` process free the model on its own under memory pressure
     * ([InferenceService.onTrimMemory]), and the coordinator only finds out the next time it
     * is asked to do something. Consulted before taking the
     * [com.postsaimanager.core.model.ReloadScope.NONE] fast path, so a silent out-of-band
     * unload is never mistaken for "nothing to do".
     */
    suspend fun isActuallyLoaded(): Boolean
}
