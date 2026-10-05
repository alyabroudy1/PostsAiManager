package com.postsaimanager.core.data.repository

import androidx.room.withTransaction
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.data.database.PamDatabase
import com.postsaimanager.core.data.database.dao.ConversationDao
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.usecase.MergeExtractionUseCase
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.mapper.DocumentMapper
import com.postsaimanager.core.data.util.PageImageStore
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ReviewState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class DocumentRepositoryImpl @Inject constructor(
    private val database: PamDatabase,
    private val documentDao: DocumentDao,
    private val conversationDao: ConversationDao,
    private val mapper: DocumentMapper,
    private val fieldRevisionDao: FieldRevisionDao,
    private val mergeExtraction: MergeExtractionUseCase,
    private val pageImageStore: PageImageStore,
    private val documentProcessor: DocumentProcessor,
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
                // Own the scanned images before anything is persisted: `pages` still points
                // at ML Kit's scan cache here, which the OS can clear at any time. Features
                // stay unaware of this — they hand over whatever URI they were given and get
                // back pages backed by app storage.
                val stored = when (val result = pageImageStore.storePages(document.id, pages)) {
                    is PamResult.Success -> result.data
                    is PamResult.Error -> return@withContext result
                }
                documentDao.insertDocumentWithPages(
                    document = mapper.toEntity(document),
                    pages = stored.map(mapper::pageToEntity),
                )
                PamResult.Success(document)
            } catch (e: Exception) {
                pageImageStore.deleteDocumentImages(document.id)
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

    override fun observeTrash(): Flow<List<Document>> =
        documentDao.observeTrashed()
            .map { entities -> entities.map(mapper::toDomain) }
            .flowOn(ioDispatcher)

    override suspend fun moveToTrash(id: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                // Before the flag is set: a worker that finishes after this would otherwise
                // write OCR text, fields and a status right back onto a document the user
                // just asked to disappear. `processDocument` also re-checks this itself
                // right before every write, closing the race where a worker is already
                // mid-run when this call lands.
                documentProcessor.cancel(id)
                documentDao.setDeletedAt(id, System.currentTimeMillis())
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun restore(id: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.clearDeletedAt(id)
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    /**
     * Deletes a document for good, in a fixed order:
     * 1. cancel any processing work (idempotent — it may already be cancelled from
     *    [moveToTrash]);
     * 2. delete the DB row in one transaction — the row's own cascade removes pages,
     *    extracted data, revisions, chunks, tags links, relations, profile links and
     *    dismissed-entity/proposal rows, and this also deletes the document's own
     *    conversation(s) and any stray `message_sources` elsewhere that cite it, since
     *    neither has a foreign key to `documents` (see `ConversationDao.deleteForDocument`/
     *    `deleteMessageSourcesForDocument`);
     * 3. once that transaction commits, delete the on-disk page images.
     *
     * Files are deleted only after the DB transaction commits: if the transaction fails, the
     * document (and its files) are left exactly as they were, rather than a document row
     * surviving with no images behind it.
     *
     * Profiles are never touched — only `document_profile_links` rows, via cascade. Tags are
     * never touched — only `document_tags` rows, via cascade.
     *
     * Shared/exported PDFs in `cacheDir/shared_pdfs` are deliberately not touched here: they
     * are named from the document's title at export time (`PAM_<title>.pdf`), not from its
     * id, so there is no reliable way to attribute a cached PDF back to this document without
     * risking deleting another document's export that happens to share a title. That cache is
     * OS-reclaimable and holds throwaway copies, not the source of truth.
     */
    override suspend fun deletePermanently(id: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentProcessor.cancel(id)
                database.withTransaction {
                    conversationDao.deleteForDocument(id)
                    conversationDao.deleteMessageSourcesForDocument(id)
                    documentDao.deleteById(id)
                }
                pageImageStore.deleteDocumentImages(id)
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun purgeTrashOlderThan(cutoff: Long): PamResult<Int> =
        withContext(ioDispatcher) {
            try {
                val expired = documentDao.getTrashedOlderThan(cutoff)
                expired.forEach { entity -> deletePermanently(entity.id) }
                PamResult.Success(expired.size)
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

    override suspend fun renameDocument(id: String, title: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) return@withContext PamResult.Success(Unit)
            try {
                documentDao.renameByUser(id, trimmed)
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

    override suspend fun setFieldReviewState(fieldId: String, state: ReviewState): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                when (state) {
                    // An edit carries a value (and a revision): it goes through updateExtractedField, never here.
                    ReviewState.EDITED -> PamResult.Error(PamError.ValidationError("reviewState", "EDITED needs a value; use updateExtractedField"))
                    // Confirming adopts the value and records it, exactly like confirmExtractedField.
                    ReviewState.CONFIRMED -> attributeToUser(fieldId) { it.fieldValue }
                    else -> {
                        val entity = documentDao.getExtractedField(fieldId)
                            ?: return@withContext PamResult.Error(PamError.DatabaseError())
                        val updated = mergeExtraction.applyReviewState(
                            mapper.extractedDataToDomain(entity),
                            state,
                            System.currentTimeMillis(),
                        )
                        documentDao.insertExtractedData(listOf(mapper.extractedDataToEntity(updated)))
                        PamResult.Success(Unit)
                    }
                }
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun setDocumentFamily(documentId: String, familyId: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.setFamilyByUser(documentId, familyId)
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun updateSummary(documentId: String, text: String): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                documentDao.setSummaryByUser(documentId, text.trim())
                PamResult.Success(Unit)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun confirmAllExtractedFields(
        documentId: String,
        onlyConfident: Boolean,
        onlyFieldIds: Set<String>?,
    ): PamResult<List<ExtractedData>> =
        withContext(ioDispatcher) {
            try {
                val now = System.currentTimeMillis()
                val toConfirm = documentDao.getExtractedData(documentId)
                    .map(mapper::extractedDataToDomain)
                    .filter { onlyFieldIds == null || it.id in onlyFieldIds }
                    .filter { it.reviewState == ReviewState.UNREVIEWED && (!onlyConfident || !it.needsReview) }
                if (toConfirm.isEmpty()) return@withContext PamResult.Success(emptyList())

                // Same per-field rule as confirmExtractedField/attributeToUser
                // (mergeExtraction.applyUserEdit with the field's own value as the "new"
                // one — confirming is adopting, see that function's KDoc), but built up in
                // memory and written as two batched calls instead of looping N single-field
                // writes.
                val updates = toConfirm.map { field ->
                    mergeExtraction.applyUserEdit(
                        field = field,
                        newValue = field.fieldValue,
                        now = now,
                        newId = { UuidGenerator.generate() },
                    )
                }
                documentDao.insertExtractedData(updates.map { (updated, _) -> mapper.extractedDataToEntity(updated) })
                fieldRevisionDao.insertAll(updates.map { (_, revision) -> mapper.revisionToEntity(revision) })
                PamResult.Success(toConfirm)
            } catch (e: Exception) {
                PamResult.Error(PamError.DatabaseError(cause = e))
            }
        }

    override suspend fun restoreExtractedFields(fields: List<ExtractedData>): PamResult<Unit> =
        withContext(ioDispatcher) {
            try {
                if (fields.isNotEmpty()) {
                    documentDao.insertExtractedData(fields.map(mapper::extractedDataToEntity))
                }
                PamResult.Success(Unit)
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
                        reviewState = field.reviewState.name,
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

    override fun observeListFields(): Flow<Map<String, List<ExtractedData>>> =
        documentDao.observeListFields()
            .map { rows -> rows.map(mapper::listFieldToDomain).groupBy { it.documentId } }
            .flowOn(ioDispatcher)

    override fun observeFirstPagePaths(): Flow<Map<String, String>> =
        documentDao.observeFirstPages()
            .map { rows -> rows.associate { it.documentId to it.imagePath } }
            .flowOn(ioDispatcher)
}
