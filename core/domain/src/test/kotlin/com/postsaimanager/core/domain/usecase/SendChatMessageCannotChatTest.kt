package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeProfileRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** A chat model whose chat template does not render says so, with the way out, instead of "the chat turn could not be started". */
class SendChatMessageCannotChatTest {

    private val engine = FakeAiEngine()
    private val models = FakeActiveModelProvider()
    private val conversations = FakeConversationRepository()
    private val sendChatMessage = SendChatMessageUseCase(
        conversations,
        engine,
        models,
        BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
        RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService(), ObserveChatVisibleDocumentsUseCase(FakeDocumentRepository())),
    )

    @Test
    @DisplayName("a model that loaded but cannot chat fails the turn with the choose-a-chat-model action, and nothing is sent to it")
    fun `cannot chat is said plainly`() = runTest {
        engine.canChat = false
        engine.response = "never said"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        val failed = turns.filterIsInstance<ChatTurn.Failed>().single()
        assertThat(failed.action).isEqualTo(ChatErrorAction.CHOOSE_CHAT_MODEL)
        assertThat(failed.message).isEqualTo("This model can't chat. Choose a chat model in AI models")
        assertThat(failed.message).doesNotContain("could not be started")
        assertThat(turns.filterIsInstance<ChatTurn.Complete>()).isEmpty()
        assertThat(engine.ensureChatSessionCalls).isEmpty()
    }

    @Test
    @DisplayName("a model that can chat is not affected")
    fun `can chat goes through`() = runTest {
        engine.canChat = true
        engine.response = "hi"

        val turns = sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        assertThat(turns.filterIsInstance<ChatTurn.Failed>()).isEmpty()
        assertThat(turns.filterIsInstance<ChatTurn.Complete>()).hasSize(1)
    }

    @Test
    @DisplayName("opening the chat on a model that cannot chat primes nothing")
    fun `prime skips a model that cannot chat`() = runTest {
        engine.canChat = false

        sendChatMessage.primeConversation("conv-1", documentId = null)

        assertThat(engine.ensureChatSessionCalls).isEmpty()
    }
}
