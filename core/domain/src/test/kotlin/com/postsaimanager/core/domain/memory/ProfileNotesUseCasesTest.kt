package com.postsaimanager.core.domain.memory

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.NoteSource
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeDocumentNoteRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Notes that belong to a household person or to the household: what the person's page lists, and the all-documents chat's memory slot. */
class ProfileNotesUseCasesTest {

    private val notes = FakeDocumentNoteRepository()
    private val profiles = FakeProfileRepository().apply {
        seed(
            testProfile(id = "me", name = "Erika Mustermann", type = ProfileType.USER_SELF),
            testProfile(id = "omar", name = "Omar Mustermann", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD),
            testProfile(id = "jc", name = "Jobcenter"),
        )
    }

    private val household = ObserveHouseholdMemoryUseCase(notes, profiles)

    @Test
    fun `a person's notes are theirs alone, pinned first, then the newest`() = runTest {
        notes.addOutsideDocument("omar", "Has swimming on Tuesdays", NoteSource.AI, "m1")
        val pinned = notes.addOutsideDocument("omar", "Allergic to penicillin, says the user", NoteSource.USER)
        notes.addOutsideDocument("me", "Works part-time", NoteSource.AI, "m2")
        notes.add("d1", "A note of a letter", NoteSource.USER)
        notes.setPinned(pinned.id, true)

        assertThat(ObserveProfileNotesUseCase(notes)("omar").first().map { it.text })
            .containsExactly("Allergic to penicillin, says the user", "Has swimming on Tuesdays").inOrder()
        assertThat(ObserveProfileMemoryUseCase(notes)("omar").first())
            .isEqualTo("- Allergic to penicillin, says the user\n- Has swimming on Tuesdays")
        assertThat(ObserveProfileMemoryUseCase(notes)("nobody").first()).isEmpty()
    }

    @Test
    fun `the user's own note on a person is stored for that person`() = runTest {
        assertThat(AddProfileNoteUseCase(notes)("omar", "  Needs   glasses ")).isTrue()
        assertThat(AddProfileNoteUseCase(notes)("omar", "   ")).isFalse()

        val note = notes.snapshot.single()
        assertThat(note.profileId).isEqualTo("omar")
        assertThat(note.documentId).isNull()
        assertThat(note.source).isEqualTo(NoteSource.USER)
        assertThat(note.text).isEqualTo("Needs glasses")
    }

    @Test
    fun `the household memory has every person's notes, named, and the household-wide ones, without the notes of a letter`() = runTest {
        notes.addOutsideDocument("omar", "Has swimming on Tuesdays", NoteSource.AI)
        notes.addOutsideDocument(null, "The family moves in March", NoteSource.AI)
        notes.addOutsideDocument("me", "Works part-time", NoteSource.AI)
        notes.add("d1", "A note of a letter", NoteSource.USER)

        val text = household().first()

        assertThat(text.lines()).containsExactly(
            "- Erika Mustermann: Works part-time",
            "- The family moves in March",
            "- Omar Mustermann: Has swimming on Tuesdays",
        ).inOrder()
        assertThat(text).doesNotContain("letter")
    }

    @Test
    fun `pinned notes come first and the slot keeps its cap`() = runTest {
        repeat(30) { notes.addOutsideDocument("omar", "Recent note number $it with some more words to fill the slot", NoteSource.AI) }
        val pinned = notes.addOutsideDocument("me", "Pinned long ago", NoteSource.USER)
        notes.setPinned(pinned.id, true)

        val text = household().first()

        assertThat(text.lines().first()).isEqualTo("- Erika Mustermann: Pinned long ago")
        assertThat(text.length).isAtMost(DocumentMemoryFormat.MAX_CHARS)
        assertThat(text.lines().size).isAtMost(DocumentMemoryFormat.MAX_NOTES)
    }

    @Test
    fun `a note about someone who is not a household person any more is not read, and a change shows at once`() = runTest {
        notes.addOutsideDocument("jc", "About an organisation", NoteSource.AI)
        notes.addOutsideDocument("omar", "Has swimming on Tuesdays", NoteSource.AI)
        assertThat(household().first()).isEqualTo("- Omar Mustermann: Has swimming on Tuesdays")

        notes.addOutsideDocument("omar", "Plays chess", NoteSource.AI)

        assertThat(household().first()).contains("Plays chess")
    }

    @Test
    fun `no notes is an empty slot`() = runTest {
        assertThat(household().first()).isEmpty()
    }
}
