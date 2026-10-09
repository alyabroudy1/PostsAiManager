package com.postsaimanager.core.domain.document.people

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.form.SubjectCandidate
import com.postsaimanager.core.domain.timeline.ResolveEventLinksUseCase
import com.postsaimanager.core.domain.timeline.SyncEventLinksUseCase
import com.postsaimanager.core.model.ConcernedSource
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEventRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.scoringFollowUps
import com.postsaimanager.core.testing.testDocument
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Who a letter is for, set by the user: the people check, its reset and its queueing never change it afterwards. */
class EditedConcernedPeopleTest {

    private class FakeConcerned(var answer: Set<String> = emptySet()) : ConcernedPeople {
        val asked = mutableListOf<List<SubjectCandidate>>()

        override suspend fun decide(letter: String, members: List<SubjectCandidate>): PamResult<Set<String>> {
            asked += members
            return PamResult.Success(answer)
        }
    }

    private val profiles = FakeProfileRepository()
    private val documents = FakeDocumentRepository()
    private val events = FakeEventRepository()
    private val concerned = FakeConcerned()
    private val processor = mockk<DocumentProcessor>(relaxed = true)
    private val decide = DecideConcernedPeopleUseCase(profiles, documents, scoringFollowUps(concerned = concerned))
    private val queue = QueueConcernedPeopleCheckUseCase(documents, processor)
    private val setConcerned = SetConcernedPeopleUseCase(documents, SyncEventLinksUseCase(documents, ResolveEventLinksUseCase(profiles, FakeContactRepository()), events))

    private fun profile(id: String, name: String) =
        Profile(id = id, kind = ProfileType.FAMILY_MEMBER.kind, householdRole = ProfileType.FAMILY_MEMBER.householdRole, name = name, createdAt = 0L, modifiedAt = 0L)

    private fun seedDocument(text: String = "Rechnung fuer Maria") {
        documents.seed(testDocument(id = "d1", extractorVersion = "extraction-v2-10").copy(concernedProfileIds = listOf("old")))
        documents.seedPages("d1", DocumentPage(id = "p1", documentId = "d1", pageNumber = 1, imagePath = "/p.jpg", ocrText = text))
    }

    private suspend fun stored() = (documents.getDocumentById("d1") as PamResult.Success).data

    @Test
    fun `setting the people stores them as the user's, without duplicates`() = runTest {
        seedDocument()

        setConcerned("d1", listOf("maria", "me", "maria"))

        assertThat(stored().concernedProfileIds).containsExactly("maria", "me").inOrder()
        assertThat(stored().concernedSource).isEqualTo(ConcernedSource.USER)
    }

    @Test
    fun `setting nobody is a decision too`() = runTest {
        seedDocument()

        setConcerned("d1", emptyList())

        assertThat(stored().concernedProfileIds).isEmpty()
        assertThat(stored().concernedSource).isEqualTo(ConcernedSource.USER)
    }

    @Test
    fun `the people check does not ask the model about a letter whose people the user set, and writes nothing`() = runTest {
        profiles.seed(profile("maria", "Maria Ahmed"))
        seedDocument()
        setConcerned("d1", listOf("me"))
        concerned.answer = setOf("maria")

        val result = decide("d1", "Rechnung fuer Maria")

        assertThat(concerned.asked).isEmpty()
        assertThat((result as PamResult.Success).data).containsExactly("me")
        assertThat(stored().concernedProfileIds).containsExactly("me")
    }

    @Test
    fun `a check that was already running cannot write over the user's list`() = runTest {
        seedDocument()
        setConcerned("d1", listOf("me"))

        documents.setConcernedProfiles("d1", listOf("maria"))

        assertThat(stored().concernedProfileIds).containsExactly("me")
    }

    @Test
    fun `a new or renamed profile neither resets nor queues a letter whose people the user set`() = runTest {
        seedDocument("Rechnung fuer Maria Ahmed")
        setConcerned("d1", listOf("me"))

        val queued = queue(profile("maria", "Maria Ahmed"))

        assertThat(queued).isEqualTo(0)
        assertThat(stored().concernedProfileIds).containsExactly("me")
        coVerify(exactly = 0) { processor.enqueuePeopleCheck(any()) }
    }

    @Test
    fun `a letter the model decided is still reset and queued as before`() = runTest {
        seedDocument("Rechnung fuer Maria Ahmed")

        val queued = queue(profile("maria", "Maria Ahmed"))

        assertThat(queued).isEqualTo(1)
        assertThat(stored().concernedProfileIds).isNull()
        coVerify(exactly = 1) { processor.enqueuePeopleCheck("d1") }
    }

    @Test
    fun `the letter's timeline events follow the new people`() = runTest {
        seedDocument()
        events.seedEvents(ProfileEvent("e1", "d1", "information", 1L, 1L, "t", personProfileIds = listOf("old"), source = EventSource.DOCUMENT))

        setConcerned("d1", listOf("maria"))

        assertThat(events.allEvents.single().personProfileIds).containsExactly("maria")
    }
}
