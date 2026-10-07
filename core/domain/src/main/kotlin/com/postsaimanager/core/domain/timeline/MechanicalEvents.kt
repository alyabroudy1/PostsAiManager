package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ProfileEvent
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject

/**
 * The events code writes with no model, on the same matter and with the same links as the document they are about: what the document's
 * newest DOCUMENT event has, or, for a letter with none yet, what its links are now (no matter).
 */
class MechanicalEventWriter @Inject constructor(
    private val events: EventRepository,
    private val links: ResolveEventLinksUseCase,
    private val clock: Clock,
) {

    /** Writes one event of [kindId] and [source] for [document], dated [eventDate] (epoch millis); [title] falls back to the kind's English label. */
    suspend fun write(document: Document, kindId: String, source: EventSource, eventDate: Long, title: String?) {
        val base = events.eventsOfDocument(document.id).filter { it.source == EventSource.DOCUMENT }.maxByOrNull { it.recordedAt }
        val resolved = if (base == null) links(document) else null
        val event = ProfileEvent(
            id = UuidGenerator.generate(),
            documentId = document.id,
            kind = kindId,
            eventDate = eventDate,
            recordedAt = clock.millis(),
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE) ?: EventKinds.DEFAULT.byId(kindId).label(EventKind.ENGLISH),
            personProfileIds = base?.personProfileIds ?: resolved?.personProfileIds.orEmpty(),
            organisationProfileId = base?.organisationProfileId ?: resolved?.organisationProfileId,
            contactId = base?.contactId ?: resolved?.contactId,
            caseId = base?.caseId,
            source = source,
        )
        events.addEvent(event)
    }

    private companion object {
        const val MAX_TITLE = 60
    }
}

/**
 * A confirmed action card becomes an ACTION event on the timeline: a reminder set, an e-mail prepared, a calendar entry. Called only
 * after the action worked (the user pressed Open and the executor succeeded), so the event is the fact of what was done, written by
 * code with no model. The event's title is the action's own text (the reminder's text, the e-mail's subject, the calendar entry's
 * title); the words for the kind come from the registry. Reading the clock records nothing.
 */
class RecordActionEventUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val writer: MechanicalEventWriter,
    private val clock: Clock,
) {

    suspend operator fun invoke(documentId: String, action: AgentAction) {
        val (kind, title) = when (action) {
            is AgentAction.ScheduleReminder -> EventKinds.REMINDER_SET to action.text
            is AgentAction.SendEmail -> EventKinds.EMAIL_SENT to action.subject
            is AgentAction.CreateCalendarEvent -> EventKinds.CALENDAR_ENTRY to action.title
            AgentAction.GetDateTime -> return
        }
        val document = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.takeUnless { it.isTrashed } ?: return
        writer.write(document, kind, EventSource.ACTION, clock.millis(), title)
    }
}

/**
 * "Deadline passed": a pure check, run when the app opens (and daily). For every document whose due date is before today and for which
 * no action was confirmed (no ACTION event), one SYSTEM event is written, once (never again for a document that has one). A due date
 * passing is a fact, not a meaning, so no model is asked; the date is the one the list shows as the document's deadline.
 *
 * @return how many events were written
 */
class RecordPassedDeadlinesUseCase @Inject constructor(
    private val documents: ObserveDocumentListItemsUseCase,
    private val events: EventRepository,
    private val writer: MechanicalEventWriter,
    private val clock: Clock,
) {

    suspend operator fun invoke(): Int {
        val today = java.time.LocalDate.now(clock)
        var written = 0
        for (item in documents().first()) {
            val due = item.dateChip.takeIf { it.kind == DocumentDateChip.Kind.DUE }?.date ?: continue
            if (!due.isBefore(today)) continue
            val existing = events.eventsOfDocument(item.id)
            if (existing.any { it.source == EventSource.ACTION || it.kind == EventKinds.DEADLINE_PASSED }) continue
            writer.write(item.document, EventKinds.DEADLINE_PASSED, EventSource.SYSTEM, due.atStartOfDay(clock.zone).toInstant().toEpochMilli(), item.document.title)
            written++
        }
        return written
    }
}
