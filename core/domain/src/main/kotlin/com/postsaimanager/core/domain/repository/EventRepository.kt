package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ProfileEvent
import kotlinx.coroutines.flow.Flow

/**
 * The events of the profile timeline and the matters (cases) that group them. One owner of the concept: nothing else reads or writes
 * the tables. Events of a trashed document are not listed; they are gone for good with the document.
 */
interface EventRepository {
    /** The events that concern the household person [profileId], newest event date first. */
    fun observeEventsForPerson(profileId: String): Flow<List<ProfileEvent>>

    /** The events of letters from the organisation [organisationId] (for every household person), newest first. */
    fun observeEventsForOrganisation(organisationId: String): Flow<List<ProfileEvent>>

    /** The events of one matter, oldest first. */
    fun observeEventsForCase(caseId: String): Flow<List<ProfileEvent>>

    /** The events written for one document, oldest first. */
    fun observeEventsForDocument(documentId: String): Flow<List<ProfileEvent>>

    suspend fun eventsOfDocument(documentId: String): List<ProfileEvent>

    suspend fun eventsOfCase(caseId: String): List<ProfileEvent>

    /**
     * Replaces the [EventSource.DOCUMENT] events of [documentId] by [events] in one step, and the [EventSource.SYSTEM] ones with them (code
     * derived them from the values of the reading being replaced, a "deadline passed" from a due date the new reading may not have; the
     * daily check writes them again from the new values). ACTION and USER events stay, so a re-read keeps what the user did and wrote.
     */
    suspend fun replaceDocumentEvents(documentId: String, events: List<ProfileEvent>)

    /** Adds one event (an action, a derived fact, a user's note). */
    suspend fun addEvent(event: ProfileEvent)

    /** Sets the links of every event of [documentId] (all sources: they are links of the document). */
    suspend fun setDocumentLinks(documentId: String, personProfileIds: List<String>, organisationProfileId: String?, contactId: String?)

    fun observeCase(caseId: String): Flow<Case?>

    /** The matters of a person's events, most recently active first. */
    fun observeCasesForPerson(profileId: String): Flow<List<Case>>

    fun observeCasesForOrganisation(organisationId: String): Flow<List<Case>>

    suspend fun getCase(caseId: String): Case?

    /** The matters of one organisation, most recently created first. */
    suspend fun casesOfOrganisation(organisationId: String): List<Case>

    /** Inserts the matter, or replaces the one with its id. */
    suspend fun saveCase(case: Case)

    suspend fun setCaseStatus(caseId: String, status: CaseStatus)

    suspend fun renameCase(caseId: String, title: String)

    /** Deletes the matter when no event belongs to it any more. */
    suspend fun deleteCaseIfEmpty(caseId: String)
}
