package com.postsaimanager.core.ai.local

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.domain.ai.InferenceCrash
import com.postsaimanager.core.domain.repository.InferenceSettingsRepository
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for [InferenceCrashObserver] — the listener that turns a GPU crash reported on
 * [AiEngine.crashEvents] into a persisted "don't offer GPU for this model again" fact (task
 * B.2). Not `RemoteAiEngine` itself: that needs a bound `:inference` process and a real
 * `IBinder.DeathRecipient`, so its part of this (emitting the right [InferenceCrash] with
 * the right config) is exercised on-device; this is the pure "given a crash, what does the
 * observer do about it" logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InferenceCrashObserverTest {

    private fun config(accelerator: Accelerator) =
        InferenceConfig(contextTokens = 4096, threads = 4, accelerator = accelerator)

    @Test
    @DisplayName("a GPU crash blocks GPU for that model")
    fun `gpu crash blocks gpu`() = runTest {
        // Unconfined so `start()`'s collector subscribes synchronously and `emit` delivers
        // to it synchronously too — no virtual-time advancement needed to observe the effect.
        val engine = StubAiEngine()
        val settings = StubInferenceSettingsRepository()
        val observer = InferenceCrashObserver(engine, settings, Dispatchers.Unconfined)

        observer.start()
        engine.emit(InferenceCrash("/data/models/qwen.gguf", config(Accelerator.GPU)))

        assertThat(settings.blocked).containsExactly("/data/models/qwen.gguf")
    }

    @Test
    @DisplayName("a CPU crash is left alone — there is no accelerator to fall back to")
    fun `cpu crash does not block anything`() = runTest {
        val engine = StubAiEngine()
        val settings = StubInferenceSettingsRepository()
        val observer = InferenceCrashObserver(engine, settings, Dispatchers.Unconfined)

        observer.start()
        engine.emit(InferenceCrash("/data/models/qwen.gguf", config(Accelerator.CPU)))

        assertThat(settings.blocked).isEmpty()
    }

    @Test
    @DisplayName("a crash with no known model id is ignored — nothing to key the block on")
    fun `crash with no model id is ignored`() = runTest {
        val engine = StubAiEngine()
        val settings = StubInferenceSettingsRepository()
        val observer = InferenceCrashObserver(engine, settings, Dispatchers.Unconfined)

        observer.start()
        engine.emit(InferenceCrash(null, config(Accelerator.GPU)))

        assertThat(settings.blocked).isEmpty()
    }

    private class StubAiEngine : AiEngine {
        private val crashes = MutableSharedFlow<InferenceCrash>(extraBufferCapacity = 4)
        override val crashEvents: SharedFlow<InferenceCrash> = crashes
        suspend fun emit(crash: InferenceCrash) = crashes.emit(crash)

        override val state: StateFlow<ModelLoadState> = MutableStateFlow(ModelLoadState.Idle)
        override val isReady: Boolean = false
        override suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities> =
            error("not used by this test")
        override fun generate(request: AiRequest): Flow<String> = error("not used by this test")
        override suspend fun ensureChatSession(
            conversationId: String,
            systemPrompt: String,
            history: List<AiChatMessage>,
        ): Boolean = error("not used by this test")
        override fun sendChatMessage(userText: String, request: AiRequest): Flow<String> =
            error("not used by this test")
        override suspend fun commitChatReply(answer: String) = error("not used by this test")
        override suspend fun discardPendingReply() = error("not used by this test")
        override suspend fun resetChatSession() = error("not used by this test")
        override fun formatPrompt(messages: List<AiChatMessage>): String = error("not used by this test")
        override suspend fun unload() = error("not used by this test")
        override suspend fun availableAccelerators(): Set<Accelerator> = setOf(Accelerator.CPU)
    }

    private class StubInferenceSettingsRepository : InferenceSettingsRepository {
        val blocked = mutableSetOf<String>()
        override val overrides: Flow<InferenceOverrides> = MutableStateFlow(InferenceOverrides.NONE)
        override suspend fun update(overrides: InferenceOverrides) = Unit
        override suspend fun reset() = Unit
        override val gpuBlockedModels: Flow<Set<String>> = MutableStateFlow(emptySet())
        override suspend fun blockGpu(modelId: String) {
            blocked += modelId
        }
        override suspend fun unblockGpu(modelId: String) {
            blocked -= modelId
        }
    }
}
