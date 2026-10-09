package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseLinkSource
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.CaseStatusSource
import com.postsaimanager.core.model.CaseTitleSource
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEventRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.scoringFollowUps
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Moving a letter to another matter, a new one or none, and setting a matter's status by hand: a re-read leaves both alone. */
class EditCaseUseCasesTest {

    /** The same-matter question: always "same as the first candidate", so a re-read that regroups would put the letter back. */
    private class AlwaysFirst : SameMatter {
        override suspend fun score(question: SameMatterQuestion): PamResult<BaselineScores> =
            PamResult.Success(BaselineScores(question.candidates.mapIndexed { i, _ -> if (i == 0) 4.0 else -4.0 }, 0.0))
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val events = FakeEventRepository()
    private val refresh = RefreshCaseStatusUseCase(events)
    private val move = MoveDocumentToCaseUseCase(documents, events, refresh, clock)
    private val record = RecordDocumentEventsUseCase(
        documents, ResolveEventLinksUseCase(profiles, contacts), events,
        DecideSameMatterUseCase(scoringFollowUps(sameMatter = AlwaysFirst()), SameMatterProfile()), refresh, clock,
    )
    private val setStatus = SetCaseStatusUseCase(events)

    private val authority = testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt", type = ProfileType.AUTHORITY)

    private fun case(id: String, title: String, status: CaseStatus = CaseStatus.OPEN) =
        Case(id = id, organisationProfileId = "jc", title = title, status = status, createdAt = 1L)

    private fun event(id: String, documentId: String, caseId: String?, kind: String = EventKinds.INFORMATION, date: Long = 10L) = ProfileEvent(
        id = id, documentId = documentId, kind = kind, eventDate = date, recordedAt = date, title = "Event $id", organisationProfileId = "jc",
        caseId = caseId, source = EventSource.DOCUMENT,
    )

    private suspend fun seedLetter(id: String) {
        documents.seed(testDocument(id = id, title = "Letter $id"))
        if (profiles.getProfileById("jc") is PamResult.Error) profiles.seed(authority)
        profiles.linkProfileToDocument("jc", id, ProfileRole.SENDER)
    }

    private suspend fun document(id: String) = (documents.getDocumentById(id) as PamResult.Success).data

    @Test
    fun `moving to another matter moves every event of the letter, marks the choice and deletes the matter left empty`() = runTest {
        seedLetter("d1")
        events.seedCases(case("c1", "First matter"), case("c2", "Second matter"))
        events.seedEvents(event("e1", "d1", "c1"), event("e2", "d2", "c2"))

        val result = move("d1", CaseTarget.Existing("c2"))

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        assertThat(events.allEvents.single { it.documentId == "d1" }.caseId).isEqualTo("c2")
        assertThat(document("d1").caseLinkSource).isEqualTo(CaseLinkSource.USER)
        assertThat(events.allCases.map { it.id }).containsExactly("c2")
    }

    @Test
    fun `moving to a new matter creates it for the letter's sender, named as typed`() = runTest {
        seedLetter("d1")
        events.seedCases(case("c1", "First matter"))
        events.seedEvents(event("e1", "d1", "c1"), event("e2", "d2", "c1"))

        move("d1", CaseTarget.New("  Housing benefit 2026  "))

        val moved = events.allEvents.single { it.documentId == "d1" }
        val created = events.allCases.single { it.id == moved.caseId }
        assertThat(created.title).isEqualTo("Housing benefit 2026")
        assertThat(created.titleSource).isEqualTo(CaseTitleSource.USER)
        assertThat(created.organisationProfileId).isEqualTo("jc")
        assertThat(events.allEvents.single { it.documentId == "d2" }.caseId).isEqualTo("c1")
    }

    @Test
    fun `a new matter with no name is titled by the letter's event`() = runTest {
        seedLetter("d1")
        events.seedEvents(event("e1", "d1", null))

        move("d1", CaseTarget.New())

        val created = events.allCases.single()
        assertThat(created.title).isEqualTo("Event e1")
        assertThat(created.titleSource).isEqualTo(CaseTitleSource.AUTO)
    }

