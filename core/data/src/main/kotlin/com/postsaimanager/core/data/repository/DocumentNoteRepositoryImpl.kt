package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.data.database.dao.DocumentNoteDao
import com.postsaimanager.core.data.database.entity.DocumentNoteEntity
import com.postsaimanager.core.domain.repository.DocumentNoteRepository
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class DocumentNoteRepositoryImpl @Inject constructor(
    private val dao: DocumentNoteDao,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DocumentNoteRepository {

    override fun observe(documentId: String): Flow<List<DocumentNote>> =
        dao.observeByDocument(documentId).map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override suspend fun notes(documentId: String): List<DocumentNote> = withContext(ioDispatcher) {
        dao.getByDocument(documentId).map(::toDomain)
    }

    override fun observeForProfile(profileId: String): Flow<List<DocumentNote>> =
        dao.observeByProfile(profileId).map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override fun observeOutsideDocuments(): Flow<List<DocumentNote>> =
        dao.observeOutsideDocuments().map { it.map(::toDomain) }.flowOn(ioDispatcher)

    override suspend fun notesOutsideDocuments(): List<DocumentNote> = withContext(ioDispatcher) {
        dao.getOutsideDocuments().map(::toDomain)
    }

    override suspend fun addOutsideDocument(profileId: String?, text: String, source: NoteSource, sourceRef: String?): DocumentNote =
        withContext(ioDispatcher) {
            val now = System.currentTimeMillis()
            val entity = DocumentNoteEntity(
                id = UUID.randomUUID().toString(), documentId = null, profileId = profileId, text = text, source = source.name,
                createdAt = now, updatedAt = now, pinned = false, sourceRef = sourceRef,
            )
            dao.upsert(entity)
            toDomain(entity)
        }

    override suspend fun add(documentId: String, text: String, source: NoteSource, sourceRef: String?): DocumentNote =
        withContext(ioDispatcher) {
            val now = System.currentTimeMillis()
            val entity = DocumentNoteEntity(
                id = UUID.randomUUID().toString(), documentId = documentId, profileId = null, text = text, source = source.name,
                createdAt = now, updatedAt = now, pinned = false, sourceRef = sourceRef,
            )
            dao.upsert(entity)
            toDomain(entity)
        }

    override suspend fun upsertByRef(documentId: String, source: NoteSource, sourceRef: String, text: String): DocumentNote =
        withContext(ioDispatcher) {
            val now = System.currentTimeMillis()
            val existing = dao.getByRef(documentId, source.name, sourceRef)
            val entity = existing?.copy(text = text, updatedAt = now) ?: DocumentNoteEntity(
                id = UUID.randomUUID().toString(), documentId = documentId, profileId = null, text = text, source = source.name,
                createdAt = now, updatedAt = now, pinned = false, sourceRef = sourceRef,
            )
            dao.upsert(entity)
            toDomain(entity)
        }

    override suspend fun updateText(id: String, text: String) = withContext(ioDispatcher) {
        val existing = dao.getById(id) ?: return@withContext
        // An edit makes the note the user's, whoever wrote it first; its reference stays, so the card it came from can find it.
        dao.upsert(existing.copy(text = text, source = NoteSource.USER.name, updatedAt = System.currentTimeMillis()))
    }

    override suspend fun setPinned(id: String, pinned: Boolean) = withContext(ioDispatcher) {
        val existing = dao.getById(id) ?: return@withContext
        dao.upsert(existing.copy(pinned = pinned))
    }

    override suspend fun delete(id: String) = withContext(ioDispatcher) { dao.delete(id) }

    override suspend fun deleteByRef(source: NoteSource, sourceRef: String) =
        withContext(ioDispatcher) { dao.deleteByRef(source.name, sourceRef) }

    private fun toDomain(entity: DocumentNoteEntity) = DocumentNote(
        id = entity.id,
        documentId = entity.documentId,
        profileId = entity.profileId,
        text = entity.text,
        source = NoteSource.entries.firstOrNull { it.name == entity.source } ?: NoteSource.USER,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        pinned = entity.pinned,
        sourceRef = entity.sourceRef,
    )
}
