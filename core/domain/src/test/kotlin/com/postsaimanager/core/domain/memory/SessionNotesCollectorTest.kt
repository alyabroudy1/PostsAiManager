package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
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

/**
 * Plan 16, A1 + A2 + A3 wired, with the notes queued: a session that ends is queued, and the queued work hands exactly the session's
 * own messages to the note writer (a document's, or the household's) when the chat model is idle, and later when it is not.
 */
class SessionNotesCollectorTest {

    /** Answers one grounded note, and remembers what it was asked. */
    private class RecordingGenerator : SessionNoteGenerator {
        var prompts = mutableListOf<String>()
        var available = true
        override fun isAvailable() = available
        override suspend fun generate(system: String, prompt: String): String {
            prompts += prompt
            return "The user paid on 5 Oct."
        }
    }

    /** What the queue was asked to keep. */
    private class RecordingQueue : SessionNotesQueue {
        val events = mutableListOf<ChatSessionEnded>()
        override fun enqueue(event: ChatSessionEnded) {
            events += event
        }
    }

    private var now = 1_000_000L
    private val tracker = ChatSessionTracker { now }
    private val conversations = FakeConversationRepository()
    private val notes = FakeDocumentNoteRepository()
    private val generator = RecordingGenerator()
    private val queue = RecordingQueue()
    private val profiles = FakeProfileRepository().apply {
        seed(testProfile(id = "me", name = "Erika Mustermann", type = ProfileType.USER_SELF))
    }

    /** The note is about the one household person whose first name it carries; about nobody otherwise. */
    private val decider = object : NotePersonDecider {
        override suspend fun decide(note: String, persons: List<SubjectCandidate>): PamResult<String?> =
            PamResult.Success(persons.firstOrNull { note.contains(it.name.substringBefore(' ')) }?.profileId)
    }

    /** The chat the collector asks: by default nothing is active; a test begins a session on [tracker] to make it so. */
    private val collector = SessionNotesCollector(
        tracker,
        conversations,
        WriteSessionNotesUseCase(generator, notes, FakeDocumentRepository(), SessionNoteVerifier()),
        WriteHouseholdNotesUseCase(generator, notes, profiles, SessionNoteVerifier(), decider),
        queue,
        tracker,
    )

    private suspend fun conversation(id: String, documentId: String?) {
        conversations.createConversation(
            AiConversation(id = id, documentId = documentId, aiModelId = null, modelType = AiModelType.LOCAL, title = "t", lastMessageAt = 1, createdAt = 1),
        )
    }

    private suspend fun say(conversationId: String, id: String, role: MessageRole, text: String, at: Long) {
        conversations.addMessage(AiMessage(id = id, conversationId = conversationId, role = role, content = text, createdAt = at))
    }

    private fun written(run: SessionNotesRun) = (run as SessionNotesRun.Done).written

    @Test
    @DisplayName("a session end writes the notes from the messages of that session only, oldest first")
    fun `only the session's messages`() = runTest {
        conversation("c1", "d1")
        say("c1", "old-q", MessageRole.USER, "an old question from an earlier visit", at = 10)
        say("c1", "old-a", MessageRole.ASSISTANT, "an old answer", at = 11)
        tracker.begin("c1")
        say("c1", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)
        say("c1", "a", MessageRole.ASSISTANT, "Noted.", at = now + 2)
        tracker.leave("c1")

        val run = collector.write(ChatSessionEnded("c1", ChatSessionEnd.LEFT, startedAt = now))

        assertThat(written(run)).isEqualTo(1)
        assertThat(generator.prompts).hasSize(1)
        assertThat(generator.prompts.single()).contains("I paid on 5 Oct")
        assertThat(generator.prompts.single()).doesNotContain("an old question")
        assertThat(notes.snapshot.map { it.documentId }).containsExactly("d1")
        assertThat(notes.snapshot.single().text).isEqualTo("The user paid on 5 Oct.")
    }

