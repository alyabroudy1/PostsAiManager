package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.ai.AiRequest
import com.postsaimanager.core.model.NoteSource
import com.postsaimanager.core.testing.FakeChatEngine
import com.postsaimanager.core.testing.FakeDocumentNoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DocumentNotesUseCasesTest {

    private val repo = FakeDocumentNoteRepository()

    @Test
    fun `an action note is one note per card, written again it is replaced`() = runBlocking {
        val record = RecordActionNoteUseCase(repo)

        record("d1", "card-1", "Reminder set for 8 Oct 09:00: Send the documents")
        record("d1", "card-1", "Reminder set for 9 Oct 09:00: Send the documents")
        record("d1", "card-2", "Email to the Jobcenter opened on 7 Oct")

        assertThat(repo.snapshot.map { it.text }).containsExactly(
            "Reminder set for 9 Oct 09:00: Send the documents",
            "Email to the Jobcenter opened on 7 Oct",
        )
        assertThat(repo.snapshot.map { it.source }.toSet()).containsExactly(NoteSource.ACTION)
    }

    @Test
    fun `forgetting a card removes its note and a card without one is a no-op`() = runBlocking {
        RecordActionNoteUseCase(repo)("d1", "card-1", "Email opened")
        val forget = ForgetActionNoteUseCase(repo)

        forget("card-none")
        assertThat(repo.snapshot).hasSize(1)
        forget("card-1")

        assertThat(repo.snapshot).isEmpty()
    }

    @Test
    fun `the user adds a note of their own, tidied, and an empty one writes nothing`() = runBlocking {
        val add = AddDocumentNoteUseCase(repo)

        assertThat(add("d1", "  Paid\n on 5 Oct  ")).isTrue()
        assertThat(add("d1", "   ")).isFalse()

        val note = repo.snapshot.single()
        assertThat(note.text).isEqualTo("Paid on 5 Oct")
        assertThat(note.source).isEqualTo(NoteSource.USER)
        assertThat(note.sourceRef).isNull()
    }

    @Test
    fun `a note longer than the limit is cut`() = runBlocking {
        AddDocumentNoteUseCase(repo)("d1", "z".repeat(1000))

        assertThat(repo.snapshot.single().text).hasLength(DocumentNoteText.MAX_CHARS)
    }

    @Test
    fun `editing keeps the source, and an emptied text deletes the note`() = runBlocking {
        repo.add("d1", "from the model", NoteSource.AI, "m1")
        val id = repo.snapshot.single().id
        val edit = EditDocumentNoteUseCase(repo)

        edit(id, "The user corrected it")
        assertThat(repo.snapshot.single().text).isEqualTo("The user corrected it")
        assertThat(repo.snapshot.single().source).isEqualTo(NoteSource.AI)

        edit(id, "  ")
        assertThat(repo.snapshot).isEmpty()
    }

    @Test
    fun `pinning and deleting`() = runBlocking {
        repo.add("d1", "first", NoteSource.USER)
        repo.add("d1", "second", NoteSource.USER)
        val first = repo.snapshot.first().id

        PinDocumentNoteUseCase(repo)(first, true)
        assertThat(ObserveDocumentNotesUseCase(repo)("d1").first().map { it.text }).containsExactly("first", "second").inOrder()

        DeleteDocumentNoteUseCase(repo)(first)
        assertThat(repo.snapshot.map { it.text }).containsExactly("second")
    }

    @Test
    fun `the slot is formatted, capped and follows the notes`() = runBlocking {
        val observe = ObserveDocumentMemoryUseCase(repo)
        assertThat(observe("d1").first()).isEmpty()

        repo.add("d1", "older", NoteSource.USER)
        repo.add("d1", "newer", NoteSource.AI)
        repo.add("other", "another document", NoteSource.USER)

        assertThat(observe("d1").first()).isEqualTo("- newer\n- older")
    }

    @Test
    fun `the generator asks the chat engine for one answer and is unavailable until a model is resident`() = runBlocking {
        val engine = FakeChatEngine()
        val generator = ChatEngineSessionNoteGenerator(engine)

        assertThat(generator.isAvailable()).isFalse()
        assertThat(generator.generate("system", "prompt")).isNull()
        // The port's default for an engine that cannot generate one-off: null, never an exception.
        assertThat(engine.generateOnce("system", AiRequest(prompt = "prompt"))).isNull()
    }
}
