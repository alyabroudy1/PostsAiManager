package com.postsaimanager.core.domain.ai

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.ModelLoadState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * What every [ChatEngine] must do, whatever runtime is behind it (the port's KDoc lists the contract). A runtime's engine passes
 * by subclassing this with its own [engine]; the suites in this package run it against the fake, the router, and llama.cpp's own
 * fake engine, so a change that breaks the contract for one of them is caught on the JVM. (The device runs the real engines.)
 */
abstract class ChatEngineContract {

    /** A fresh engine with nothing loaded. */
    protected abstract fun engine(): ChatEngine

    /** Makes the next reply of [engine] stream [text]. */
    protected abstract fun script(engine: ChatEngine, text: String)

    private val config = InferenceConfig(contextTokens = 4096, threads = 4)

    private fun AiRequest(text: String = "") = com.postsaimanager.core.domain.ai.AiRequest(prompt = text)

    @Test
    @DisplayName("a load leaves the engine Ready with exactly the config that was asked for")
    fun `load reports ready with the requested config`() = runTest {
        val engine = engine()

        val result = engine.load("/models/m", config)

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        val state = engine.state.value
        assertThat(state).isInstanceOf(ModelLoadState.Ready::class.java)
        assertThat((state as ModelLoadState.Ready).config).isEqualTo(config)
        assertThat(state.modelId).isEqualTo("/models/m")
    }

    @Test
    @DisplayName("a session is primed once opened, and the same conversation does not prime again")
    fun `session priming`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)
        assertThat(engine.isChatSessionPrimed("c1")).isFalse()

        assertThat(engine.ensureChatSession("c1", "grounding", emptyList())).isTrue()
        assertThat(engine.isChatSessionPrimed("c1")).isTrue()
        assertThat(engine.ensureChatSession("c1", "", emptyList())).isFalse()
        assertThat(engine.isChatSessionPrimed("c2")).isFalse()
    }

    @Test
    @DisplayName("loading a different model drops the primed session: the next turn re-primes")
    fun `a reload invalidates the session`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)
        engine.ensureChatSession("c1", "grounding", emptyList())

        engine.load("/models/other", config)

        assertThat(engine.isChatSessionPrimed("c1")).isFalse()
    }

    @Test
    @DisplayName("loading the same model with the same config again is a no-op for the session")
    fun `an unchanged load keeps the session`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)
        engine.ensureChatSession("c1", "grounding", emptyList())

        engine.load("/models/m", config)

        assertThat(engine.isChatSessionPrimed("c1")).isTrue()
    }

    @Test
    @DisplayName("a reply streams as the model produces it, in pieces that join to the whole")
    fun `reply streams`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)
        engine.ensureChatSession("c1", "grounding", emptyList())
        script(engine, "Die Gebühr beträgt 25 Euro. الرسوم ٢٥ يورو")

        val pieces = engine.sendChatMessage("Wie hoch ist die Gebühr?", AiRequest()).toList()

        assertThat(pieces.joinToString("")).isEqualTo("Die Gebühr beträgt 25 Euro. الرسوم ٢٥ يورو")
        assertThat(pieces.size).isGreaterThan(1)
    }

    @Test
    @DisplayName("a finished reply is committed and a stopped one is discarded; neither needs a session to be safe")
    fun `commit and discard`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)
        engine.ensureChatSession("c1", "grounding", emptyList())
        script(engine, "ok")
        engine.sendChatMessage("q", AiRequest()).toList()

        engine.commitChatReply("ok")
        engine.discardPendingReply()

        assertThat(engine.isChatSessionPrimed("c1")).isTrue()
    }

    @Test
    @DisplayName("resetting the session unprimes it")
    fun `reset drops the session`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)
        engine.ensureChatSession("c1", "grounding", emptyList())

        engine.resetChatSession()

        assertThat(engine.isChatSessionPrimed("c1")).isFalse()
    }

    @Test
    @DisplayName("a reply that was not cut off says so")
    fun `no hit limit by default`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)
        engine.ensureChatSession("c1", "grounding", emptyList())
        script(engine, "short")
        engine.sendChatMessage("q", AiRequest()).toList()

        assertThat(engine.lastReplyHitLimit()).isFalse()
    }

    @Test
    @DisplayName("unloading returns the engine to Idle")
    fun `unload`() = runTest {
        val engine = engine()
        engine.load("/models/m", config)

        engine.unload()

        assertThat(engine.state.value).isEqualTo(ModelLoadState.Idle)
        assertThat(engine.isBusy).isFalse()
    }
}
