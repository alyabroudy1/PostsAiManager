package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.usecase.ChatSessionEnd
import com.postsaimanager.core.domain.usecase.ChatSessionEnded
import com.postsaimanager.core.domain.usecase.ChatSessionTracker
import com.postsaimanager.core.model.AiConversation
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.AiModelType
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.testing.FakeConversationRepository
import com.postsaimanager.core.testing.FakeDocumentNoteRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Plan 16, A1 + A2 wired: a session that ends hands exactly its own messages to the note writer, for document chats only. */
class SessionNotesCollectorTest {

    /** Answers one grounded note, and remembers what it was asked. */
    private class RecordingGenerator : SessionNoteGenerator {
        var prompts = mutableListOf<String>()
        override fun isAvailable() = true
        override suspend fun generate(system: String, prompt: String): String {
            prompts += prompt
            return "The user paid on 5 Oct."
        }
    }

    private var now = 1_000_000L
    private val tracker = ChatSessionTracker { now }
    private val conversations = FakeConversationRepository()
    private val notes = FakeDocumentNoteRepository()
    private val generator = RecordingGenerator()
    private val collector = SessionNotesCollector(
        tracker,
        conversations,
        WriteSessionNotesUseCase(generator, notes, FakeDocumentRepository(), SessionNoteVerifier()),
    )

    private suspend fun conversation(id: String, documentId: String?) {
        conversations.createConversation(
            AiConversation(id = id, documentId = documentId, aiModelId = null, modelType = AiModelType.LOCAL, title = "t", lastMessageAt = 1, createdAt = 1),
        )
    }

    private suspend fun say(conversationId: String, id: String, role: MessageRole, text: String, at: Long) {
        conversations.addMessage(AiMessage(id = id, conversationId = conversationId, role = role, content = text, createdAt = at))
    }

    @Test
    @DisplayName("a session end writes the notes from the messages of that session only, oldest first")
    fun `only the session's messages`() = runTest {
        conversation("c1", "d1")
        say("c1", "old-q", MessageRole.USER, "an old question from an earlier visit", at = 10)
        say("c1", "old-a", MessageRole.ASSISTANT, "an old answer", at = 11)
        tracker.begin("c1")
        say("c1", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)
        say("c1", "a", MessageRole.ASSISTANT, "Noted.", at = now + 2)

        val written = collector.handle(ChatSessionEnded("c1", ChatSessionEnd.LEFT, startedAt = now))

        assertThat(written).isEqualTo(1)
        assertThat(generator.prompts).hasSize(1)
        assertThat(generator.prompts.single()).contains("I paid on 5 Oct")
        assertThat(generator.prompts.single()).doesNotContain("an old question")
        assertThat(notes.snapshot.map { it.documentId }).containsExactly("d1")
        assertThat(notes.snapshot.single().text).isEqualTo("The user paid on 5 Oct.")
    }

    @Test
    @DisplayName("the tracker's end event drives it: leaving the chat writes the notes in the collector's own scope")
    fun `leave triggers the writer`() = runTest {
        conversation("c1", "d1")
        collector.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        tracker.begin("c1")
        say("c1", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)

        tracker.leave("c1")
        testScheduler.advanceUntilIdle()

        assertThat(generator.prompts).hasSize(1)
        assertThat(notes.snapshot).hasSize(1)
    }

    @Test
    @DisplayName("a chat of all documents has no notes yet")
    fun `all documents chat writes nothing`() = runTest {
        conversation("all", documentId = null)
        collector.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        tracker.begin("all")
        say("all", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)

        tracker.leave("all")
        testScheduler.advanceUntilIdle()

        assertThat(generator.prompts).isEmpty()
        assertThat(notes.snapshot).isEmpty()
    }

    @Test
    @DisplayName("a session with no message of its own (opened and left) wakes nothing")
    fun `empty session writes nothing`() = runTest {
        conversation("c1", "d1")
        say("c1", "old-q", MessageRole.USER, "an old question", at = 10)

        assertThat(collector.handle(ChatSessionEnded("c1", ChatSessionEnd.IDLE, startedAt = now))).isEqualTo(0)
        assertThat(generator.prompts).isEmpty()
    }

    @Test
    @DisplayName("a chat that was deleted meanwhile has nothing to write")
    fun `deleted chat writes nothing`() = runTest {
        assertThat(collector.handle(ChatSessionEnded("gone", ChatSessionEnd.LEFT, startedAt = 0))).isEqualTo(0)
    }
}