    @Test
    fun `moving to no matter takes the letter out of it`() = runTest {
        seedLetter("d1")
        events.seedCases(case("c1", "First matter"))
        events.seedEvents(event("e1", "d1", "c1"), event("e2", "d2", "c1"))

        move("d1", CaseTarget.None)

        assertThat(events.allEvents.single { it.documentId == "d1" }.caseId).isNull()
        assertThat(events.allCases.map { it.id }).containsExactly("c1")
        assertThat(document("d1").caseLinkSource).isEqualTo(CaseLinkSource.USER)
    }

    @Test
    fun `a letter without an event or a matter that does not exist is refused`() = runTest {
        seedLetter("d1")

        assertThat(move("d1", CaseTarget.None)).isInstanceOf(PamResult.Error::class.java)

        events.seedEvents(event("e1", "d1", null))
        val missing = move("d1", CaseTarget.Existing("nope"))
        assertThat((missing as PamResult.Error).error).isInstanceOf(PamError.ValidationError::class.java)
    }

    @Test
    fun `reading the letter again leaves it where the user put it`() = runTest {
        seedLetter("d1")
        events.seedCases(case("c1", "First matter"))
        events.seedEvents(event("e1", "d1", "c1"), event("e0", "d0", "c1"))
        move("d1", CaseTarget.None)

        // The same-matter question would say "the first matter" again: a re-read regroups an untouched letter, never this one.
        record("d1", EventReading(EventKinds.INFORMATION, "Information"))

        assertThat(events.allEvents.single { it.documentId == "d1" }.caseId).isNull()
    }

    @Test
    fun `a letter the user did not move is still grouped by the re-read`() = runTest {
        seedLetter("d1")
        events.seedCases(case("c1", "First matter"))
        events.seedEvents(event("e0", "d0", "c1"))

        record("d1", EventReading(EventKinds.INFORMATION, "Information"))

        assertThat(events.allEvents.single { it.documentId == "d1" }.caseId).isEqualTo("c1")
    }

    // ── status ──

    @Test
    fun `a status the user set is kept when the events say otherwise`() = runTest {
        events.seedCases(case("c1", "Matter"))
        events.seedEvents(event("e1", "d1", "c1", kind = EventKinds.APPROVAL))

        setStatus("c1", CaseStatus.CLOSED)
        refresh("c1")

        val stored = events.getCase("c1")!!
        assertThat(stored.status).isEqualTo(CaseStatus.CLOSED)
        assertThat(stored.statusSource).isEqualTo(CaseStatusSource.USER)
    }

    @Test
    fun `handing the status back to automatic derives it from the events again`() = runTest {
        events.seedCases(case("c1", "Matter"))
        events.seedEvents(event("e1", "d1", "c1", kind = EventKinds.APPROVAL))
        setStatus("c1", CaseStatus.CLOSED)

        setStatus("c1", null)

        val stored = events.getCase("c1")!!
        assertThat(stored.status).isEqualTo(CaseStatus.APPROVED)
        assertThat(stored.statusSource).isEqualTo(CaseStatusSource.AUTO)
        // And from then on a new event moves it again.
        events.addEvent(event("e2", "d2", "c1", kind = EventKinds.REJECTION, date = 20L))
        refresh("c1")
        assertThat(events.getCase("c1")!!.status).isEqualTo(CaseStatus.REJECTED)
    }

    @Test
    fun `the choices are the sender's matters and the one the letter is in`() = runTest {
        events.seedCases(case("c1", "First matter"), case("c2", "Second matter"))
        events.seedEvents(event("e1", "d1", "c2"))

        val choices = ObserveCaseChoicesUseCase(events)("d1").first()

        assertThat(choices!!.currentCaseId).isEqualTo("c2")
        assertThat(choices.cases.map { it.id }).containsExactly("c1", "c2")
        assertThat(ObserveCaseChoicesUseCase(events)("unknown").first()).isNull()
    }
}
