package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A load that never comes back must end in a clear failed state, never an endless spinner. */
class ModelLoadTimeoutTest {

    private val config = InferenceConfig(contextTokens = 4096, threads = 4)

    /** A service stuck behind something else: the load call never returns. */
    private class StuckOps : ModelLoadOps {
        val never = CompletableDeferred<PamResult<AiCapabilities>>()
        override suspend fun loadModel(modelId: String, config: InferenceConfig) = never.await()
        override suspend fun recreateContext(modelId: String, config: InferenceConfig) = never.await()
        override suspend fun unloadModel() = Unit
        override suspend fun isActuallyLoaded() = false
    }

    @Test
    @DisplayName("a load that never returns fails after the timeout, with a message the chat shows and Retry for it")
    fun `a stuck load ends in Failed`() = runTest {
        val coordinator = ModelLoadCoordinator(StuckOps(), loadTimeoutMs = 60_000)

        val result = coordinator.load("model-a", config)

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        val state = coordinator.state.value
        assertThat(state).isInstanceOf(ModelLoadState.Failed::class.java)
        assertThat((state as ModelLoadState.Failed).error).contains("took longer")
    }

    @Test
    fun `the next load after a timeout is a fresh attempt`() = runTest {
        val stuck = StuckOps()
        val coordinator = ModelLoadCoordinator(stuck, loadTimeoutMs = 60_000)
        coordinator.load("model-a", config)

        val retry = coordinator.load("model-a", config)

        assertThat(retry).isInstanceOf(PamResult.Error::class.java)
        assertThat(coordinator.state.value).isInstanceOf(ModelLoadState.Failed::class.java)
    }
}
