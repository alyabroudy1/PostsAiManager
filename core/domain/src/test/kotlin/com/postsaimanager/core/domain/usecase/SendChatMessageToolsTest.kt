package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.ChatToolsRequest
import com.postsaimanager.core.model.ModelRuntime
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

/** A chat reply asks for the Agent Skills tools only when the active model is a LiteRT-LM one that declares them. */
class SendChatMessageToolsTest {

    private val engine = FakeChatEngine(name = "chat", supportsThinking = false)
    private val models = FakeActiveModelProvider()
    private val sendChatMessage = SendChatMessageUseCase(
        FakeConversationRepository(),
        engine,
        models,
        BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor()),
        RetrieveChunksUseCase(
            FakeDocumentChunkRepository(),
            FakeEmbeddingService(),
            ObserveChatVisibleDocumentsUseCase(FakeDocumentRepository()),
        ),
    )

    private suspend fun send(documentId: String?) {
        sendChatMessage("conv", documentId = documentId, text = "Draft a reply").toList()
    }

    @Test
    @DisplayName("a LiteRT-LM model that declares tools gets them, grounded on the chat's letter")
    fun `tools are requested for a tool-capable LiteRT model`() = runTest {
        models.runtime = ModelRuntime.LITERT_LM
        models.supportsTools = true

        send(documentId = null)

        assertThat(engine.requests.single().tools).isEqualTo(ChatToolsRequest(documentId = null))
    }

    @Test
    fun `a LiteRT model without declared support gets no tools`() = runTest {
        models.runtime = ModelRuntime.LITERT_LM
        models.supportsTools = false

        send(documentId = null)

        assertThat(engine.requests.single().tools).isNull()
    }

    @Test
    fun `llama cpp chat stays tool-less even when the descriptor declares tools`() = runTest {
        models.runtime = ModelRuntime.LLAMA_CPP
        models.supportsTools = true

        send(documentId = null)

        assertThat(engine.requests.single().tools).isNull()
    }
}
