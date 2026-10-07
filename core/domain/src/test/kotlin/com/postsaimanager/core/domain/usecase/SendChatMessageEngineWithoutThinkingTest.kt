package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.ThinkingEffort
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeProfileRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Chat on an engine that cannot hand back a reasoning trace (LiteRT-LM in phase 1): whatever thinking effort the user set, the
 * turn is asked for none, and the reply is read as an answer from its first token (a thinking-start parser would swallow the
 * whole reply as reasoning and fail the turn).
 */
class SendChatMessageEngineWithoutThinkingTest {

    private val engine = FakeChatEngine(name = "litert", supportsThinking = false)
    private val models = FakeActiveModelProvider()
    private val conversations = FakeConversationRepository()
    private val retrieveChunks = RetrieveChunksUseCase(
        FakeDocumentChunkRepository(),
        FakeEmbeddingService(),
        ObserveChatVisibleDocumentsUseCase(FakeDocumentRepository()),
    )
    private val sendChatMessage = SendChatMessageUseCase(
        conversations,
        engine,
        models,
        BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
        retrieveChunks,
    )

    @Test
    @DisplayName("high thinking effort is turned off for an engine without thinking, and the reply is a normal answer")
    fun `thinking is off for an engine that cannot think`() = runTest {
        engine.reply = "Die Gebühr beträgt 25 Euro."

        val turns = sendChatMessage("conv-1", documentId = null, text = "Wie hoch ist die Gebühr?", thinkingEffort = ThinkingEffort.HIGH).toList()

        assertThat(engine.requests.single().thinkingEnabled).isFalse()
        assertThat(engine.requests.single().thinkingBudgetTokens).isEqualTo(0)
        val complete = turns.filterIsInstance<ChatTurn.Complete>().single()
        assertThat(complete.message.content).isEqualTo("Die Gebühr beträgt 25 Euro.")
        assertThat(turns.filterIsInstance<ChatTurn.ThinkingToken>()).isEmpty()
        assertThat(engine.committed).containsExactly("Die Gebühr beträgt 25 Euro.")
    }

    @Test
    @DisplayName("the same effort is honoured by an engine that can think")
    fun `thinking is kept for an engine that can think`() = runTest {
        val thinking = FakeChatEngine(name = "llama", supportsThinking = true)
        val useCase = SendChatMessageUseCase(
            conversations,
            thinking,
            models,
            BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
            retrieveChunks,
        )
        thinking.reply = "reasoning</think>answer"

        useCase("conv-2", documentId = null, text = "q", thinkingEffort = ThinkingEffort.HIGH).toList()

        assertThat(thinking.requests.single().thinkingEnabled).isTrue()
    }
}
