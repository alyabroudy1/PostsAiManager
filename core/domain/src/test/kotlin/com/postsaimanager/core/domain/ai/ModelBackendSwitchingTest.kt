package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.gemma.ChatEngineGemmaReader
import com.postsaimanager.core.domain.extraction.gemma.GemmaReaderRequest
import com.postsaimanager.core.domain.extraction.gemma.MiniLetter
import com.postsaimanager.core.model.Accelerator
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The reading/chat backend switching: one reload per switch, none by default, no ping-pong across a batch of readings. */
class ModelBackendSwitchingTest {

    private val path = "/models/gemma.litertlm"
    private val engine = FakeChatEngine().apply { structuredAnswer = """{"category":"bill"}""" }
    private val provider = FakeActiveModelProvider(path = path, runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val letter = MiniLetter().letter

    private fun read() = runBlocking { ChatEngineGemmaReader(engine, provider).read(GemmaReaderRequest(letter, emptyList())) }

    /** What the chat does before a reply: loads the chat's own config. */
    private fun chatLoad() = runBlocking { engine.loadForUse(ModelUse.CHAT, path, provider.activeModelConfig()) }

    private fun accelerators() = engine.loads.map { it.second.accelerator }

    @Test
    @DisplayName("planning: nothing resident is a load, the same accelerator is kept, the other one is a reload")
    fun `plans`() {
        val config = runBlocking { provider.activeModelConfig() }
        val onCpu = ModelLoadState.Ready(path, config, 0L)

        assertThat(planBackend(ModelLoadState.Idle, path, Accelerator.GPU)).isEqualTo(BackendPlan.Load(Accelerator.GPU))
        assertThat(planBackend(onCpu, path, Accelerator.CPU)).isEqualTo(BackendPlan.Keep)
        assertThat(planBackend(onCpu, path, Accelerator.GPU)).isEqualTo(BackendPlan.Reload(Accelerator.CPU, Accelerator.GPU))
        assertThat(planBackend(onCpu, "/models/other.litertlm", Accelerator.CPU)).isEqualTo(BackendPlan.Load(Accelerator.CPU))
    }

    @Test
    @DisplayName("by default (reading on CPU, chat on CPU) a reading and a chat load the same config: no backend switch at all")
    fun `no switch by default`() {
        read()
        chatLoad()
        read()

        assertThat(accelerators().toSet()).containsExactly(Accelerator.CPU)
        assertThat(planBackend(engine.state.value, path, Accelerator.CPU)).isEqualTo(BackendPlan.Keep)
    }

    @Test
    @DisplayName("a reading on GPU loads the model on the GPU, and the chat's next load switches it back to the CPU")
    fun `reading on gpu then chat on cpu`() {
        provider.readingAccelerator = Accelerator.GPU

        read()
        assertThat(accelerators()).containsExactly(Accelerator.GPU)

        chatLoad()
        assertThat(accelerators()).containsExactly(Accelerator.GPU, Accelerator.CPU).inOrder()
        assertThat((engine.state.value as ModelLoadState.Ready).config.accelerator).isEqualTo(Accelerator.CPU)
    }

    @Test
    @DisplayName("queued readings are batched on the GPU: three readings are three loads of the same config, which is one reload; the chat backend returns only when the chat asks")
    fun `a batch of readings is one reload`() {
        provider.readingAccelerator = Accelerator.GPU
        chatLoad()
        val reloadsBefore = reloads()

        read()
        read()
        read()

        assertThat(reloads() - reloadsBefore).isEqualTo(1)
        // Nothing switched back by itself after the batch.
        assertThat((engine.state.value as ModelLoadState.Ready).config.accelerator).isEqualTo(Accelerator.GPU)

        chatLoad()
        assertThat(reloads() - reloadsBefore).isEqualTo(2)
    }

    @Test
    @DisplayName("the reading config is used by the reader's structured turn too (the model is loaded for the reading, then asked)")
    fun `the reader asks on the reading backend`() {
        provider.readingAccelerator = Accelerator.GPU

        val outcome = read()

        assertThat(outcome).isInstanceOf(com.postsaimanager.core.domain.extraction.gemma.GemmaReaderOutcome.Answered::class.java)
        assertThat(engine.structuredRequests).hasSize(1)
        assertThat((engine.state.value as ModelLoadState.Ready).config.accelerator).isEqualTo(Accelerator.GPU)
    }

    /** How many times the accelerator changed between two consecutive loads, as the engine would reload. */
    private fun reloads(): Int = accelerators().zipWithNext().count { (a, b) -> a != b }
}
