package com.postsaimanager.core.data.repository

import androidx.room.withTransaction
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.dao.ProfileEventDao
import com.postsaimanager.core.data.database.dao.ProfileEventWithPeople
import com.postsaimanager.core.data.database.entity.CaseEntity
import com.postsaimanager.core.data.database.entity.ProfileEventEntity
import com.postsaimanager.core.domain.repository.EventRepository
import com.postsaimanager.core.model.Case
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource
import com.postsaimanager.core.model.ProfileEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Room implementation of [EventRepository]. */
class EventRepositoryImpl @Inject constructor(
    private val database: PamDatabase,
    private val dao: ProfileEventDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : EventRepository {

    override fun observeEventsForPerson(profileId: String): Flow<List<ProfileEvent>> =
        dao.observeForPerson(profileId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override fun observeEventsForOrganisation(organisationId: String): Flow<List<ProfileEvent>> =
        dao.observeForOrganisation(organisationId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override fun observeEventsForCase(caseId: String): Flow<List<ProfileEvent>> =
        dao.observeForCase(caseId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override fun observeEventsForDocument(documentId: String): Flow<List<ProfileEvent>> =
        dao.observeForDocument(documentId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override suspend fun eventsOfDocument(documentId: String): List<ProfileEvent> =
        withContext(ioDispatcher) { dao.getForDocument(documentId).map(::toDomain) }

    override suspend fun eventsOfCase(caseId: String): List<ProfileEvent> =
        withContext(ioDispatcher) { dao.getForCase(caseId).map(::toDomain) }

    override suspend fun replaceDocumentEvents(documentId: String, events: List<ProfileEvent>) = withContext(ioDispatcher) {
        database.withTransaction {
            dao.deleteBySource(documentId, EventSource.DOCUMENT.name)
            events.forEach { insert(it) }
        }
    }

    override suspend fun addEvent(event: ProfileEvent) = withContext(ioDispatcher) { database.withTransaction { insert(event) } }

    private suspend fun insert(event: ProfileEvent) {
        dao.insert(
            ProfileEventEntity(
                id = event.id, documentId = event.documentId, kind = event.kind, eventDate = event.eventDate, recordedAt = event.recordedAt,
                title = event.title, organisationProfileId = event.organisationProfileId, contactId = event.contactId,
                caseId = event.caseId, source = event.source.name,
            ),
        )
        if (event.personProfileIds.isNotEmpty()) dao.insertPeople(event.id, event.personProfileIds)
    }

    override suspend fun setDocumentLinks(
        documentId: String,
        personProfileIds: List<String>,
        organisationProfileId: String?,
        contactId: String?,
    ) = withContext(ioDispatcher) {
        database.withTransaction {
            dao.updateLinks(documentId, organisationProfileId, contactId)
            dao.deletePeopleOfDocument(documentId)
            if (personProfileIds.isNotEmpty()) dao.idsOfDocument(documentId).forEach { dao.insertPeople(it, personProfileIds) }
        }
    }

    override fun observeCase(caseId: String): Flow<Case?> = dao.observeCase(caseId).map { it?.let(::toDomain) }.flowOn(ioDispatcher)

    override fun observeCasesForPerson(profileId: String): Flow<List<Case>> =
        dao.observeCasesForPerson(profileId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override fun observeCasesForOrganisation(organisationId: String): Flow<List<Case>> =
        dao.observeCasesForOrganisation(organisationId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override suspend fun getCase(caseId: String): Case? = withContext(ioDispatcher) { dao.getCase(caseId)?.let(::toDomain) }

    override suspend fun casesOfOrganisation(organisationId: String): List<Case> =
        withContext(ioDispatcher) { dao.casesOfOrganisation(organisationId).map(::toDomain) }

    override suspend fun saveCase(case: Case) = withContext(ioDispatcher) {
        dao.upsertCase(
            CaseEntity(
                id = case.id, organisationProfileId = case.organisationProfileId, title = case.title,
                referenceKeys = encodeKeys(case.referenceKeys), status = case.status.name, createdAt = case.createdAt,
            ),
        )
    }

    override suspend fun setCaseStatus(caseId: String, status: CaseStatus) = withContext(ioDispatcher) { dao.setCaseStatus(caseId, status.name) }

    override suspend fun renameCase(caseId: String, title: String) = withContext(ioDispatcher) { dao.renameCase(caseId, title) }

    override suspend fun deleteCaseIfEmpty(caseId: String) = withContext(ioDispatcher) { dao.deleteCaseIfEmpty(caseId) }

    private fun toDomain(row: ProfileEventWithPeople) = row.event.let {
        ProfileEvent(
            id = it.id, documentId = it.documentId, kind = it.kind, eventDate = it.eventDate, recordedAt = it.recordedAt, title = it.title,
            personProfileIds = row.personIds, organisationProfileId = it.organisationProfileId, contactId = it.contactId, caseId = it.caseId,
            source = runCatching { EventSource.valueOf(it.source) }.getOrDefault(EventSource.DOCUMENT),
        )
    }

    private fun toDomain(row: CaseEntity) = Case(
        id = row.id, organisationProfileId = row.organisationProfileId, title = row.title, referenceKeys = decodeKeys(row.referenceKeys),
        status = runCatching { CaseStatus.valueOf(row.status) }.getOrDefault(CaseStatus.OPEN), createdAt = row.createdAt,
    )

    private companion object {
        const val SEPARATOR = "|"

        /** The keys as one text, each between two separators, so "|KEY|" finds exactly one key. */
        fun encodeKeys(keys: Set<String>): String = if (keys.isEmpty()) "" else keys.sorted().joinToString(SEPARATOR, SEPARATOR, SEPARATOR)

        fun decodeKeys(text: String): Set<String> = text.split(SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }
}
