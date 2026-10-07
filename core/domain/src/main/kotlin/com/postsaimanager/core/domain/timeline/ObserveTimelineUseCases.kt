package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.ProfileEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/** One matter with its events, newest first. */
data class CaseTimeline(val case: Case, val events: List<ProfileEvent>)

/** A profile's timeline: its matters (the most recently active first) and the events that belong to none, newest first. */
data class ProfileTimeline(val cases: List<CaseTimeline>, val looseEvents: List<ProfileEvent>)

/** Groups events under their matters. Pure. */
object TimelineBuilder {

    fun build(events: List<ProfileEvent>, cases: List<Case>): ProfileTimeline {
        val newestFirst = events.sortedWith(compareByDescending<ProfileEvent> { it.eventDate }.thenByDescending { it.recordedAt })
        val byCase = newestFirst.groupBy { it.caseId }
        val grouped = cases.filter { byCase.containsKey(it.id) }
            .map { CaseTimeline(it, byCase.getValue(it.id)) }
            .sortedByDescending { it.events.first().eventDate }
        // An event whose matter is not among [cases] (not loaded yet) is loose rather than lost.
        val known = grouped.map { it.case.id }.toSet()
        return ProfileTimeline(grouped, newestFirst.filter { it.caseId == null || it.caseId !in known })
    }
}

/** The timeline of a household person: the matters their letters belong to, and the events outside any matter. */
class ObserveTimelineForPersonUseCase @Inject constructor(private val events: EventRepository) {
    operator fun invoke(profileId: String): Flow<ProfileTimeline> =
        combine(events.observeEventsForPerson(profileId), events.observeCasesForPerson(profileId), TimelineBuilder::build)
}

/** The timeline of an organisation: every matter of its letters, for all household persons. */
class ObserveTimelineForOrganisationUseCase @Inject constructor(private val events: EventRepository) {
    operator fun invoke(organisationId: String): Flow<ProfileTimeline> =
        combine(events.observeEventsForOrganisation(organisationId), events.observeCasesForOrganisation(organisationId), TimelineBuilder::build)
}

/** The matter a letter is part of, with all its events (the letter's own among them); null when the letter belongs to none. */
class ObserveCaseForDocumentUseCase @Inject constructor(private val events: EventRepository) {

    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(documentId: String): Flow<CaseTimeline?> =
        events.observeEventsForDocument(documentId).flatMapLatest { own ->
            val caseId = own.firstNotNullOfOrNull { it.caseId } ?: return@flatMapLatest flowOf(null)
            combine(events.observeCase(caseId), events.observeEventsForCase(caseId)) { case, all ->
                case?.let { CaseTimeline(it, all.sortedWith(compareByDescending<ProfileEvent> { e -> e.eventDate }.thenByDescending { e -> e.recordedAt })) }
            }
        }
}

/** The user renames a matter; the generated title was only a first guess. */
class RenameCaseUseCase @Inject constructor(private val events: EventRepository) {
    suspend operator fun invoke(caseId: String, title: String) {
        title.trim().takeIf { it.isNotEmpty() }?.let { events.renameCase(caseId, it) }
    }
}
