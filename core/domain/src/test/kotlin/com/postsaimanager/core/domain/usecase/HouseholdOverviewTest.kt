package com.postsaimanager.core.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.document.list.ConcernedPeopleTagsUseCase
import com.postsaimanager.core.domain.document.list.IdentityPartyNameResolver
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.timeline.EventKinds
import com.postsaimanager.core.domain.timeline.ObserveTimelineForPersonUseCase
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.model.ProfileType
import com.postsaimanager.core.model.Relationship
import com.postsaimanager.core.testing.FakeDocumentRepository
import com.postsaimanager.core.testing.FakeEventRepository
import com.postsaimanager.core.testing.FakeProfileRepository
import com.postsaimanager.core.testing.testDocument
import com.postsaimanager.core.testing.testProfile
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** The all-documents chat's card: what the overview says, from what other use cases decided, and that it stays within its cap. */
class HouseholdOverviewTest {

    private val today = LocalDate.of(2026, 10, 7)
    private val clock = Clock.fixed(today.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private fun day(iso: String) = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private val documents = FakeDocumentRepository()
    private val profiles = FakeProfileRepository()
    private val events = FakeEventRepository()

    private val overview = BuildHouseholdOverviewUseCase(
        profiles,
        ObserveDocumentListItemsUseCase(documents, IdentityPartyNameResolver(), clock, profiles, ConcernedPeopleTagsUseCase()),
        ObserveTimelineForPersonUseCase(events),
        clock,
    )

    private fun due(documentId: String, date: String) = ExtractedData(
        id = "f-$documentId", documentId = documentId, fieldName = "Due date", fieldValue = date, fieldType = ExtractedFieldType.DATE,
        confidence = 0.9f, slotKey = "due_date",
    )

    private fun event(id: String, doc: String, kind: String, date: String, title: String, caseId: String?, people: List<String>) =
        ProfileEvent(id, doc, kind, day(date), day(date), title, personProfileIds = people, organisationProfileId = "jc", caseId = caseId)

    private fun household() {
        profiles.seed(
            testProfile(id = "me", name = "Erika Mustermann", type = ProfileType.USER_SELF),
            testProfile(id = "omar", name = "Omar Mustermann", type = ProfileType.FAMILY_MEMBER, relationship = Relationship.CHILD),
            testProfile(id = "jc", name = "Jobcenter Musterstadt"),
            testProfile(id = "other", name = "Not Household", type = ProfileType.PERSON),
        )
    }

    @Test
    fun `the household persons, open cases with their latest event, deadlines and letters needing action`() = runTest {
        household()
        documents.seed(
            testDocument(id = "d1", title = "Bürgergeld Bescheid", extractionType = "official_letter").copy(concernedProfileIds = listOf("me")),
            testDocument(id = "d2", title = "Jahresabrechnung", extractionType = "bill").copy(
                concernedProfileIds = listOf("omar"), actionItems = listOf(ActionItem("pay")),
            ),
        )
        documents.seedExtracted("d2", due("d2", "15.10.2026"))
        events.seedCases(Case("bg", "jc", "Bürgergeld application", status = CaseStatus.OPEN, createdAt = 1))
        events.seedEvents(event("e1", "d1", EventKinds.APPROVAL, "2026-09-10", "Bürgergeld approved from 1 Sep", "bg", listOf("me")))

        val text = overview()

        assertThat(text).startsWith("\n## Household overview (2026-10-07)\n")
        assertThat(text).contains("People: Erika Mustermann (me), Omar Mustermann (child)")
        assertThat(text).doesNotContain("Not Household")
        assertThat(text).contains("Cases:\n- Erika Mustermann: Jobcenter Musterstadt, Bürgergeld application [open]. Latest: 2026-09-10 Approval, Bürgergeld approved from 1 Sep")
        // The people of a letter are the person tags the list shows (first names).
        assertThat(text).contains("Deadlines in the next 30 days:\n- 2026-10-15 Omar: Jahresabrechnung")
        assertThat(text).contains("Letters needing action:\n- Jahresabrechnung, for Omar")
    }

    @Test
    fun `a rejected case is listed with its status, after the open ones, and a closed one is not`() = runTest {
        household()
        documents.seed(
            testDocument(id = "d1", title = "Bescheid", extractionType = "official_letter"),
            testDocument(id = "d2", title = "Antrag", extractionType = "official_letter"),
            testDocument(id = "d3", title = "Old", extractionType = "official_letter"),
        )
        events.seedCases(
            Case("bg", "jc", "Bürgergeld application", status = CaseStatus.REJECTED, createdAt = 1),
            Case("op", "jc", "Wohngeld", status = CaseStatus.OPEN, createdAt = 2),
            Case("cl", "jc", "Old matter", status = CaseStatus.CLOSED, createdAt = 3),
        )
        events.seedEvents(
            event("e1", "d1", EventKinds.REJECTION, "2026-12-05", "Ablehnungsbescheid", "bg", listOf("me")),
            event("e2", "d2", EventKinds.APPLICATION_FILED, "2026-09-10", "Antrag", "op", listOf("me")),
            event("e3", "d3", EventKinds.INFORMATION, "2026-01-10", "Old", "cl", listOf("me")),
        )

        val text = overview()

        assertThat(text).contains("Bürgergeld application [rejected]. Latest: 2026-12-05 Rejection, Ablehnungsbescheid")
        assertThat(text.indexOf("Wohngeld [open]")).isLessThan(text.indexOf("Bürgergeld application [rejected]"))
        assertThat(text).doesNotContain("Old matter")
    }

    @Test
    fun `a deadline beyond 30 days or already past is not upcoming`() = runTest {
        household()
        documents.seed(
            testDocument(id = "d1", title = "Far", extractionType = "bill"),
            testDocument(id = "d2", title = "Past", extractionType = "bill"),
            testDocument(id = "d3", title = "Edge", extractionType = "bill"),
        )
        documents.seedExtracted("d1", due("d1", "07.11.2026"))
        documents.seedExtracted("d2", due("d2", "06.10.2026"))
        documents.seedExtracted("d3", due("d3", "06.11.2026"))
        events.seedCases(Case("bg", "jc", "Done matter", status = CaseStatus.REJECTED, createdAt = 1))
        events.seedEvents(event("e1", "d1", EventKinds.REJECTION, "2026-09-10", "Rejected", "bg", listOf("me")))

        val text = overview()

        assertThat(text).contains("- 2026-11-06 Edge")
        assertThat(text).doesNotContain("Far")
        assertThat(text).doesNotContain("Past")
    }

    @Test
    fun `a health letter and its events are neither named nor counted`() = runTest {
        household()
        documents.seed(
            testDocument(id = "d1", title = "Arztbrief Dr. Beispiel", extractionType = "medical").copy(actionItems = listOf(ActionItem("reply"))),
        )
        documents.seedExtracted("d1", due("d1", "10.10.2026"))
        events.seedCases(Case("hc", "jc", "Treatment", status = CaseStatus.OPEN, createdAt = 1))
        events.seedEvents(event("e1", "d1", EventKinds.APPOINTMENT, "2026-10-10", "Appointment at the clinic", "hc", listOf("me")))

        val text = overview()

        assertThat(text).doesNotContain("Arztbrief")
        assertThat(text).doesNotContain("clinic")
        assertThat(text).doesNotContain("Treatment")
        assertThat(text).doesNotContain("Deadlines")
        assertThat(text).doesNotContain("needing action")
    }

    @Test
    fun `a case two household persons share is listed once`() = runTest {
        household()
        documents.seed(testDocument(id = "d1", title = "Bescheid", extractionType = "official_letter"))
        events.seedCases(Case("bg", "jc", "Shared matter", status = CaseStatus.OPEN, createdAt = 1))
        events.seedEvents(event("e1", "d1", EventKinds.INFORMATION, "2026-09-10", "News", "bg", listOf("me", "omar")))

        assertThat(overview().lines().count { it.contains("Shared matter") }).isEqualTo(1)
    }

    @Test
    fun `no household and nothing due is no overview at all`() = runTest {
        profiles.seed(testProfile(id = "jc", name = "Jobcenter"))

        assertThat(overview()).isEmpty()
    }

    @Test
    fun `the text is capped, whole lines only, and the people and open cases are the last to go`() {
        val many = HouseholdOverview(
            today = today,
            people = listOf(HouseholdOverview.Person("Erika", "me")),
            cases = (1..8).map { HouseholdOverview.OpenCase("Erika", "Jobcenter", "Matter number $it with a rather long title", "2026-09-10 Approval, ${"x".repeat(80)}") },
            deadlines = (1..5).map { HouseholdOverview.Deadline(today.plusDays(it.toLong()), "Erika", "Stadtwerke", "Invoice $it") },
            actions = (1..5).map { HouseholdOverview.ActionLetter("Letter $it ${"y".repeat(60)}", "Sender", "Erika") },
        )

        val text = HouseholdOverviewFormat.format(many)

        assertThat(text.length).isAtMost(HouseholdOverviewFormat.MAX_CHARS)
        assertThat(text).contains("People: Erika (me)")
        assertThat(text).contains("Cases:")
        // The cap ends a section at a whole line, and nothing after it is added.
        assertThat(text.lines().filter { it.startsWith("- ") }).isNotEmpty()
        assertThat(text.lines().filter { it.startsWith("- Erika:") }.all { it.endsWith("x".repeat(80)) }).isTrue()
        assertThat(HouseholdOverviewFormat.format(many, maxChars = 100).length).isAtMost(100)
    }
}
