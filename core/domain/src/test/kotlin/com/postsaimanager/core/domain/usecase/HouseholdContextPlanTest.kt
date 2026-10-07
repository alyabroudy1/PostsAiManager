package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.memory.ObserveDocumentMemoryUseCase
import com.postsaimanager.core.domain.memory.ObserveHouseholdMemoryUseCase
import com.postsaimanager.core.model.AiMessage
import com.postsaimanager.core.model.MessageRole
import com.postsaimanager.core.model.NoteSource
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeDocumentNoteRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.letterContactsFor
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Plan 16, A3: the all-documents chat is built from the same layers as a document's, with the overview as its card and the household's
 * notes as its memory slot. The session boundary and the continuity tail work as in A1.
 */
class HouseholdContextPlanTest {

    private val documents = FakeDocumentRepository().apply { seed(testDocument(id = "d1", title = "Strom Rechnung")) }
    private val profiles = FakeProfileRepository().apply {
        seed(
            testProfile(id = "me", name = "Erika Mustermann", type = ProfileType.USER_SELF),
            testProfile(id = "omar", name = "Omar Mustermann", type = ProfileType.FAMILY_MEMBER),
        )
    }
    private val notes = FakeDocumentNoteRepository()
    private val buildChatContext = BuildChatContextUseCase(documents, profiles, letterContactsFor(profiles))

    private var overview = "\n## Household overview (2026-10-07)\nPeople: Erika Mustermann (me), Omar Mustermann (child)\n"
    private var overviewFails = false

    private val buildModelContext = BuildModelContextUseCase(
        buildChatContext,
        { documentId -> ObserveDocumentMemoryUseCase(notes)(documentId).first() },
        { ObserveHouseholdMemoryUseCase(notes, profiles)().first() },
        { if (overviewFails) error("the overview could not be built") else overview },
    )

    private fun message(i: Int, role: MessageRole, text: String) = AiMessage("m$i", "conv-all", role, text, createdAt = i.toLong())

    private suspend fun plan(transcript: List<AiMessage> = emptyList(), documentId: String? = null, systemPrompt: String? = null) =
        buildModelContext(documentId, contextTokens = 4096, historyTokens = 4096, transcript = transcript, systemPrompt = systemPrompt)

    @Test
    fun `the card of the chat without a document is the household overview, then the memory slot of the household`() = runTest {
        notes.addOutsideDocument("omar", "Has swimming on Tuesdays", NoteSource.AI)
        notes.addOutsideDocument(null, "The family moves in March", NoteSource.AI)

        val plan = plan()
        val text = plan.card.text

        assertThat(plan.card.retrievalMode).isTrue()
        assertThat(text).contains("## Household overview (2026-10-07)")
        assertThat(text).contains("## Your documents\n- Strom Rechnung")
        assertThat(text).contains("## What you remember about the household\n- The family moves in March\n- Omar Mustermann: Has swimming on Tuesdays\n")
        assertThat(text.indexOf("Household overview")).isLessThan(text.indexOf("What you remember about the household"))
        assertThat(plan.documentMemoryChars).isGreaterThan(0)
        assertThat(plan.cardChars).isEqualTo(text.length - plan.documentMemoryChars)
    }

    @Test
    fun `the slot keeps its cap, whole notes only`() = runTest {
        repeat(40) { notes.addOutsideDocument("omar", "Recent note number $it with a few more words to fill the slot up", NoteSource.AI) }

        val plan = plan()

        assertThat(plan.documentMemoryChars).isAtMost(BuildModelContextUseCase.MEMORY_CAP_CHARS + BuildModelContextUseCase.HOUSEHOLD_MEMORY_HEADER.length)
        val slot = plan.card.text.substringAfter("What you remember about the household\n")
        assertThat(slot.lines().filter { it.isNotBlank() }.all { it.startsWith("- Omar Mustermann: Recent note number") }).isTrue()
    }

    @Test
    fun `a document chat keeps the notes of its document and gets neither the overview nor the household's notes`() = runTest {
        notes.addOutsideDocument("omar", "Has swimming on Tuesdays", NoteSource.AI)
        notes.add("d1", "Already paid on 5 Oct, says the user", NoteSource.USER)

        val text = plan(documentId = "d1").card.text

        assertThat(text).contains("What you remember about this document")
        assertThat(text).contains("Already paid on 5 Oct")
        assertThat(text).doesNotContain("Household overview")
        assertThat(text).doesNotContain("swimming")
    }

    @Test
    fun `the session boundary is as in a document chat, only the last exchange is replayed and the notes come from the store`() = runTest {
        notes.addOutsideDocument("me", "Works part-time", NoteSource.AI)
        val transcript = (0 until 20).flatMap { n ->
            listOf(message(2 * n, MessageRole.USER, "question $n"), message(2 * n + 1, MessageRole.ASSISTANT, "answer $n"))
        }

        val plan = plan(transcript)

        assertThat(plan.tail.map { it.content }).containsExactly("question 19", "answer 19").inOrder()
        assertThat(plan.card.text).contains("Works part-time")
        assertThat(plan.card.text).doesNotContain("question 3")
    }

    @Test
    fun `an overview that cannot be built leaves the chat without one, not without a chat`() = runTest {
        overviewFails = true
        notes.addOutsideDocument("me", "Works part-time", NoteSource.AI)

        val text = plan().card.text

        assertThat(text).doesNotContain("Household overview")
        assertThat(text).contains("Works part-time")
        assertThat(text).contains("No document is open")
    }

    @Test
    fun `a caller's own prompt replaces the card and brings neither overview nor notes`() = runTest {
        notes.addOutsideDocument("me", "Works part-time", NoteSource.AI)

        val plan = plan(systemPrompt = "Custom prompt")

        assertThat(plan.card.text).isEqualTo("Custom prompt")
        assertThat(plan.documentMemoryChars).isEqualTo(0)
    }
}
