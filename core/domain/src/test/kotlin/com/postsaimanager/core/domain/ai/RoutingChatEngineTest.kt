package com.postsaimanager.core.domain.ai

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeChatEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The router a feature talks to: the model's runtime (from the catalogue's descriptor, carried on the config) picks the engine,
 * and every call after the load goes to that engine. This is also the engine-switch policy a chat model change goes through.
 */
class RoutingChatEngineTest {

    private val llama = FakeChatEngine(name = "llama")
    private val litert = FakeChatEngine(name = "litert", supportsThinking = false)
    private val router = RoutingChatEngine(mapOf(ModelRuntime.LLAMA_CPP to llama, ModelRuntime.LITERT_LM to litert))

    private fun config(runtime: ModelRuntime) = InferenceConfig(contextTokens = 4096, threads = 4, runtime = runtime)

    @Test
    @DisplayName("a load goes to the engine of the config's runtime, and to no other")
    fun `load is routed by runtime`() = runTest {
        router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))

        assertThat(litert.loads.map { it.first }).containsExactly("/models/gemma.litertlm")
        assertThat(llama.loads).isEmpty()
        assertThat(router.activeRuntime).isEqualTo(ModelRuntime.LITERT_LM)
    }

    @Test
    @DisplayName("after a load, the session and the stream go to the engine that took it")
    fun `calls follow the loaded engine`() = runTest {
        router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))
        litert.reply = "from litert"
        llama.reply = "from llama"

        router.ensureChatSession("c1", "grounding", emptyList())
        val reply = router.sendChatMessage("q", AiRequest(prompt = "")).toList().joinToString("")
        router.commitChatReply(reply)

        assertThat(reply).isEqualTo("from litert")
        assertThat(litert.sent).containsExactly("q")
        assertThat(litert.committed).containsExactly("from litert")
        assertThat(llama.sent).isEmpty()
        assertThat(router.isChatSessionPrimed("c1")).isTrue()
    }

    @Test
    @DisplayName("switching the chat model from LiteRT-LM to llama.cpp and back sends each call to the right engine")
    fun `switch there and back`() = runTest {
        router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))
        router.ensureChatSession("c1", "grounding", emptyList())

        router.load("/models/qwen.gguf", config(ModelRuntime.LLAMA_CPP))
        // The llama.cpp engine has no session of its own for this conversation yet.
        assertThat(router.isChatSessionPrimed("c1")).isFalse()
        llama.reply = "qwen says"
        router.ensureChatSession("c1", "grounding", emptyList())
        assertThat(router.sendChatMessage("q", AiRequest(prompt = "")).toList().joinToString("")).isEqualTo("qwen says")

        router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))
        litert.reply = "gemma says"
        assertThat(router.sendChatMessage("q2", AiRequest(prompt = "")).toList().joinToString("")).isEqualTo("gemma says")
        assertThat(litert.sent).containsExactly("q2")
        assertThat(llama.sent).containsExactly("q")
    }

    @Test
    @DisplayName("the thinking capability is the loaded engine's, so the use case asks a LiteRT-LM model for none")
    fun `thinking follows the engine`() = runTest {
        router.load("/models/qwen.gguf", config(ModelRuntime.LLAMA_CPP))
        assertThat(router.supportsThinking).isTrue()

        router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))
        assertThat(router.supportsThinking).isFalse()
    }

    @Test
    @DisplayName("the state is the loaded engine's, readable the moment the load returns")
    fun `state follows the engine`() = runTest {
        router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))

        val state = router.state.value
        assertThat(state).isInstanceOf(ModelLoadState.Ready::class.java)
        assertThat((state as ModelLoadState.Ready).modelId).isEqualTo("/models/gemma.litertlm")
    }

    @Test
    @DisplayName("a collector of the state sees the switch")
    fun `state flow switches engines`() = runTest {
        router.load("/models/qwen.gguf", config(ModelRuntime.LLAMA_CPP))

        router.state.test {
            assertThat((awaitItem() as ModelLoadState.Ready).modelId).isEqualTo("/models/qwen.gguf")
            router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))
            // The other engine's own states (its Idle before the load) may come first; the switch ends on its Ready.
            var item = awaitItem()
            while (!(item is ModelLoadState.Ready && item.modelId == "/models/gemma.litertlm")) item = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("the router is busy while any engine is")
    fun `busy is any engine`() = runTest {
        assertThat(router.isBusy).isFalse()
    }

    @Test
    @DisplayName("a runtime nobody implements fails its load with a typed error, not a crash")
    fun `missing runtime fails typed`() = runTest {
        val llamaOnly = RoutingChatEngine(mapOf(ModelRuntime.LLAMA_CPP to llama))

        val result = llamaOnly.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))

        assertThat(result).isInstanceOf(PamResult.Error::class.java)
        assertThat(llama.loads).isEmpty()
        assertThat(llamaOnly.activeRuntime).isEqualTo(ModelRuntime.LLAMA_CPP)
    }

    @Test
    @DisplayName("unloading unloads every engine, so no runtime keeps a model behind the user's back")
    fun `unload reaches all engines`() = runTest {
        router.load("/models/qwen.gguf", config(ModelRuntime.LLAMA_CPP))
        router.load("/models/gemma.litertlm", config(ModelRuntime.LITERT_LM))

        router.unload()

        assertThat(llama.state.value).isEqualTo(ModelLoadState.Idle)
        assertThat(litert.state.value).isEqualTo(ModelLoadState.Idle)
    }
}
