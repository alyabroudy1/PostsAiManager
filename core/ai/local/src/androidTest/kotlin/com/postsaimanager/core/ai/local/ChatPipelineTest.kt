package com.postsaimanager.core.ai.local

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.repository.ConversationRepository
import com.postsaimanager.core.domain.usecase.ChatErrorAction
import com.postsaimanager.core.domain.usecase.ChatTurn
import com.postsaimanager.core.domain.usecase.BuildChatContextUseCase
import com.postsaimanager.core.domain.usecase.SendChatMessageUseCase
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end test of the chat pipeline against the **real on-device engine**.
 *
 * This is the wiring that replaced the mock: use case → model load → streamed generation →
 * persistence. Everything below the use case is real; only the repository is in-memory, so
 * the test asserts ordering and content rather than Room behaviour.
 */
@RunWith(AndroidJUnit4::class)
class ChatPipelineTest {

    private val modelFile = File("/data/local/tmp/spike-model.gguf")
    private val tag = "pam_spike"

    /** Minimal in-memory [ConversationRepository]; records write order. */
    private class RecordingConversationRepository : ConversationRepository {
        val conversations = mutableListOf<AiConversation>()
        val messages = MutableStateFlow<List<AiMessage>>(emptyList())
        val writeOrder = mutableListOf<String>()

        override fun getConversationsForDocument(documentId: String): Flow<List<AiConversation>> =
            MutableStateFlow(conversations.filter { it.documentId == documentId })

        override fun getMessages(conversationId: String): Flow<List<AiMessage>> =
            messages.map { list -> list.filter { it.conversationId == conversationId } }

        override suspend fun getConversationById(id: String): PamResult<AiConversation> =
            conversations.firstOrNull { it.id == id }
                ?.let { PamResult.Success(it) }
                ?: PamResult.Error(PamError.FileNotFound("no conversation $id"))

        override suspend fun createConversation(conversation: AiConversation): PamResult<AiConversation> {
            conversations += conversation
            writeOrder += "conversation"
            return PamResult.Success(conversation)
        }

        override suspend fun addMessage(message: AiMessage): PamResult<AiMessage> {
            messages.value = messages.value + message
            writeOrder += message.role.name
            return PamResult.Success(message)
        }

        override suspend fun updateMessage(message: AiMessage): PamResult<Unit> =
            PamResult.Success(Unit)

        override suspend fun deleteConversation(id: String): PamResult<Unit> =
            PamResult.Success(Unit)
    }

    private class StubActiveModel(private val path: String?) : ActiveModelProvider {
        override suspend fun activeModelPath(): String? = path
        override suspend fun activeModelContextTokens(): Int = 1024
        override suspend fun extractionModelPath(): String? = path
        override suspend fun extractionModelContextTokens(): Int = 1024
    }

    /**
     * Real in-memory fakes. The documents are unseeded, so context building takes its
     * document-not-found path and yields the standalone prompt — which is what these tests
     * want to exercise; grounding has its own unit tests.
     */
    private fun contextUseCase() =
        BuildChatContextUseCase(FakeDocumentRepository(), FakeProfileRepository())

    private fun requireModel() {
        assumeTrue("No model at ${modelFile.path}", modelFile.exists())
        assumeTrue("Native library unavailable", LlamaNative.ensureLoaded())
    }

    @Test
    fun producesARealStreamedReplyAndPersistsBothTurns() = runBlocking {
        requireModel()

        val repo = RecordingConversationRepository()
        val engine = LocalAiEngine(Dispatchers.IO)
        val useCase = SendChatMessageUseCase(repo, engine, StubActiveModel(modelFile.absolutePath), contextUseCase())

        try {
            val turns = useCase(
                conversationId = "conv-1",
                documentId = "doc-1",
                text = "Name three colours.",
            ).toList()

            val tokens = turns.filterIsInstance<ChatTurn.Token>()
            val complete = turns.filterIsInstance<ChatTurn.Complete>().singleOrNull()
            val reply = tokens.joinToString("") { it.text }

            Log.i(tag, "chat turns=${turns.size} tokens=${tokens.size} reply=<<<$reply>>>")

            // The engine was cold, so the model had to be loaded first.
            assertTrue(
                "expected a PreparingModel turn",
                turns.any { it is ChatTurn.PreparingModel },
            )
            assertTrue("expected streamed tokens, got ${tokens.size}", tokens.size > 1)
            assertTrue("no completion turn", complete != null)
            assertTrue("empty reply", reply.isNotBlank())

            // The persisted assistant message must match what was streamed — otherwise the
            // UI and the history would disagree after a reopen.
            assertEquals(reply, complete!!.message.content)

            // Ordering is load-bearing: the user's message is stored BEFORE generation, so
            // a native abort mid-reply (spike Q3 showed it kills the process) cannot lose
            // what they typed.
            assertEquals(
                listOf("conversation", "USER", "ASSISTANT"),
                repo.writeOrder,
            )

            val stored = repo.messages.value
            assertEquals(2, stored.size)
            assertEquals(MessageRole.USER, stored[0].role)
            assertEquals("Name three colours.", stored[0].content)
            assertEquals(MessageRole.ASSISTANT, stored[1].role)
        } finally {
            engine.unload()
        }
    }

    @Test
    fun withNoModelInstalledItReportsAnActionableFailure() = runBlocking {
        val repo = RecordingConversationRepository()
        val engine = LocalAiEngine(Dispatchers.IO)
        val useCase = SendChatMessageUseCase(repo, engine, StubActiveModel(null), contextUseCase())

        val turns = useCase("conv-2", null, "Hello").toList()
        val failure = turns.filterIsInstance<ChatTurn.Failed>().single()

        Log.i(tag, "chat noModel failure=${failure.message} action=${failure.action}")

        // Not a silent no-op: the user is told what is wrong and where to fix it.
        assertEquals(ChatErrorAction.INSTALL_MODEL, failure.action)
        assertTrue(failure.message.contains("model", ignoreCase = true))

        // Their message is still saved, so nothing is lost by the failure.
        assertEquals(1, repo.messages.value.size)
        assertEquals(MessageRole.USER, repo.messages.value.first().role)
    }

    @Test
    fun reusesAnExistingConversationRatherThanCreatingDuplicates() = runBlocking {
        requireModel()

        val repo = RecordingConversationRepository()
        val engine = LocalAiEngine(Dispatchers.IO)
        val useCase = SendChatMessageUseCase(repo, engine, StubActiveModel(modelFile.absolutePath), contextUseCase())

        try {
            useCase("conv-3", "doc-3", "First question.").toList()
            useCase("conv-3", "doc-3", "Second question.").toList()

            assertEquals("conversation created twice", 1, repo.conversations.size)
            // 2 user + 2 assistant
            assertEquals(4, repo.messages.value.size)
        } finally {
            engine.unload()
        }
    }
}
