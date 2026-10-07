package com.postsaimanager.core.data.repository

import androidx.room.withTransaction
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.dao.ContactDao
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.entity.ContactPersonEntity
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity
import com.postsaimanager.core.data.database.entity.DocumentContactEntity
import com.postsaimanager.core.domain.document.normaliseEntityName
import com.postsaimanager.core.domain.repository.ContactRepository
import com.postsaimanager.core.model.ContactPerson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Room implementation of [ContactRepository]. */
class ContactRepositoryImpl @Inject constructor(
    private val database: PamDatabase,
    private val contactDao: ContactDao,
    private val dismissedEntityDao: DismissedEntityDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : ContactRepository {

    override fun observeContacts(organisationId: String): Flow<List<ContactPerson>> =
        contactDao.observeForOrganisation(organisationId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override fun observeContactsForDocument(documentId: String): Flow<List<ContactPerson>> =
        contactDao.observeForDocument(documentId).map { rows -> rows.map(::toDomain) }.flowOn(ioDispatcher)

    override fun observeContactCounts(): Flow<Map<String, Int>> =
        contactDao.observeCounts().map { rows -> rows.associate { it.organisationId to it.count } }.flowOn(ioDispatcher)

    override suspend fun getContact(id: String): PamResult<ContactPerson> = guarded {
        contactDao.getById(id)?.let { PamResult.Success(toDomain(it)) } ?: PamResult.Error(PamError.FileNotFound(path = id))
    }

    override suspend fun addContact(contact: ContactPerson): PamResult<ContactPerson> = guarded {
        contactDao.insert(toEntity(contact))
        PamResult.Success(contact)
    }

    override suspend fun updateContact(contact: ContactPerson): PamResult<Unit> = guarded {
        contactDao.update(toEntity(contact))
        PamResult.Success(Unit)
    }

    override suspend fun deleteContact(id: String): PamResult<Unit> = guarded {
        database.withTransaction<PamResult<Unit>> {
            val contact = contactDao.getById(id)
            if (contact != null) {
                // The same tombstone a deleted machine-made profile leaves: reading the letter again does not bring the person back.
                val now = System.currentTimeMillis()
                contactDao.documentIdsOf(id).forEach { documentId ->
                    dismissedEntityDao.dismiss(DismissedEntityEntity(documentId, normaliseEntityName(contact.name), now))
                }
            }
            contactDao.deleteById(id)
            PamResult.Success(Unit)
        }
    }

    override suspend fun isRemovedFromDocument(documentId: String, name: String): Boolean = withContext(ioDispatcher) {
        dismissedEntityDao.isDismissed(documentId, normaliseEntityName(name))
    }

    override suspend fun setActive(id: String, active: Boolean): PamResult<Unit> = guarded {
        contactDao.setActive(id, active)
        PamResult.Success(Unit)
    }

    override suspend fun mergeContacts(keepId: String, mergedId: String): PamResult<Unit> = guarded {
        if (keepId == mergedId) return@guarded PamResult.Error(PamError.ValidationError("contact", "cannot merge a contact into itself"))
        database.withTransaction<PamResult<Unit>> {
            val keep = contactDao.getById(keepId)
            val merged = contactDao.getById(mergedId)
            when {
                keep == null -> PamResult.Error(PamError.FileNotFound(path = keepId))
                merged == null -> PamResult.Error(PamError.FileNotFound(path = mergedId))
                keep.organisationId != merged.organisationId ->
                    PamResult.Error(PamError.ValidationError("contact", "contacts of different organisations cannot be merged"))
                else -> {
                    contactDao.update(
                        keep.copy(
                            title = keep.title ?: merged.title,
                            department = keep.department ?: merged.department,
                            phone = keep.phone ?: merged.phone,
                            email = keep.email ?: merged.email,
                            room = keep.room ?: merged.room,
                            firstSeen = minOf(keep.firstSeen, merged.firstSeen),
                            lastSeen = maxOf(keep.lastSeen, merged.lastSeen),
                        ),
                    )
                    contactDao.moveLinks(mergedId, keepId)
                    // A link both contacts had stays on the merged row; it goes with the row (cascade).
                    contactDao.deleteById(mergedId)
                    PamResult.Success(Unit)
                }
            }
        }
    }

    override suspend fun linkContactToDocument(contactId: String, documentId: String): PamResult<Unit> = guarded {
        contactDao.insertLink(DocumentContactEntity(documentId, contactId, System.currentTimeMillis()))
        PamResult.Success(Unit)
    }

    override suspend fun unlinkContactFromDocument(contactId: String, documentId: String): PamResult<Unit> = guarded {
        contactDao.deleteLink(contactId, documentId)
        PamResult.Success(Unit)
    }

    private suspend fun <T> guarded(block: suspend () -> PamResult<T>): PamResult<T> = withContext(ioDispatcher) {
        try {
            block()
        } catch (e: Exception) {
            PamResult.Error(PamError.DatabaseError(cause = e))
        }
    }

    private fun toDomain(entity: ContactPersonEntity) = ContactPerson(
        id = entity.id, organisationId = entity.organisationId, name = entity.name, title = entity.title,
        department = entity.department, phone = entity.phone, email = entity.email, room = entity.room,
        firstSeen = entity.firstSeen, lastSeen = entity.lastSeen, active = entity.active,
    )

    private fun toEntity(contact: ContactPerson) = ContactPersonEntity(
        id = contact.id, organisationId = contact.organisationId, name = contact.name, title = contact.title,
        department = contact.department, phone = contact.phone, email = contact.email, room = contact.room,
        firstSeen = contact.firstSeen, lastSeen = contact.lastSeen, active = contact.active,
    )
}
