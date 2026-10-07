package com.postsaimanager.core.domain.ai

import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeChatEngine

/** The contract against the plain fake. */
class FakeChatEngineContractTest : ChatEngineContract() {
    override fun engine(): ChatEngine = FakeChatEngine()
    override fun script(engine: ChatEngine, text: String) {
        (engine as FakeChatEngine).reply = text
    }
}

/** The contract against the llama.cpp-side fake, which is an [AiEngine] and so also a [ChatEngine]. */
class FakeAiEngineChatContractTest : ChatEngineContract() {
    override fun engine(): ChatEngine = FakeAiEngine()
    override fun script(engine: ChatEngine, text: String) {
        (engine as FakeAiEngine).response = text
    }
}

/** The contract through the router, over a llama.cpp-side fake and a LiteRT-side fake: routing must not change the behaviour. */
class RoutingChatEngineContractTest : ChatEngineContract() {
    private val llama = FakeChatEngine(name = "llama")
    private val litert = FakeChatEngine(name = "litert")

    override fun engine(): ChatEngine = RoutingChatEngine(
        mapOf(ModelRuntime.LLAMA_CPP to llama, ModelRuntime.LITERT_LM to litert),
    )

    // Whichever engine the load picks is the one that streams.
    override fun script(engine: ChatEngine, text: String) {
        llama.reply = text
        litert.reply = text
    }
}
