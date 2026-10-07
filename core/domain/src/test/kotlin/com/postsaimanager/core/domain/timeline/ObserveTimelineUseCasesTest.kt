package com.postsaimanager.core.domain.timeline

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.testing.FakeEventRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ObserveTimelineUseCasesTest {

    private val events = FakeEventRepository()

    private fun event(id: String, doc: String, kind: String, date: Long, caseId: String?, people: List<String> = listOf("maria"), org: String = "jc") =
        ProfileEvent(id, doc, kind, date, date, id, personProfileIds = people, organisationProfileId = org, caseId = caseId)

    init {
        events.seedCases(
            Case("buergergeld", "jc", "Bürgergeld", status = CaseStatus.REJECTED, createdAt = 1),
            Case("strom", "stw", "Strom", createdAt = 2),
        )
        events.seedEvents(
            event("e1", "d1", EventKinds.APPLICATION_FILED, 10, "buergergeld"),
            event("e2", "d2", EventKinds.APPROVAL, 20, "buergergeld"),
            event("e3", "d3", EventKinds.REJECTION, 30, "buergergeld"),
            event("e4", "d4", EventKinds.PAYMENT_DEMAND, 25, "strom", org = "stw"),
            event("e5", "d5", EventKinds.INFORMATION, 5, null, people = listOf("maria", "me")),
            event("e6", "d6", EventKinds.INFORMATION, 6, "strom", people = listOf("me"), org = "stw"),
        )
    }

    @Test
    fun `a person's timeline is her matters, the most recently active first, and the events outside any matter`() = runTest {
        val timeline = ObserveTimelineForPersonUseCase(events)("maria").first()
        assertThat(timeline.cases.map { it.case.id }).containsExactly("buergergeld", "strom").inOrder()
        assertThat(timeline.cases.first().events.map { it.id }).containsExactly("e3", "e2", "e1").inOrder()
        assertThat(timeline.cases.last().events.map { it.id }).containsExactly("e4")
        assertThat(timeline.looseEvents.map { it.id }).containsExactly("e5")
    }

    @Test
    fun `an event concerning two people is on both timelines, once each`() = runTest {
        assertThat(ObserveTimelineForPersonUseCase(events)("me").first().let { it.cases.flatMap { c -> c.events } + it.looseEvents }.map { it.id })
            .containsExactly("e5", "e6")
    }

    @Test
    fun `an organisation's timeline covers every household person`() = runTest {
        val timeline = ObserveTimelineForOrganisationUseCase(events)("jc").first()
        assertThat(timeline.cases.map { it.case.id }).containsExactly("buergergeld")
        assertThat(timeline.looseEvents.map { it.id }).containsExactly("e5")
    }

    @Test
    fun `the case of a letter is its matter with all its events, newest first`() = runTest {
        val matter = ObserveCaseForDocumentUseCase(events)("d2").first()!!
        assertThat(matter.case.id).isEqualTo("buergergeld")
        assertThat(matter.case.status).isEqualTo(CaseStatus.REJECTED)
        assertThat(matter.events.map { it.id }).containsExactly("e3", "e2", "e1").inOrder()
    }

    @Test
    fun `a letter outside any matter has none`() = runTest {
        assertThat(ObserveCaseForDocumentUseCase(events)("d5").first()).isNull()
        assertThat(ObserveCaseForDocumentUseCase(events)("nobody").first()).isNull()
    }
}
