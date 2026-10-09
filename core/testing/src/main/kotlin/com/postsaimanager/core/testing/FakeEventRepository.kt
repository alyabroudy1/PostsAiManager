package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.CaseStatusSource
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.EventUserState
import com.postsaimanager.core.model.ProfileEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [EventRepository] for tests; reactive like the real one. Trashing is not modelled. */
class FakeEventRepository : EventRepository {

    private val events = MutableStateFlow<List<ProfileEvent>>(emptyList())
    private val cases = MutableStateFlow<List<Case>>(emptyList())

    /** Every event, in insertion order. */
    val allEvents: List<ProfileEvent> get() = events.value
    val allCases: List<Case> get() = cases.value

    fun seedEvents(vararg items: ProfileEvent) {
        events.value = events.value + items
    }

    fun seedCases(vararg items: Case) {
        cases.value = cases.value + items
    }

    private fun newestFirst(list: List<ProfileEvent>) = list.sortedWith(compareByDescending<ProfileEvent> { it.eventDate }.thenByDescending { it.recordedAt })

    private fun oldestFirst(list: List<ProfileEvent>) = list.sortedWith(compareBy<ProfileEvent> { it.eventDate }.thenBy { it.recordedAt })

    // Like the real one: a tombstone is never listed (the letter's own list for the re-read, eventsOfDocument, still has it).
    private fun listed(list: List<ProfileEvent>) = list.filter { it.userState != EventUserState.DELETED }

    override fun observeEventsForPerson(profileId: String): Flow<List<ProfileEvent>> =
        events.map { list -> newestFirst(listed(list).filter { profileId in it.personProfileIds }) }

    override fun observeEventsForOrganisation(organisationId: String): Flow<List<ProfileEvent>> =
        events.map { list -> newestFirst(listed(list).filter { it.organisationProfileId == organisationId }) }

    override fun observeEventsForCase(caseId: String): Flow<List<ProfileEvent>> =
        events.map { list -> oldestFirst(listed(list).filter { it.caseId == caseId }) }

    override fun observeEventsForDocument(documentId: String): Flow<List<ProfileEvent>> =
        events.map { list -> oldestFirst(listed(list).filter { it.documentId == documentId }) }

    override suspend fun eventsOfDocument(documentId: String): List<ProfileEvent> = oldestFirst(events.value.filter { it.documentId == documentId })

    override suspend fun eventsOfCase(caseId: String): List<ProfileEvent> = oldestFirst(listed(events.value).filter { it.caseId == caseId })

    override suspend fun getEvent(eventId: String): ProfileEvent? = events.value.firstOrNull { it.id == eventId }

    override suspend fun updateEventByUser(eventId: String, kind: String, eventDate: Long, title: String) {
        events.value = events.value.map {
            if (it.id != eventId) it else it.copy(
                kind = kind, eventDate = eventDate, title = title,
                userState = if (it.source == EventSource.USER) it.userState else EventUserState.EDITED,
            )
        }
    }

    override suspend fun markEventDeleted(eventId: String) {
        events.value = events.value.map { if (it.id == eventId) it.copy(userState = EventUserState.DELETED) else it }
    }

    override suspend fun deleteEvent(eventId: String) {
        events.value = events.value.filterNot { it.id == eventId }
    }

    override suspend fun replaceDocumentEvents(documentId: String, events: List<ProfileEvent>) {
        // What the user edited or deleted stays; the rest of the reading's and the derived events is replaced.
        this.events.value = this.events.value.filterNot {
            it.documentId == documentId && (it.source == EventSource.DOCUMENT || it.source == EventSource.SYSTEM) && it.userState == EventUserState.NONE
        } + events
    }

    override suspend fun addEvent(event: ProfileEvent) {
        events.value = events.value + event
    }

    override suspend fun setDocumentLinks(documentId: String, personProfileIds: List<String>, organisationProfileId: String?, contactId: String?) {
        events.value = events.value.map {
            if (it.documentId == documentId) it.copy(personProfileIds = personProfileIds, organisationProfileId = organisationProfileId, contactId = contactId) else it
        }
    }

    override fun observeCase(caseId: String): Flow<Case?> = cases.map { list -> list.firstOrNull { it.id == caseId } }

    override fun observeCasesForPerson(profileId: String): Flow<List<Case>> = cases.map { list ->
        val ids = events.value.filter { profileId in it.personProfileIds }.mapNotNull { it.caseId }.toSet()
        list.filter { it.id in ids }
    }

    override fun observeCasesForOrganisation(organisationId: String): Flow<List<Case>> =
        cases.map { list -> list.filter { it.organisationProfileId == organisationId } }

    override suspend fun getCase(caseId: String): Case? = cases.value.firstOrNull { it.id == caseId }

    override suspend fun casesOfOrganisation(organisationId: String): List<Case> =
        cases.value.filter { it.organisationProfileId == organisationId }.sortedByDescending { it.createdAt }

    override suspend fun saveCase(case: Case) {
        cases.value = cases.value.filterNot { it.id == case.id } + case
    }

    override suspend fun setCaseStatus(caseId: String, status: CaseStatus) {
        cases.value = cases.value.map { if (it.id == caseId) it.copy(status = status) else it }
    }

    override suspend fun setCaseStatusByUser(caseId: String, status: CaseStatus) {
        cases.value = cases.value.map { if (it.id == caseId) it.copy(status = status, statusSource = CaseStatusSource.USER) else it }
    }

    override suspend fun setCaseStatusAutomatic(caseId: String, derived: CaseStatus) {
        cases.value = cases.value.map { if (it.id == caseId) it.copy(status = derived, statusSource = CaseStatusSource.AUTO) else it }
    }

    override suspend fun setDocumentCase(documentId: String, caseId: String?) {
        events.value = events.value.map { if (it.documentId == documentId) it.copy(caseId = caseId) else it }
    }

    override suspend fun renameCase(caseId: String, title: String) {
        cases.value = cases.value.map { if (it.id == caseId) it.copy(title = title, titleSource = com.postsaimanager.core.model.CaseTitleSource.USER) else it }
    }

    override suspend fun deleteCaseIfEmpty(caseId: String) {
        if (listed(events.value).none { it.caseId == caseId }) cases.value = cases.value.filterNot { it.id == caseId }
    }
}
