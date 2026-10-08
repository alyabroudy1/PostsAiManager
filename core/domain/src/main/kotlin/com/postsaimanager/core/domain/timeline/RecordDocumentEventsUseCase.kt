package com.postsaimanager.core.domain.timeline

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ProfileEvent
import com.postsaimanager.core.model.ProfileKind
import com.postsaimanager.core.model.ProfileRole
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/**
 * Who a document's events are about: [personProfileIds] the household persons it concerns (the person chips, decided by the model),
 * [organisationProfileId] the resolved sender organisation, [contactId] the contact who handled the letter.
 */
data class EventLinks(
    val personProfileIds: List<String> = emptyList(),
    val organisationProfileId: String? = null,
    val organisationName: String? = null,
    val contactId: String? = null,
)

/** Reads the current links of a document from where they are decided (the document's person chips, its sender profile, its contact). */
class ResolveEventLinksUseCase @Inject constructor(
    private val profiles: ProfileRepository,
    private val contacts: ContactRepository,
) {

    suspend operator fun invoke(document: Document): EventLinks {
        val organisation = profiles.getProfilesForDocument(document.id).first()
            .firstOrNull { (profile, role) -> role == ProfileRole.SENDER && profile.kind == ProfileKind.ORGANISATION }?.first
        return EventLinks(
            personProfileIds = document.concernedProfileIds.orEmpty().distinct(),
            organisationProfileId = organisation?.id,
            organisationName = organisation?.let { it.organization?.takeIf(String::isNotBlank) ?: it.name },
            contactId = contacts.observeContactsForDocument(document.id).first().firstOrNull()?.id,
        )
    }
}

/** Brings a matter's stored status in line with its events ([CaseStatusDeriver]); a matter left with no event is deleted. */
class RefreshCaseStatusUseCase @Inject constructor(private val events: EventRepository) {

    suspend operator fun invoke(caseId: String) {
        val remaining = events.eventsOfCase(caseId)
        if (remaining.isEmpty()) {
            events.deleteCaseIfEmpty(caseId)
        } else {
            events.setCaseStatus(caseId, CaseStatusDeriver.derive(remaining))
        }
    }
}

/**
 * Writes what a letter reports onto the timeline, once the second stage has decided it (the kind, scored by the model, and a grounded title).
 *
 * - **One event per document**, carrying all the household persons the document concerns, so an organisation's timeline shows the letter
 *   once and a person's timeline finds it by any of them. (One row per person would repeat the letter wherever persons are merged.)
 * - **Kind**: the reading's; an id this build does not know is information. **Date**: [EventDateResolver]. **Title**: the reading's grounded
 *   title, else the document's own title.
 * - **Case**: grouped under the sender organisation. First by code: a stored reference value that exactly matches one of a matter's keys
 *   ([ReferenceKeys]). Then the matter the letter was already in, when it is read again. Then the model's same-matter question over the
 *   organisation's matters ([DecideSameMatterUseCase], margin, a made-up distractor); otherwise a new matter, titled with the first event's
 *   title (the user may rename it). With no resolved sender there is no matter.
 * - **Re-reading** replaces the document's DOCUMENT events and keeps every ACTION, USER and SYSTEM one.
 */
class RecordDocumentEventsUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val links: ResolveEventLinksUseCase,
    private val events: EventRepository,
    private val sameMatter: DecideSameMatterUseCase,
    private val refresh: RefreshCaseStatusUseCase,
    private val clock: Clock,
) {

    suspend operator fun invoke(documentId: String, reading: EventReading, kinds: EventKinds = EventKinds.DEFAULT) {
        val document = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.takeUnless { it.isTrashed } ?: return
        val fields = documents.observeExtractedData(documentId).first()
        val kind = kinds.byId(reading.kindId)
        val now = clock.millis()
        val resolved = links(document)
        val previous = events.eventsOfDocument(documentId).filter { it.source == EventSource.DOCUMENT }
        val event = ProfileEvent(
            id = UuidGenerator.generate(),
            documentId = documentId,
            kind = kind.id,
            eventDate = EventDateResolver.resolve(kind, fields, document.createdAt, clock.zone, earlierEventDate = previous.firstOrNull()?.eventDate),
            recordedAt = now,
            title = reading.title?.takeIf { it.isNotBlank() } ?: document.title,
            personProfileIds = resolved.personProfileIds,
            organisationProfileId = resolved.organisationProfileId,
            contactId = resolved.contactId,
            source = EventSource.DOCUMENT,
        )
        val caseId = resolved.organisationProfileId?.let { organisation ->
            caseFor(organisation, resolved.organisationName, event, kind, ReferenceKeys.of(fields), previous.firstOrNull(), now)
        }
        events.replaceDocumentEvents(documentId, listOf(event.copy(caseId = caseId)))
        (previous.mapNotNull { it.caseId } + listOfNotNull(caseId)).distinct().forEach { refresh(it) }
    }

    /** The matter [event] belongs to (an existing one with the new keys added, or a new one), saved. */
    private suspend fun caseFor(
        organisationId: String,
        organisationName: String?,
        event: ProfileEvent,
        kind: EventKind,
        keys: Set<String>,
        previous: ProfileEvent?,
        now: Long,
    ): String {
        val cases = events.casesOfOrganisation(organisationId)
        val byReference = cases.filter { c -> c.referenceKeys.any { it in keys } }.maxByOrNull { it.createdAt }
        val existing = byReference
            ?: cases.firstOrNull { it.id == previous?.caseId }
            ?: askSameMatter(cases, organisationName, event, kind)
        if (existing != null) {
            // A matter still titled as this letter's own earlier event was (nobody renamed it) follows the title the letter has now: a
            // re-read that names the sender better (the avatar letter no longer in front of it) must not leave the old name on the matter.
            val title = event.title.takeIf { previous != null && existing.title == previous.title && it.isNotBlank() } ?: existing.title
            if (!existing.referenceKeys.containsAll(keys) || title != existing.title) {
                events.saveCase(existing.copy(referenceKeys = existing.referenceKeys + keys, title = title))
            }
            return existing.id
        }
        val created = Case(
            id = UuidGenerator.generate(), organisationProfileId = organisationId, title = event.title, referenceKeys = keys,
            createdAt = now,
        )
        events.saveCase(created)
        return created.id
    }

    /** The organisation's matter the model says the letter is part of, or null (a new matter, or no model to ask). */
    private suspend fun askSameMatter(cases: List<Case>, organisationName: String?, event: ProfileEvent, kind: EventKind): Case? {
        if (cases.isEmpty()) return null
        val candidates = cases.map { c ->
            MatterCandidate(
                id = c.id,
                title = c.title,
                latestEvents = events.eventsOfCase(c.id).filter { it.documentId != event.documentId }
                    .sortedByDescending { it.eventDate }.map(::line),
            )
        }
        val question = NewMatterEvent(kind.label(EventKind.ENGLISH), event.eventDate, event.title)
        val decided = (sameMatter(organisationName.orEmpty(), question, candidates) as? PamResult.Success)?.data ?: return null
        return cases.firstOrNull { it.id == decided.matchedId }
    }

    private fun line(event: ProfileEvent): String =
        "${EventKinds.DEFAULT.byId(event.kind).label(EventKind.ENGLISH)} ${Instant.ofEpochMilli(event.eventDate).toString().take(DATE_CHARS)}: ${event.title}"

    private companion object {
        const val DATE_CHARS = 10
    }
}

/**
 * Updates the links (people, sender organisation, contact) of a document's events from where they are decided now: for when the people
 * check or the contact link finishes after the events were written. Code only; no model.
 */
class SyncEventLinksUseCase @Inject constructor(
    private val documents: DocumentRepository,
    private val links: ResolveEventLinksUseCase,
    private val events: EventRepository,
) {

    suspend operator fun invoke(documentId: String) {
        if (events.eventsOfDocument(documentId).isEmpty()) return
        val document = (documents.getDocumentById(documentId) as? PamResult.Success)?.data?.takeUnless { it.isTrashed } ?: return
        val resolved = links(document)
        events.setDocumentLinks(documentId, resolved.personProfileIds, resolved.organisationProfileId, resolved.contactId)
    }
}
