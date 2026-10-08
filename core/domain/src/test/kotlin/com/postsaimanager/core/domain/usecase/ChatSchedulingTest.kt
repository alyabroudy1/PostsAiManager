package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiCapabilities
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.testing.FakeActiveModelProvider
import com.postsaimanager.core.testing.FakeAiEngine
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentChunkRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeProfileRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The chat's side of the scheduling with readings: active before its model loads, and a warm-up that starts when the engine is free. */
class ChatSchedulingTest {

    private val fake = FakeAiEngine()
    private var now = 1_000_000L
    private val tracker = ChatSessionTracker { now }

    /** What [tracker] said at the moment the model was loaded. */
    private val activeAtLoad = mutableListOf<Boolean>()

    private val engine: ChatEngine = object : ChatEngine by fake {
        override suspend fun load(modelPath: String, config: InferenceConfig): PamResult<AiCapabilities> {
            activeAtLoad += tracker.isChatActive()
            return fake.load(modelPath, config)
        }
    }
    private val models = FakeActiveModelProvider()
    private val conversations = FakeConversationRepository()
    private val buildChatContext = BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository(), com.postsaimanager.core.testing.letterContactsFor())
    private val retrieveChunks = RetrieveChunksUseCase(FakeDocumentChunkRepository(), FakeEmbeddingService(), ObserveChatVisibleDocumentsUseCase(FakeDocumentRepository()))
    private val sendChatMessage = SendChatMessageUseCase(conversations, engine, models, buildChatContext, retrieveChunks, sessions = tracker)

    @Test
    @DisplayName("a send makes the chat active before the model is loaded, so a reading that would start meanwhile waits")
    fun `active before the load of a send`() = runTest {
        fake.response = "hi"

        sendChatMessage("conv-1", documentId = null, text = "hello").toList()

        assertThat(activeAtLoad).containsExactly(true)
        assertThat(tracker.isChatActive()).isTrue()
    }

    @Test
    @DisplayName("opening the chat (the prime) makes it active before its model is loaded")
    fun `active before the load of a prime`() = runTest {
        sendChatMessage.primeConversation("conv-1", documentId = null)

        assertThat(activeAtLoad).containsExactly(true)
        assertThat(tracker.isChatActive()).isTrue()
    }

    @Test
    @DisplayName("a load that failed leaves no live session behind: the chat is not 'active' for ten minutes over nothing")
    fun `a failed load is not a session`() = runTest {
        fake.loadFailsWith = PamResult.Error(com.postsaimanager.core.common.result.PamError.ModelNotLoaded("x"))

        sendChatMessage("conv-1", documentId = null, text = "hello").toList()
        sendChatMessage.primeConversation("conv-2", documentId = null)

        assertThat(tracker.isChatActive()).isFalse()
    }

    @Test
    @DisplayName("the warm-up does not start while a reading holds the engine, and starts when the engine is free")
    fun `warm-up waits for the engine`() = runTest {
        fake.isBusy = true
        val prime = launch { sendChatMessage.primeConversation("conv-1", documentId = null) }

        advanceTimeBy(30_000)
        assertThat(fake.warmUps).isEmpty()
        assertThat(prime.isCompleted).isFalse()

        fake.isBusy = false
        advanceUntilIdle()

        assertThat(fake.warmUps).hasSize(1)
        assertThat(prime.isCompleted).isTrue()
    }

    @Test
    @DisplayName("a reading that outlasts the wait leaves the warm-up out: the first message prepares the conversation")
    fun `warm-up gives up on a long reading`() = runTest {
        fake.isBusy = true

        sendChatMessage.primeConversation("conv-1", documentId = null)

        assertThat(fake.warmUps).isEmpty()
        // The session itself was opened (it is quick and waits its turn in the engine); only the long prefill is left out.
        assertThat(fake.ensureChatSessionCalls).hasSize(1)
    }
}
