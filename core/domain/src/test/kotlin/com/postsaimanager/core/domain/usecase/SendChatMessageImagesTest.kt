package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.MessageImages
import com.postsaimanager.core.model.MediaType
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Pictures in a chat message: stored with the user's message, sent to a model that can look at them, replayed as a marker. */
class SendChatMessageImagesTest {

    private val engine = FakeChatEngine(name = "chat", supportsThinking = false)
    private val models = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM, supportsImages = true)
    private val conversations = FakeConversationRepository()
    private val sendChatMessage = SendChatMessageUseCase(
        conversations,
        engine,
        models,
        BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
        RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService(), ObserveChatVisibleDocumentsUseCase(FakeDocumentRepository())),
    )

    @Test
    @DisplayName("the pictures are stored with the user's message and handed to the engine for that reply")
    fun `pictures reach the engine and the message`() = runTest {
        sendChatMessage("conv", documentId = null, text = "What is this?", imagePaths = listOf("/a/1.png", "/a/2.png")).toList()

        assertThat(engine.requests.single().imagePaths).containsExactly("/a/1.png", "/a/2.png").inOrder()
        val user = conversations.getMessages("conv").first().first { it.role == MessageRole.USER }
        assertThat(user.mediaType).isEqualTo(MediaType.IMAGE)
        assertThat(MessageImages.decode(user.mediaPath)).containsExactly("/a/1.png", "/a/2.png").inOrder()
    }

    @Test
    @DisplayName("pictures are never dropped silently: a model that cannot look at them gets a visible error and nothing is sent or stored")
    fun `a model that cannot look at pictures fails visibly`() = runTest {
        models.supportsImages = false

        val turns = sendChatMessage("conv", documentId = null, text = "What is this?", imagePaths = listOf("/a/1.png")).toList()

        val failed = turns.single() as ChatTurn.Failed
        assertThat(failed.action).isEqualTo(ChatErrorAction.IMAGES_NOT_SUPPORTED)
        assertThat(engine.requests).isEmpty()
        assertThat(conversations.getMessages("conv").first()).isEmpty()
    }

    @Test
    fun `a retry of a message with pictures on a model that cannot see them fails visibly`() = runTest {
        sendChatMessage("conv", documentId = null, text = "What is this?", imagePaths = listOf("/a/1.png")).toList()
        conversations.deleteMessage(conversations.getMessages("conv").first().last { it.role == MessageRole.ASSISTANT }.id)
        models.supportsImages = false

        val turns = sendChatMessage("conv", documentId = null, text = "What is this?", persistUserMessage = false).toList()

        assertThat(turns.filterIsInstance<ChatTurn.Failed>().single().action).isEqualTo(ChatErrorAction.IMAGES_NOT_SUPPORTED)
        assertThat(engine.requests).hasSize(1)
    }

    @Test
    fun `at most the Gallery's ten pictures are sent`() = runTest {
        val twelve = (1..12).map { "/a/$it.png" }

        sendChatMessage("conv", documentId = null, text = "all of them", imagePaths = twelve).toList()

        assertThat(engine.requests.single().imagePaths).hasSize(MessageImages.MAX_PER_MESSAGE)
    }

    @Test
    @DisplayName("a message sent again as it is (a retry) keeps the pictures it was stored with")
    fun `a retry keeps the pictures`() = runTest {
        sendChatMessage("conv", documentId = null, text = "What is this?", imagePaths = listOf("/a/1.png")).toList()
        // As a regenerate leaves the chat: the question is the last row, its answer deleted.
        conversations.deleteMessage(conversations.getMessages("conv").first().last { it.role == MessageRole.ASSISTANT }.id)

        sendChatMessage("conv", documentId = null, text = "What is this?", persistUserMessage = false).toList()

        assertThat(engine.requests.last().imagePaths).containsExactly("/a/1.png")
        assertThat(conversations.getMessages("conv").first().count { it.role == MessageRole.USER }).isEqualTo(1)
    }

    @Test
    @DisplayName("only the current turn sends pixels: an earlier picture is a text marker in the rebuilt history")
    fun `earlier pictures are replayed as a marker`() = runTest {
        sendChatMessage("conv", documentId = null, text = "What is this?", imagePaths = listOf("/a/1.png", "/a/2.png")).toList()
        engine.resetChatSession()
        engine.sessions.clear()

        sendChatMessage("conv", documentId = null, text = "And the second?").toList()

        val replayed = engine.sessions.single().first { it.role == com.postsaimanager.core.domain.ai.AiChatRole.USER }
        assertThat(replayed.content).startsWith("[image: photo 1] [image: photo 2]")
        assertThat(replayed.content).contains("What is this?")
        assertThat(engine.requests.last().imagePaths).isEmpty()
    }
}
