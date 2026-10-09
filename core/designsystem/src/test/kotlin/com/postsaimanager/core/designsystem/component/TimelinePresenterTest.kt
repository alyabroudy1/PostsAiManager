package com.postsaimanager.core.designsystem.component

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ProfileEvent
import org.junit.jupiter.api.Test

class TimelinePresenterTest {

    private val day = 86_400_000L

    private fun event(
        id: String,
        doc: String,
        kind: String,
        days: Long,
        title: String = "T-$id",
        source: EventSource = EventSource.DOCUMENT,
        persons: List<String> = listOf("maria"),
    ) = ProfileEvent(
        id = id, documentId = doc, kind = kind, eventDate = days * day, recordedAt = days, title = title,
        personProfileIds = persons, organisationProfileId = "jc", caseId = "k1", source = source,
    )

    private fun case(id: String = "k1", title: String = "Bürgergeld", status: CaseStatus = CaseStatus.OPEN) =
        Case(id, "jc", title, status = status, createdAt = 1)

    private val names = mapOf("jc" to "Jobcenter", "maria" to "Maria", "omar" to "Omar")

    private fun present(cases: List<TimelineCaseInput>, loose: List<ProfileEvent> = emptyList(), forOrganisation: Boolean = false) =
        TimelinePresenter.present(cases, loose, names::get, names::get, forOrganisation)

    @Test
    fun `a matter of several events is a card with its events newest first`() {
        val events = listOf(event("e1", "d1", "application_filed", 10), event("e3", "d3", "rejection", 30), event("e2", "d2", "approval", 20))

        val ui = present(listOf(TimelineCaseInput(case(status = CaseStatus.REJECTED), events)))

        val card = ui.cases.single()
        assertThat(card.events.map { it.id }).containsExactly("e3", "e2", "e1").inOrder()
        assertThat(card.latest.kindId).isEqualTo("rejection")
        assertThat(card.letterCount).isEqualTo(3)
        assertThat(card.organisationName).isEqualTo("Jobcenter")
        assertThat(card.personNames).isEmpty()
        assertThat(ui.other).isEmpty()
    }

    @Test
    fun `an action shares the letter it was done on and does not add a letter`() {
        val events = listOf(
            event("e1", "d1", "approval", 10),
            event("e2", "d1", "reminder_set", 11, source = EventSource.ACTION),
            event("e3", "d2", "rejection", 30),
        )

        assertThat(present(listOf(TimelineCaseInput(case(), events))).cases.single().letterCount).isEqualTo(2)
    }

    @Test
    fun `a matter of one event with its generated title is shown as a plain event`() {
        val lone = event("e1", "d1", "information", 10, title = "Mitteilung")

        val ui = present(listOf(TimelineCaseInput(case(title = "Mitteilung"), listOf(lone))))

        assertThat(ui.cases).isEmpty()
        assertThat(ui.other.map { it.id }).containsExactly("e1")
        assertThat(ui.other.single().context).isEqualTo("Jobcenter")
    }

    @Test
    fun `a renamed matter of one event stays a card`() {
        val lone = event("e1", "d1", "information", 10, title = "Mitteilung")

        val ui = present(listOf(TimelineCaseInput(case(title = "Meine Mitteilung"), listOf(lone))))

        assertThat(ui.cases.single().title).isEqualTo("Meine Mitteilung")
        assertThat(ui.other).isEmpty()
    }

    @Test
    fun `plain events and loose events share Other, newest first`() {
        val lone = event("e1", "d1", "information", 10, title = "Mitteilung")
        val loose = event("e9", "d9", "payment_demand", 50).copy(caseId = null)

        val ui = present(listOf(TimelineCaseInput(case(title = "Mitteilung"), listOf(lone))), listOf(loose))

        assertThat(ui.other.map { it.id }).containsExactly("e9", "e1").inOrder()
    }

    @Test
    fun `cards are ordered by their latest event`() {
        val older = TimelineCaseInput(case("k1"), listOf(event("a1", "d1", "approval", 10), event("a2", "d2", "rejection", 20)))
        val newer = TimelineCaseInput(case("k2", "Miete"), listOf(event("b1", "d3", "payment_demand", 40), event("b2", "d4", "payment_reminder", 60)))

        assertThat(present(listOf(older, newer)).cases.map { it.caseId }).containsExactly("k2", "k1").inOrder()
    }

    @Test
    fun `an organisation's cards name the persons instead of the sender`() {
        val events = listOf(
            event("e1", "d1", "approval", 10, persons = listOf("maria")),
            event("e2", "d2", "rejection", 20, persons = listOf("maria", "omar")),
        )

        val card = present(listOf(TimelineCaseInput(case(), events)), forOrganisation = true).cases.single()

        assertThat(card.personNames).containsExactly("Maria", "Omar").inOrder()
        assertThat(card.organisationName).isNull()
    }

    @Test
    fun `a letter's own matter lists all events and opens on its first person`() {
        val events = listOf(event("e1", "d1", "approval", 10), event("e2", "d2", "rejection", 20))

        val ui = TimelinePresenter.documentCase("d1", case(status = CaseStatus.REJECTED), events)!!

        assertThat(ui.events.map { it.id }).containsExactly("e2", "e1").inOrder()
        assertThat(ui.openProfileId).isEqualTo("maria")
        assertThat(ui.currentDocumentId).isEqualTo("d1")
    }

    @Test
    fun `a letter without a person opens its matter on the sender`() {
        val events = listOf(event("e1", "d1", "approval", 10, persons = emptyList()), event("e2", "d2", "rejection", 20, persons = emptyList()))

        assertThat(TimelinePresenter.documentCase("d1", case(), events)!!.openProfileId).isEqualTo("jc")
    }

    @Test
    fun `a letter alone in an unrenamed matter has no row`() {
        val lone = event("e1", "d1", "information", 10, title = "Mitteilung")

        assertThat(TimelinePresenter.documentCase("d1", case(title = "Mitteilung"), listOf(lone))).isNull()
        assertThat(TimelinePresenter.documentCase("d1", case(title = "Umbenannt"), listOf(lone))).isNotNull()
    }

    @Test
    fun `the letter's own page shows even a lone unrenamed matter, so it can be renamed or left`() {
        val lone = event("e1", "d1", "information", 10, title = "Mitteilung")

        assertThat(TimelinePresenter.documentCase("d1", case(title = "Mitteilung"), listOf(lone), showPlain = true)).isNotNull()
    }
}
