package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.list.ConcernedPeopleTagsUseCase
import com.postsaimanager.core.domain.document.list.IdentityPartyNameResolver
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.testing.FakeContactRepository
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEventRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testDocument
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class MechanicalEventsTest {

    private val now = Instant.parse("2026-10-07T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val contacts = FakeContactRepository()
    private val events = FakeEventRepository()
    private val writer = MechanicalEventWriter(events, ResolveEventLinksUseCase(profiles, contacts), clock)
    private val recordAction = RecordActionEventUseCase(documents, writer, clock)
    private val passedDeadlines = RecordPassedDeadlinesUseCase(
        ObserveDocumentListItemsUseCase(documents, IdentityPartyNameResolver(), clock, profiles, ConcernedPeopleTagsUseCase()),
        events, writer, clock,
    )

    private fun due(documentId: String, value: String) = ExtractedData(
        id = "due-$documentId", documentId = documentId, fieldName = "Due Date", fieldValue = value, fieldType = ExtractedFieldType.DATE,
        confidence = 0.9f, slotKey = "due_date",
    )

    /** A letter read earlier: a DOCUMENT event on a matter, for Maria and the Jobcenter. */
    private fun readLetter(id: String, dueDate: String? = null) {
        documents.seed(testDocument(id = id, title = "Letter $id", createdAt = 1))
        dueDate?.let { documents.seedExtracted(id, due(id, it)) }
        events.seedCases(Case("case-$id", "jc", "Matter $id", createdAt = 1))
        events.seedEvents(
            ProfileEvent(
                "doc-$id", id, EventKinds.PAYMENT_DEMAND, 1, 1, "Demand", personProfileIds = listOf("maria"), organisationProfileId = "jc",
                contactId = null, caseId = "case-$id",
            ),
        )
    }

    // ── a confirmed action ──

    @Test
    fun `a confirmed reminder, e-mail and calendar entry each write an ACTION event on the letter's matter, with its links`() = runTest {
        readLetter("d1")

        recordAction("d1", AgentAction.ScheduleReminder(LocalDateTime.of(2026, 10, 8, 9, 0), "Pay the invoice", "d1"))
        recordAction("d1", AgentAction.SendEmail("amt@example.org", "Re: Aktenzeichen 123", "Hello"))
        recordAction("d1", AgentAction.CreateCalendarEvent("Termin Jobcenter", LocalDateTime.of(2026, 10, 20, 10, 0), null, ""))

        val actions = events.allEvents.filter { it.source == EventSource.ACTION }
        assertThat(actions.map { it.kind }).containsExactly(EventKinds.REMINDER_SET, EventKinds.EMAIL_SENT, EventKinds.CALENDAR_ENTRY).inOrder()
        assertThat(actions.map { it.title }).containsExactly("Pay the invoice", "Re: Aktenzeichen 123", "Termin Jobcenter").inOrder()
        assertThat(actions.map { it.caseId }.distinct()).containsExactly("case-d1")
        assertThat(actions.all { it.personProfileIds == listOf("maria") && it.organisationProfileId == "jc" }).isTrue()
        assertThat(actions.all { it.eventDate == now.toEpochMilli() && it.recordedAt == now.toEpochMilli() }).isTrue()
    }

    @Test
    fun `reading the clock records nothing, and neither does a missing letter`() = runTest {
        readLetter("d1")
        recordAction("d1", AgentAction.GetDateTime)
        recordAction("nobody", AgentAction.ScheduleReminder(LocalDateTime.of(2026, 10, 8, 9, 0), "x", null))
        assertThat(events.allEvents.filter { it.source == EventSource.ACTION }).isEmpty()
    }

    @Test
    fun `an action on a letter that has no event yet is still written, with no matter`() = runTest {
        documents.seed(testDocument(id = "d1", title = "Letter", createdAt = 1))
        recordAction("d1", AgentAction.SendEmail("a@b.example", "", "x"))
        val event = events.allEvents.single()
        assertThat(event.caseId).isNull()
        // An empty subject falls back to the kind's English label.
        assertThat(event.title).isEqualTo("Email prepared")
    }

    // ── deadline passed ──

    @Test
    fun `a due date that passed with no action writes one SYSTEM event on the matter, and only once`() = runTest {
        readLetter("d1", dueDate = "30.09.2026")

        assertThat(passedDeadlines()).isEqualTo(1)
        assertThat(passedDeadlines()).isEqualTo(0)

        val system = events.allEvents.filter { it.source == EventSource.SYSTEM }.single()
        assertThat(system.kind).isEqualTo(EventKinds.DEADLINE_PASSED)
        assertThat(system.documentId).isEqualTo("d1")
        assertThat(system.caseId).isEqualTo("case-d1")
        assertThat(system.eventDate).isEqualTo(LocalDate.of(2026, 9, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        assertThat(system.personProfileIds).containsExactly("maria")
    }

    @Test
    fun `a due date that has not passed, is today, or has an action done writes nothing`() = runTest {
        readLetter("future", dueDate = "01.11.2026")
        readLetter("today", dueDate = "07.10.2026")
        readLetter("done", dueDate = "30.09.2026")
        recordAction("done", AgentAction.ScheduleReminder(LocalDateTime.of(2026, 10, 1, 9, 0), "Pay", "done"))

        assertThat(passedDeadlines()).isEqualTo(0)
        assertThat(events.allEvents.filter { it.source == EventSource.SYSTEM }).isEmpty()
    }

    @Test
    fun `a letter with no due date is never overdue`() = runTest {
        readLetter("d1")
        assertThat(passedDeadlines()).isEqualTo(0)
    }
}
