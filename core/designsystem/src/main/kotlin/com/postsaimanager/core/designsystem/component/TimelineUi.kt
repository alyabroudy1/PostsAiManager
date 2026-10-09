package com.postsaimanager.core.designsystem.component

import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.CaseStatusSource
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ProfileEvent

/**
 * One dated line of a timeline as the screen draws it. The kind is kept as its id: the words are looked up when drawing, in the
 * user's language ([TimelineSection]'s `kindLabel`), so a language switch needs no new presentation.
 *
 * @property context who or what the event is about in the page it is drawn on (the sender on a person's page, the persons on an
 *   organisation's page); null where the page already says it.
 * @property documentId the letter the event opens; an action's own event belongs to the letter it was done on.
 */
data class TimelineEventUi(
    val id: String,
    val documentId: String,
    val kindId: String,
    val eventDate: Long,
    val title: String,
    val source: EventSource,
    val context: String? = null,
)

/**
 * One matter as a card: its events newest first.
 *
 * @property organisationName the sender, shown on a person's page; null on the organisation's own page.
 * @property personNames the household persons the matter concerns, shown on an organisation's page; empty on a person's page.
 * @property letterCount how many letters the matter holds (an action event shares the letter it was done on).
 */
data class TimelineCaseUi(
    val caseId: String,
    val title: String,
    val status: CaseStatus,
    val letterCount: Int,
    val organisationName: String?,
    val personNames: List<String>,
    val events: List<TimelineEventUi>,
    /** The user set [status] by hand (it is not derived from the events until they hand it back to automatic). */
    val statusByUser: Boolean = false,
) {
    /** The newest event: the card's "Rejected · 5 Dec" line. */
    val latest: TimelineEventUi get() = events.first()
}

/** A profile's timeline as drawn: matter cards first, then the plain events under "Other". */
data class TimelineUi(val cases: List<TimelineCaseUi> = emptyList(), val other: List<TimelineEventUi> = emptyList()) {
    val isEmpty: Boolean get() = cases.isEmpty() && other.isEmpty()

    companion object {
        val EMPTY = TimelineUi()
    }
}

/**
 * The compact "Part of: <matter>" block of a letter.
 *
 * @property events the matter's events newest first, the letter's own among them.
 * @property openProfileId the profile whose timeline shows the matter: the letter's first household person, else the sender.
 */
data class DocumentCaseUi(
    val caseId: String,
    val title: String,
    val status: CaseStatus,
    val events: List<TimelineEventUi>,
    val currentDocumentId: String,
    val openProfileId: String?,
    val statusByUser: Boolean = false,
)

/** A matter and its events, as the presenter takes them (the domain's `CaseTimeline`, without the domain). */
data class TimelineCaseInput(val case: Case, val events: List<ProfileEvent>)

/**
 * Turns matters and events into what the timeline draws. Pure, so it is tested without a screen.
 *
 * The single-event rule (presentation only): every letter makes a matter, so a lone "information" letter makes a matter of one event,
 * and a card for it would only repeat the event. A matter holding exactly one event and still carrying its generated title (the
 * first event's title, see `RecordDocumentEventsUseCase`) is shown as a plain event under "Other"; a matter the user renamed is a
 * card even with one event, because the name is something the user said.
 */
object TimelinePresenter {

    /** Whether [case] with [events] is drawn as a plain event rather than a card. */
    fun isPlainEvent(case: Case, events: List<ProfileEvent>): Boolean = events.size == 1 && case.title == events.first().title

    /**
     * @param cases the matters (any order) with their events.
     * @param looseEvents the events that belong to no matter.
     * @param organisationName the name of an organisation profile, or null when unknown.
     * @param personName the name of a household person profile, or null when unknown.
     * @param forOrganisation true on an organisation's page (cards carry the persons), false on a person's (cards carry the sender).
     */
    fun present(
        cases: List<TimelineCaseInput>,
        looseEvents: List<ProfileEvent>,
        organisationName: (String) -> String?,
        personName: (String) -> String?,
        forOrganisation: Boolean,
    ): TimelineUi {
        val cards = mutableListOf<TimelineCaseUi>()
        val plain = mutableListOf<ProfileEvent>()
        cases.forEach { (case, events) ->
            if (events.isEmpty()) return@forEach
            if (isPlainEvent(case, events)) plain += events else cards += card(case, events, organisationName, personName, forOrganisation)
        }
        plain += looseEvents
        return TimelineUi(
            cases = cards.sortedByDescending { it.latest.eventDate },
            other = newestFirst(plain).map { event(it, organisationName, personName, forOrganisation) },
        )
    }

    private fun card(
        case: Case,
        events: List<ProfileEvent>,
        organisationName: (String) -> String?,
        personName: (String) -> String?,
        forOrganisation: Boolean,
    ): TimelineCaseUi {
        val ordered = newestFirst(events)
        val letters = events.filter { it.source == EventSource.DOCUMENT }.map { it.documentId }.distinct()
            .ifEmpty { events.map { it.documentId }.distinct() }
        return TimelineCaseUi(
            caseId = case.id,
            title = case.title,
            status = case.status,
            statusByUser = case.statusSource == CaseStatusSource.USER,
            letterCount = letters.size,
            organisationName = organisationName(case.organisationProfileId).takeUnless { forOrganisation },
            personNames = if (forOrganisation) events.flatMap { it.personProfileIds }.distinct().mapNotNull(personName) else emptyList(),
            events = ordered.map { event(it, null, null, false) },
        )
    }

    private fun event(
        event: ProfileEvent,
        organisationName: ((String) -> String?)?,
        personName: ((String) -> String?)?,
        forOrganisation: Boolean,
    ): TimelineEventUi {
        val context = if (forOrganisation) {
            event.personProfileIds.mapNotNull { personName?.invoke(it) }.joinToString(", ")
        } else {
            event.organisationProfileId?.let { organisationName?.invoke(it) }
        }
        return TimelineEventUi(
            id = event.id,
            documentId = event.documentId,
            kindId = event.kind,
            eventDate = event.eventDate,
            title = event.title,
            source = event.source,
            context = context?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The "Part of" block for [documentId], or null when the letter belongs to no matter or to one drawn as a plain event.
     *
     * @param events the matter's events, any order, the letter's own among them.
     * @param showPlain draw the row for a matter that is only this letter too (the letter's own page always says where the letter is, so
     *   the user can rename or move it)
     */
    fun documentCase(documentId: String, case: Case, events: List<ProfileEvent>, showPlain: Boolean = false): DocumentCaseUi? {
        if (events.isEmpty() || (!showPlain && isPlainEvent(case, events))) return null
        val own = events.filter { it.documentId == documentId }
        return DocumentCaseUi(
            caseId = case.id,
            title = case.title,
            status = case.status,
            statusByUser = case.statusSource == CaseStatusSource.USER,
            events = newestFirst(events).map { event(it, null, null, false) },
            currentDocumentId = documentId,
            openProfileId = own.flatMap { it.personProfileIds }.firstOrNull() ?: case.organisationProfileId,
        )
    }

    private fun newestFirst(events: List<ProfileEvent>) =
        events.sortedWith(compareByDescending<ProfileEvent> { it.eventDate }.thenByDescending { it.recordedAt })
}