    @Test
    @DisplayName("the tracker's end event queues the session (it is not written at that moment), and the queued run writes it")
    fun `leave queues the session`() = runTest {
        conversation("c1", "d1")
        collector.start(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        tracker.begin("c1")
        say("c1", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)

        tracker.leave("c1")
        testScheduler.advanceUntilIdle()

        assertThat(queue.events).hasSize(1)
        assertThat(queue.events.single().conversationId).isEqualTo("c1")
        assertThat(generator.prompts).isEmpty()
        assertThat(notes.snapshot).isEmpty()

        assertThat(written(collector.write(queue.events.single()))).isEqualTo(1)
        assertThat(notes.snapshot).hasSize(1)
    }

    @Test
    @DisplayName("the model is not free when the session ends: the run is deferred, nothing is lost, and the same session is written when it is free")
    fun `deferred while the model is busy then written`() = runTest {
        conversation("c1", "d1")
        tracker.begin("c1")
        say("c1", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)
        tracker.leave("c1")
        val event = ChatSessionEnded("c1", ChatSessionEnd.LEFT, startedAt = 1_000_000L)

        generator.available = false
        assertThat(collector.write(event)).isEqualTo(SessionNotesRun.Later)
        assertThat(notes.snapshot).isEmpty()

        generator.available = true
        assertThat(written(collector.write(event))).isEqualTo(1)
        assertThat(notes.snapshot).hasSize(1)
    }

    @Test
    @DisplayName("a parked session is not taken by the notes job of an earlier session: re-entering finds the same session, and the notes wait")
    fun `parked session survives a notes tick`() = runTest {
        conversation("old", "d0")
        say("old", "q0", MessageRole.USER, "I paid on 5 Oct", at = now + 1)
        val oldEvent = ChatSessionEnded("old", ChatSessionEnd.LEFT, startedAt = now)
        conversation("c1", "d1")
        assertThat(tracker.begin("c1")).isTrue()
        tracker.park("c1")

        now += 90_000
        assertThat(collector.write(oldEvent)).isEqualTo(SessionNotesRun.Later)
        assertThat(generator.prompts).isEmpty()

        now += 90_000
        tracker.enter("c1")
        assertThat(tracker.begin("c1")).isFalse()

        // The session really ends (left): the earlier notes are written then.
        tracker.leave("c1")
        assertThat(written(collector.write(oldEvent))).isEqualTo(1)
    }

    @Test
    @DisplayName("a chat is active again (the person came back): the notes wait, they never take the model from the live visit")
    fun `deferred while a chat is active`() = runTest {
        conversation("c1", "d1")
        tracker.begin("c1")
        say("c1", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)
        val event = ChatSessionEnded("c1", ChatSessionEnd.LEFT, startedAt = 1_000_000L)

        // The live session of this very chat is still on: not the moment to generate.
        assertThat(collector.write(event)).isEqualTo(SessionNotesRun.Later)
        assertThat(generator.prompts).isEmpty()

        tracker.leave("c1")
        assertThat(written(collector.write(event))).isEqualTo(1)
    }

    @Test
    @DisplayName("a chat of all documents writes the household's notes, not a document's")
    fun `all documents chat writes household notes`() = runTest {
        conversation("all", documentId = null)
        tracker.begin("all")
        say("all", "q", MessageRole.USER, "I paid on 5 Oct", at = now + 1)
        tracker.leave("all")

        collector.write(ChatSessionEnded("all", ChatSessionEnd.LEFT, startedAt = 1_000_000L))

        assertThat(generator.prompts).hasSize(1)
        assertThat(generator.prompts.single()).contains(SessionNotesFormat.ABOUT_HOUSEHOLD)
        val note = notes.snapshot.single()
        assertThat(note.documentId).isNull()
        assertThat(note.profileId).isNull()
        assertThat(note.text).isEqualTo("The user paid on 5 Oct.")
    }

    @Test
    @DisplayName("a session with no message of its own (opened and left) wakes nothing")
    fun `empty session writes nothing`() = runTest {
        conversation("c1", "d1")
        say("c1", "old-q", MessageRole.USER, "an old question", at = 10)

        assertThat(written(collector.write(ChatSessionEnded("c1", ChatSessionEnd.IDLE, startedAt = now)))).isEqualTo(0)
        assertThat(generator.prompts).isEmpty()
    }

    @Test
    @DisplayName("a chat that was deleted meanwhile has nothing to write")
    fun `deleted chat writes nothing`() = runTest {
        assertThat(written(collector.write(ChatSessionEnded("gone", ChatSessionEnd.LEFT, startedAt = 0)))).isEqualTo(0)
    }
}
