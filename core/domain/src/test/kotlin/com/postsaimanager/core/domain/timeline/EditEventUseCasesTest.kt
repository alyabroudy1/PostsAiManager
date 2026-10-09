package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.BaselineScores
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.EventUserState
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

/** Editing, deleting and adding events: the user's say stays through every re-read of the letter. */
class EditEventUseCasesTest {

    private class NoMatter : SameMatter {
        override suspend fun score(question: SameMatterQuestion): PamResult<BaselineScores> =
            PamResult.Success(BaselineScores(question.candidates.map { -4.0 }, 0.0))
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC)
    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val events = FakeEventRepository()
    private val refresh = RefreshCaseStatusUseCase(events)
    private val links = ResolveEventLinksUseCase(profiles, contacts)
    private val edit = EditEventUseCase(events, refresh)
    private val delete = DeleteEventUseCase(events, refresh)
    private val add = AddEventUseCase(documents, links, events, refresh, clock)
    private val record = RecordDocumentEventsUseCase(
        documents, links, events, DecideSameMatterUseCase(scoringFollowUps(sameMatter = NoMatter()), SameMatterProfile()), refresh, clock,
    )

    private val authority = testProfile(id = "jc", name = "Jobcenter Musterstadt", organization = "Jobcenter Musterstadt", type = ProfileType.AUTHORITY)

    private suspend fun letter() {
        documents.seed(testDocument(id = "d1", title = "Letter", createdAt = 1_000L))
        profiles.seed(authority)
        profiles.linkProfileToDocument("jc", "d1", ProfileRole.SENDER)
    }

    private suspend fun readOnce() {
        record("d1", EventReading(EventKinds.APPROVAL, "Bewilligt"))
    }

    private fun mine() = events.allEvents.filter { it.documentId == "d1" }

    @Test
    fun `editing the reading's event changes its kind, date and text and marks it edited`() = runTest {
        letter(); readOnce()
        val id = mine().single().id

        val result = edit(id, EventKinds.REJECTION, 5_000_000L, "  Abgelehnt  ")

        assertThat(result).isInstanceOf(PamResult.Success::class.java)
        val changed = mine().single()
        assertThat(changed.kind).isEqualTo(EventKinds.REJECTION)
        assertThat(changed.eventDate).isEqualTo(5_000_000L)
        assertThat(changed.title).isEqualTo("Abgelehnt")
        assertThat(changed.userState).isEqualTo(EventUserState.EDITED)
        assertThat(changed.source).isEqualTo(EventSource.DOCUMENT)
    }

    @Test
    fun `a re-read leaves the edited event as it is and writes none beside it`() = runTest {
        letter(); readOnce()
        edit(mine().single().id, EventKinds.REJECTION, 5_000_000L, "Abgelehnt")

        record("d1", EventReading(EventKinds.APPROVAL, "Bewilligt, again"))

        val only = mine().single()
        assertThat(only.title).isEqualTo("Abgelehnt")
        assertThat(only.kind).isEqualTo(EventKinds.REJECTION)
    }

    @Test
    fun `a deleted reading event is a tombstone that is not listed and a re-read does not bring it back`() = runTest {
        letter(); readOnce()
        val id = mine().single().id

        delete(id)
        record("d1", EventReading(EventKinds.APPROVAL, "Bewilligt"))

        assertThat(events.observeEventsForDocument("d1").first()).isEmpty()
        assertThat(mine().single().userState).isEqualTo(EventUserState.DELETED)
        assertThat(mine()).hasSize(1)
    }

    @Test
    fun `a deleted event that the user wrote is removed for good`() = runTest {
        letter()
        add("d1", EventKinds.INFORMATION, 1_000_000L, "Phoned the office")
        val id = mine().single().id

        delete(id)

        assertThat(mine()).isEmpty()
    }

    @Test
    fun `an added event is the user's, with the letter's links and matter, and survives a re-read`() = runTest {
        letter(); readOnce()
        val matter = mine().single().caseId

        add("d1", EventKinds.INFORMATION, 2_000_000L, "  Phoned the office ")
        record("d1", EventReading(EventKinds.APPROVAL, "Bewilligt"))

        val added = mine().single { it.source == EventSource.USER }
        assertThat(added.title).isEqualTo("Phoned the office")
        assertThat(added.eventDate).isEqualTo(2_000_000L)
        assertThat(added.organisationProfileId).isEqualTo("jc")
        assertThat(added.caseId).isEqualTo(matter)
        assertThat(mine().count { it.source == EventSource.DOCUMENT }).isEqualTo(1)
    }

    @Test
    fun `an added event without text is worded by its kind`() = runTest {
        letter()

        add("d1", EventKinds.INFORMATION, 2_000_000L, null)

        assertThat(mine().single().title).isEqualTo("Information")
    }

    @Test
    fun `an unknown kind, an empty text or a vanished event is refused`() = runTest {
        letter(); readOnce()
        val id = mine().single().id

        assertThat(edit(id, "no-such-kind", 1L, "x")).isInstanceOf(PamResult.Error::class.java)
        assertThat(edit(id, EventKinds.APPROVAL, 1L, "  ")).isInstanceOf(PamResult.Error::class.java)
        assertThat(edit("missing", EventKinds.APPROVAL, 1L, "x")).isInstanceOf(PamResult.Error::class.java)
        assertThat(add("d1", "no-such-kind", 1L, "x")).isInstanceOf(PamResult.Error::class.java)
        assertThat(delete("missing")).isInstanceOf(PamResult.Error::class.java)
    }

    @Test
    fun `the matter's status follows an edited event, and a matter whose events are all deleted goes`() = runTest {
        letter(); readOnce()
        val event = mine().single()
        assertThat(events.getCase(event.caseId!!)!!.status).isEqualTo(CaseStatus.APPROVED)

        edit(event.id, EventKinds.REJECTION, event.eventDate, "Abgelehnt")
        assertThat(events.getCase(event.caseId!!)!!.status).isEqualTo(CaseStatus.REJECTED)

        delete(event.id)
        assertThat(events.getCase(event.caseId!!)).isNull()
    }

    @Test
    fun `the policy lets a re-read write only over a reading nobody touched`() {
        fun e(source: EventSource, state: EventUserState) =
            ProfileEvent("e", "d1", "information", 1L, 1L, "t", source = source, userState = state)

        assertThat(EventEditPolicy.mayReplaceReading(emptyList())).isTrue()
        assertThat(EventEditPolicy.mayReplaceReading(listOf(e(EventSource.DOCUMENT, EventUserState.NONE)))).isTrue()
        assertThat(EventEditPolicy.mayReplaceReading(listOf(e(EventSource.DOCUMENT, EventUserState.EDITED)))).isFalse()
        assertThat(EventEditPolicy.mayReplaceReading(listOf(e(EventSource.DOCUMENT, EventUserState.DELETED)))).isFalse()
        assertThat(EventEditPolicy.deletionIsTombstone(e(EventSource.USER, EventUserState.NONE))).isFalse()
        assertThat(EventEditPolicy.deletionIsTombstone(e(EventSource.SYSTEM, EventUserState.NONE))).isTrue()
    }
}
