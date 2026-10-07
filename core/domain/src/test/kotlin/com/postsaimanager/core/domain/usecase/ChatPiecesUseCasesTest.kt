package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.ChatImagePolicy
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.ModelRuntime
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeChatImageStore
import com.postsaimanager.core.testing.FakeConversationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The new chat (the Gallery's reset session), the attach step and the image-support decision. */
class ChatPiecesUseCasesTest {

    private val conversations = FakeConversationRepository()
    private val engine = FakeChatEngine(name = "chat")
    private val images = FakeChatImageStore(unreadable = setOf("content://not-a-picture"))

    private suspend fun seedChat(id: String) {
        conversations.createConversation(
            AiConversation(id = id, documentId = "doc", aiModelId = null, modelType = AiModelType.LOCAL, title = "t", lastMessageAt = 1, createdAt = 1),
        )
        conversations.addMessage(AiMessage(id = "m1", conversationId = id, role = MessageRole.USER, content = "hi", createdAt = 1))
        conversations.addMessage(AiMessage(id = "m2", conversationId = id, role = MessageRole.ASSISTANT, content = "hello", createdAt = 2))
    }

    @Test
    @DisplayName("a new chat deletes the history and the pictures, and drops the model's conversation; it stays deleted")
    fun `new chat deletes and resets`() = runTest {
        seedChat("conv-doc")
        seedChat("conv-other")
        images.import("conv-doc", "content://a")
        images.import("conv-other", "content://b")
        engine.load("/model", InferenceConfig(contextTokens = 4096, threads = 2))
        engine.ensureChatSession("conv-doc", "grounding", emptyList())
        assertThat(engine.isChatSessionPrimed("conv-doc")).isTrue()

        val cleared = StartNewChatUseCase(conversations, engine, images)("conv-doc")

        assertThat(cleared).isTrue()
        // Nothing is archived: the rows are gone and a later read finds nothing.
        assertThat(conversations.getMessages("conv-doc").first()).isEmpty()
        assertThat(conversations.getConversationById("conv-doc")).isInstanceOf(com.postsaimanager.core.common.result.PamResult.Error::class.java)
        assertThat(images.stored).doesNotContainKey("conv-doc")
        assertThat(engine.isChatSessionPrimed("conv-doc")).isFalse()
        // Another chat is untouched.
        assertThat(conversations.getMessages("conv-other").first()).hasSize(2)
        assertThat(images.stored).containsKey("conv-other")
    }

    @Test
    fun `a chat that never started is already new`() = runTest {
        assertThat(StartNewChatUseCase(conversations, engine, images)("conv-none")).isTrue()
    }

    @Test
    fun `attaching stores the picture in the chat's own folder, and a file that is not a picture is refused`() = runTest {
        val attach = AttachChatImageUseCase(images)
        assertThat(attach("conv-doc", "content://photo/1")).isEqualTo("/chat-attachments/conv-doc/1.png")
        assertThat(attach("conv-doc", "content://not-a-picture")).isNull()
    }

    @Test
    @DisplayName("pictures are offered only for a LiteRT-LM model whose catalogue entry declares image input")
    fun `image support follows the catalogue entry and the runtime`() = runTest {
        val models = FakeActiveModelProvider(runtime = ModelRuntime.LITERT_LM, supportsImages = true)
        assertThat(ChatImageSupportUseCase(models)()).isTrue()

        models.supportsImages = false
        assertThat(ChatImageSupportUseCase(models)()).isFalse()

        models.supportsImages = true
        models.runtime = ModelRuntime.LLAMA_CPP
        assertThat(ChatImageSupportUseCase(models)()).isFalse()
        assertThat(ChatImagePolicy.enabledFor(models.activeModelConfig())).isFalse()
    }
}
