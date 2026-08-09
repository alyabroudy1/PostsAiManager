package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val mapper: DocumentMapper,
    private val fieldRevisionDao: FieldRevisionDao,
    private val mergeExtraction: MergeExtractionUseCase,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : DocumentRepository {

    /**
     * Records an edit as the user's, with a revision.
     *
     * Every user-facing write to a field goes through here. A raw
     * `UPDATE extracted_data SET fieldValue` leaves `source = MACHINE`, and the next
     * extraction then treats the correction as its own disposable output and overwrites it
     * — which is the exact data loss the merge was built to prevent, arriving through a
     * different door. A device test caught it doing precisely that.
     */
    private suspend fun attributeToUser(
        fieldId: String,
        newValue: (com.postsaimanager.core.model.ExtractedData) -> String,
    ): PamResult<Unit> {
        val entity = documentDao.getExtractedField(fieldId)
            ?: return PamResult.Error(PamError.DatabaseError())
        val field = mapper.extractedDataToDomain(entity)

        val (updated, revision) = mergeExtraction.applyUserEdit(
            field = field,
            newValue = newValue(field),
            now = System.currentTimeMillis(),
            newId = { UuidGenerator.generate() },
        )
        documentDao.insertExtractedData(listOf(mapper.extractedDataToEntity(updated)))
        fieldRevisionDao.insertAll(listOf(mapper.revisionToEntity(revision)))
        return PamResult.Success(Unit)
    }

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

    override suspend fun confirmExtractedField(fieldId: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                // Confirming is adopting: the user asserts this value is right, so it
                // becomes theirs and stops being disposable machine output.
                attributeToUser(fieldId) { it.fieldValue }
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun addExtractedField(field: ExtractedData): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.insertSingleExtractedData(
                    com.postsaimanager.core.data.database.entity.ExtractedDataEntity(
                        id = field.id,
                        documentId = field.documentId,
                        fieldName = field.fieldName,
                        fieldValue = field.fieldValue,
                        fieldType = field.fieldType.name,
                        confidence = field.confidence,
                        pageNumber = field.pageNumber,
                        isConfirmed = field.isConfirmed,
                    )
                )
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun updateExtractedField(fieldId: String, name: String, value: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                if (name.isNotBlank()) documentDao.renameExtractedField(fieldId, name)
                attributeToUser(fieldId) { value }
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun deleteExtractedField(fieldId: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                // A tombstone, not a delete. Removing the row lets the next extraction
                // re-add the field, so the app would argue with the user once per run.
                val entity = documentDao.getExtractedField(fieldId)
                if (entity != null) {
                    val tombstoned = mergeExtraction.applyUserDelete(
                        mapper.extractedDataToDomain(entity),
                        System.currentTimeMillis(),
                    )
                    documentDao.insertExtractedData(
                        listOf(mapper.extractedDataToEntity(tombstoned)),
                    )
                }
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override fun observeDocument(id: String): Flow<Document?> =
        documentDao.observeById(id)
            .map { entity -> entity?.let(mapper::toDomain) }
            .flowOn(ioDispatcher)

    override fun observePages(documentId: String): Flow<List<DocumentPage>> =
        documentDao.observePages(documentId)
            .map { entities -> entities.map(mapper::pageToDomain) }
            .flowOn(ioDispatcher)

    override fun observeExtractedData(documentId: String): Flow<List<ExtractedData>> =
        documentDao.observeExtractedData(documentId)
            .map { entities -> entities.map(mapper::extractedDataToDomain) }
            .flowOn(ioDispatcher)
}
