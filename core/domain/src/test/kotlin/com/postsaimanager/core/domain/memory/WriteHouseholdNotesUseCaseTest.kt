package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.NoteSource
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeDocumentNoteRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * The notes of a finished all-documents chat: drafted and verified like a document's, then each note given to the household person the
 * model says it is about, or to the household when it says nobody. The person decision is a fake scorer here.
 */
class WriteHouseholdNotesUseCaseTest {

    private class FakeGenerator(var available: Boolean = true, var answer: String? = "NONE") : SessionNoteGenerator {
        var asked = 0
        var lastPrompt = ""
        override fun isAvailable() = available
        override suspend fun generate(system: String, prompt: String): String? {
            asked++
            lastPrompt = prompt
            return answer
        }
    }

    /** Decides by what the note says, standing in for the scores: a name in the note is the person it is about. */
    private class FakeDecider : NotePersonDecider {
        var failure: PamError? = null
        var answer: (note: String, persons: List<SubjectCandidate>) -> String? = { note, persons ->
            persons.firstOrNull { note.contains(it.name.substringBefore(' ')) }?.profileId
        }
        val asked = mutableListOf<Pair<String, List<String>>>()

        override suspend fun decide(note: String, persons: List<SubjectCandidate>): PamResult<String?> {
            asked += note to persons.map { it.profileId }
            failure?.let { return PamResult.Error(it) }
            return PamResult.Success(answer(note, persons))
        }
    }

    private val generator = FakeGenerator()
    private val decider = FakeDecider()
    private val notes = FakeDocumentNoteRepository()
    private val profiles = FakeProfileRepository().apply {
        seed(
            testProfile(id = "me", name = "Erika Mustermann", type = ProfileType.USER_SELF),
            testProfile(id = "omar", name = "Omar Mustermann", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD),
            testProfile(id = "jc", name = "Jobcenter"),
        )
    }
    private val useCase = WriteHouseholdNotesUseCase(generator, notes, profiles, SessionNoteVerifier(), decider)

    private fun user(text: String, id: String = "u1") = AiMessage(id, "c", MessageRole.USER, text, createdAt = 1)
    private fun write(vararg turns: AiMessage) = runBlocking { useCase(turns.toList()) }

    @Test
    fun `each note goes to the person the model decided it is about, and to the household when it decided nobody`() {
        generator.answer = "Omar has swimming on Tuesdays.\nErika works part-time.\nThe family moves in March."

        val written = write(user("Omar has swimming on Tuesdays, Erika works part-time and we move in March."))

        assertThat(written).isEqualTo(3)
        assertThat(notes.snapshot.map { it.text to it.profileId }).containsExactly(
            "Omar has swimming on Tuesdays." to "omar",
            "Erika works part-time." to "me",
            "The family moves in March." to null,
        ).inOrder()
        assertThat(notes.snapshot.map { it.documentId }.toSet()).containsExactly(null)
        assertThat(notes.snapshot.map { it.source }.toSet()).containsExactly(NoteSource.AI)
        assertThat(notes.snapshot.first().sourceRef).isEqualTo("u1")
    }

    @Test
    fun `only household persons are asked about, never an organisation`() {
        generator.answer = "Omar has swimming on Tuesdays."

        write(user("Omar has swimming on Tuesdays."))

        assertThat(decider.asked.single().second).containsExactly("me", "omar")
    }

    @Test
    fun `a person the model names who was not asked about is not trusted`() {
        generator.answer = "The Jobcenter wants a call."
        decider.answer = { _, _ -> "jc" }

        write(user("The Jobcenter wants a call."))

        assertThat(notes.snapshot.single().profileId).isNull()
    }

    @Test
    fun `a decision that cannot be made keeps the note for the household`() {
        generator.answer = "Omar has swimming on Tuesdays."
        decider.failure = PamError.InferenceError("no model")

        assertThat(write(user("Omar has swimming on Tuesdays."))).isEqualTo(1)
        assertThat(notes.snapshot.single().profileId).isNull()
    }

    @Test
    fun `the same verification as a document's, a number the user did not say and a repeat are dropped`() {
        notes.seed(DocumentNote("n0", null, "Omar has swimming on Tuesdays.", NoteSource.AI, 1, 1, profileId = "omar"))
        generator.answer = "Omar has swimming on Tuesdays!\nOmar pays 99 euros a month.\nErika works part-time."

        write(user("Omar has swimming on Tuesdays, and Erika works part-time."))

        assertThat(notes.snapshot.map { it.text }).containsExactly("Omar has swimming on Tuesdays.", "Erika works part-time.")
        assertThat(notes.snapshot.last().profileId).isEqualTo("me")
    }

    @Test
    fun `the question is about the household, with the household's notes already kept`() {
        notes.seed(DocumentNote("n0", null, "Erika works part-time", NoteSource.AI, 1, 1, profileId = "me"))
        // A note of a letter is not part of the household's memory.
        notes.seed(DocumentNote("n1", "d1", "Already paid on 5 Oct", NoteSource.USER, 1, 1))

        write(user("hello"))

        assertThat(generator.lastPrompt).contains("matter for ${SessionNotesFormat.ABOUT_HOUSEHOLD} later")
        assertThat(generator.lastPrompt).contains("Erika works part-time")
        assertThat(generator.lastPrompt).doesNotContain("Already paid")
    }

    @Test
    fun `no model, nothing the user said, or NONE writes nothing and the decider is not asked`() {
        generator.available = false
        generator.answer = "a note"
        assertThat(write(user("a chat"))).isEqualTo(0)
        assertThat(generator.asked).isEqualTo(0)

        generator.available = true
        assertThat(write(AiMessage("a1", "c", MessageRole.ASSISTANT, "Hello", createdAt = 1))).isEqualTo(0)
        generator.answer = "NONE"
        assertThat(write(user("hello"))).isEqualTo(0)

        assertThat(notes.snapshot).isEmpty()
        assertThat(decider.asked).isEmpty()
    }

    @Test
    fun `a household of nobody writes household notes without asking`() {
        val alone = WriteHouseholdNotesUseCase(generator, notes, FakeProfileRepository(), SessionNoteVerifier(), decider)
        generator.answer = "The family moves in March."

        assertThat(runBlocking { alone(listOf(user("We move in March."))) }).isEqualTo(1)

        assertThat(notes.snapshot.single().profileId).isNull()
        assertThat(decider.asked).isEmpty()
    }
}
