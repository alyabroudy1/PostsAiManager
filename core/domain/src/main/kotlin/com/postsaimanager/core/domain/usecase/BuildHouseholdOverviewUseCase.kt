package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.timeline.CaseHistory
import com.postsaimanager.core.domain.timeline.EventKind
import com.postsaimanager.core.domain.timeline.EventKinds
import com.postsaimanager.core.domain.timeline.ObserveTimelineForPersonUseCase
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.model.Relationship
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * The card of the all-documents chat: a short overview of the household ([HouseholdOverviewFormat]), built from what other use cases
 * already decided. It decides nothing:
 *  - the people are the profiles with a household role;
 *  - the matters are each person's cases that are not closed ([ObserveTimelineForPersonUseCase]), the open ones first, each with its
 *    derived status and its newest event (a rejected matter is listed: its objection deadline is still open);
 *  - the deadlines are the stored due dates of the document list ([ObserveDocumentListItemsUseCase]) in the next
 *    [HouseholdOverviewFormat.DEADLINE_DAYS] days;
 *  - the letters needing action are the ones with stored action items.
 *
 * Only what the all-documents chat may see goes in ([ObserveChatVisibleDocumentsUseCase.isChatVisible]): a health letter, or an event
 * of one, is neither named nor counted here. A matter whose every event is hidden is not listed.
 */
class BuildHouseholdOverviewUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val documents: ObserveDocumentListItemsUseCase,
    private val timelines: ObserveTimelineForPersonUseCase,
    private val clock: Clock,
) {

    /** The overview as the card's text (empty when the household has no person and nothing is due), capped at [HouseholdOverviewFormat.MAX_CHARS]. */
    suspend operator fun invoke(): String = HouseholdOverviewFormat.format(overview())

    suspend fun overview(): HouseholdOverview {
        val today = LocalDate.now(clock)
        val everyone = profiles.getProfiles().first()
        val household = everyone.filter { it.isManaged }.sortedByDescending { it.isSelf }
        val names = everyone.associate { it.id to it.name }
        val items = documents().first().filter { ObserveChatVisibleDocumentsUseCase.isChatVisible(it.document) }
        val visible = items.mapTo(mutableSetOf()) { it.id }

        val seen = mutableSetOf<String>()
        val cases = household.flatMap { person ->
            // A matter that was decided (rejected, approved) is listed with its status, not left out: what follows from a decision (an
            // objection deadline, a payment) is what "what is open for her" is about. Only a matter closed for good is left out.
            timelines(person.id).first().cases
                .filter { it.case.status != CaseStatus.CLOSED && seen.add(it.case.id) }
                .sortedBy { it.case.status != CaseStatus.OPEN }
                .mapNotNull { timeline ->
                    val events = timeline.events.filter { it.documentId in visible }
                    if (events.isEmpty()) return@mapNotNull null
                    HouseholdOverview.OpenCase(
                        person = person.name,
                        organisation = names[timeline.case.organisationProfileId],
                        title = timeline.case.title,
                        latest = latest(events.first()),
                        status = timeline.case.status.name.lowercase(),
                    )
                }
                .take(MAX_CASES_PER_PERSON)
        }

        val deadlines = items.mapNotNull { item ->
            val chip = item.dateChip.takeIf { it.kind == DocumentDateChip.Kind.DUE } ?: return@mapNotNull null
            val days = ChronoUnit.DAYS.between(today, chip.date)
            if (days !in 0..HouseholdOverviewFormat.DEADLINE_DAYS) return@mapNotNull null
            HouseholdOverview.Deadline(chip.date, item.people.firstOrNull()?.displayName, item.sender, item.document.title)
        }.sortedBy { it.date }.take(MAX_LISTED)

        val actions = items.filter { it.openActionCount > 0 }
            .sortedByDescending { it.document.createdAt }
            .take(MAX_LISTED)
            .map { HouseholdOverview.ActionLetter(it.document.title, it.sender, it.people.firstOrNull()?.displayName) }

        return HouseholdOverview(
            today = today,
            people = household.map { HouseholdOverview.Person(it.name, relation(it.isSelf, it.relationship)) },
            cases = cases,
            deadlines = deadlines,
            actions = actions,
        )
    }

    private fun relation(isSelf: Boolean, relationship: Relationship?): String? =
        if (isSelf) "me" else relationship?.name?.lowercase()

    /** One event as "date kind, title", the same wording the document card's case history uses. */
    private fun latest(event: ProfileEvent): String {
        val date = Instant.ofEpochMilli(event.eventDate).atZone(clock.zone).toLocalDate()
        val label = EventKinds.DEFAULT.byId(event.kind).label(EventKind.ENGLISH)
        val title = event.title.replace(Regex("\\s+"), " ").trim().let {
            if (it.length > CaseHistory.MAX_TITLE) it.take(CaseHistory.MAX_TITLE - 1).trimEnd() + "…" else it
        }
        return "$date $label" + if (title.isEmpty()) "" else ", $title"
    }

    private companion object {
        const val MAX_CASES_PER_PERSON = 3
        const val MAX_LISTED = 5
    }
}
