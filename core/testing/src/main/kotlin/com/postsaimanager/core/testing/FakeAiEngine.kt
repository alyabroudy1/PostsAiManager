package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiEngine
import com.postsaimanager.core.domain.ai.AiEngineState
import com.postsaimanager.core.domain.ai.AiRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * An [AiEngine] that returns whatever a test tells it to.
 *
 * Lets everything around generation — prompt construction, grammar selection, parsing,
 * failure handling — be tested without a multi-gigabyte model or a device. The tests that
 * need a real model are device tests and say so.
 */
class FakeAiEngine(
    override var isReady: Boolean = true,
) : AiEngine {

    private val _state = MutableStateFlow<AiEngineState>(
        AiEngineState.Ready(
            AiCapabilities(
                supportsGrammar = true,
                contextTokens = 4096,
                modelName = "fake",
                hasNativeChatTemplate = true,
            ),
        ),
    )
    override val state: StateFlow<AiEngineState> = _state

    /** What [generate] emits, in chunks, to exercise streaming. */
    var response: String = ""

    /** When set, [generate] throws — for the "inference died" path. */
    var failWith: Exception? = null

    /** Captured so tests can assert what was asked, and how. */
    var lastRequest: AiRequest? = null
        private set

    var lastMessages: List<AiChatMessage> = emptyList()
        private set

    override suspend fun load(modelPath: String, contextTokens: Int): PamResult<AiCapabilities> {
        isReady = true
        return PamResult.Success(
            AiCapabilities(true, contextTokens, "fake", hasNativeChatTemplate = true),
        )
    }

    override fun generate(request: AiRequest): Flow<String> = flow {
        lastRequest = request
        failWith?.let { throw it }
        // Emitted in pieces: a caller that assumes one emission per generation would pass a
        // single-chunk fake and fail against the real streaming engine.
        response.chunked(7).forEach { emit(it) }
    }

    override fun formatPrompt(messages: List<AiChatMessage>): String {
        lastMessages = messages
        return messages.joinToString("\n") { "${it.role.wireName}: ${it.content}" }
    }

    override suspend fun unload() {
        isReady = false
    }
}
