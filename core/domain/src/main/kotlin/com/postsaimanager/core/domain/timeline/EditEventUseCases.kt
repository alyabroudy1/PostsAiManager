package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.EventUserState
import com.postsaimanager.core.model.ProfileEvent
import java.time.Clock
import javax.inject.Inject

/**
 * What a re-read may do to the events of a letter, the one place that decides it. The reading writes one event of its own per letter
 * (source DOCUMENT) and replaces it on every re-read, unless the user changed it: an edited reading event stays as they left it, a deleted
 * one stays deleted (a tombstone), and in both cases the re-read writes no new one beside it. The events the user added, an action's and the
 * system's are never replaced by a re-read to begin with.
 */
object EventEditPolicy {

    /** True when a re-read may write its event over [previous], the letter's events as stored (tombstones included). */
    fun mayReplaceReading(previous: List<ProfileEvent>): Boolean =
        previous.none { it.source == EventSource.DOCUMENT && it.userState != EventUserState.NONE }

    /** An event the user wrote is removed for good; any other is hidden by a tombstone so that it does not come back. */
    fun deletionIsTombstone(event: ProfileEvent): Boolean = event.source != EventSource.USER
}

private fun invalid(field: String, message: String): PamResult<Unit> = PamResult.Error(PamError.ValidationError(field, message))

/** The user edits an event: its kind (a chooser of the registry), its date and its text. A re-read keeps it as edited. */
class EditEventUseCase @Inject constructor(
    private val events: EventRepository,
    private val refresh: RefreshCaseStatusUseCase,
) {

    /** @param eventDate epoch millis of the day the event happened */
    suspend operator fun invoke(eventId: String, kindId: String, eventDate: Long, title: String): PamResult<Unit> {
        val event = events.getEvent(eventId)?.takeIf { it.userState != EventUserState.DELETED } ?: return invalid("event", "no such event")
        if (EventKinds.DEFAULT.all.none { it.id == kindId }) return invalid("kind", "unknown event kind $kindId")
        val text = title.trim().takeIf { it.isNotEmpty() } ?: return invalid("title", "an event needs its text")
        events.updateEventByUser(eventId, kindId, eventDate, text)
        // The status a matter derives from its events may have moved (an approval became a rejection).
        event.caseId?.let { refresh(it) }
        return PamResult.Success(Unit)
    }
}

/** The user deletes an event: one they wrote is removed, any other is hidden for good by a tombstone. */
class DeleteEventUseCase @Inject constructor(
    private val events: EventRepository,
    private val refresh: RefreshCaseStatusUseCase,
) {

    suspend operator fun invoke(eventId: String): PamResult<Unit> {
        val event = events.getEvent(eventId)?.takeIf { it.userState != EventUserState.DELETED } ?: return invalid("event", "no such event")
        if (EventEditPolicy.deletionIsTombstone(event)) events.markEventDeleted(eventId) else events.deleteEvent(eventId)
        // A matter left with no event is deleted; one that is left derives its status again.
        event.caseId?.let { refresh(it) }
        return PamResult.Success(Unit)
    }
}

/**
 * The user adds an event to a letter's timeline (a call they made, a decision they heard of): source USER, in the letter's matter, with the
 * letter's people, sender and contact. An event belongs to a letter, so it is added from the letter.
 */
class AddEventUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val links: ResolveEventLinksUseCase,
    private val events: EventRepository,
    private val refresh: RefreshCaseStatusUseCase,
    private val clock: Clock,
) {

    /** @param title the text; blank: the kind's English label */
    suspend operator fun invoke(documentId: String, kindId: String, eventDate: Long, title: String?): PamResult<Unit> {
        val document = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.takeUnless { it.isTrashed }
            ?: return invalid("document", "no such letter")
        val kind = EventKinds.DEFAULT.all.firstOrNull { it.id == kindId } ?: return invalid("kind", "unknown event kind $kindId")
        val own = events.eventsOfDocument(documentId).filter { it.userState != EventUserState.DELETED }
        val resolved = links(document)
        events.addEvent(
            ProfileEvent(
                id = UuidGenerator.generate(),
                documentId = documentId,
                kind = kind.id,
                eventDate = eventDate,
                recordedAt = clock.millis(),
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: kind.label(EventKind.ENGLISH),
                personProfileIds = resolved.personProfileIds,
                organisationProfileId = resolved.organisationProfileId,
                contactId = resolved.contactId,
                caseId = own.firstNotNullOfOrNull { it.caseId },
                source = EventSource.USER,
            ),
        )
        own.firstNotNullOfOrNull { it.caseId }?.let { refresh(it) }
        return PamResult.Success(Unit)
    }
}
