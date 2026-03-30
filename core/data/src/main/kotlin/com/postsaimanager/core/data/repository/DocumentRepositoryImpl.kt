package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val mapper: DocumentMapper,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DocumentRepository {

    override fun getDocuments(): Flow<List<Document>> =
        documentDao.observeAll()
            .map { entities -> entities.map(mapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun getDocumentsByStatus(status: DocumentStatus): Flow<List<Document>> =
        documentDao.observeByStatus(status.name)
            .map { entities -> entities.map(mapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun getFavoriteDocuments(): Flow<List<Document>> =
        documentDao.observeFavorites()
            .map { entities -> entities.map(mapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun searchDocuments(query: String): Flow<List<Document>> =
        documentDao.search(query)
            .map { entities -> entities.map(mapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun getDocumentById(id: String): PamResult<Document> =
        withContext(ioDispatcher) {
            try {
                val entity = documentDao.getById(id)
                if (entity != null) {
                    PamResult.Success(mapper.toDomain(entity))
                } else {
                    PamResult.Error(PamError.FileNotFound(path = id))
                }
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun getDocumentPages(documentId: String): PamResult<List<DocumentPage>> =
        withContext(ioDispatcher) {
            try {
                val pages = documentDao.getPages(documentId)
                PamResult.Success(pages.map(mapper::pageToDomain))
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun createDocument(document: Document, pages: List<DocumentPage>): PamResult<Document> =
        withContext(ioDispatcher) {
            try {
                documentDao.insertDocumentWithPages(
                    document = mapper.toEntity(document),
                    pages = pages.map(mapper::pageToEntity),
                )
                PamResult.Success(document)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun updateDocument(document: Document): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.update(mapper.toEntity(document))
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun deleteDocument(id: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.deleteById(id)
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun toggleFavorite(id: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.toggleFavorite(id)
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun updateDocumentStatus(id: String, status: DocumentStatus): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.updateStatus(id, status.name)
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }
}
