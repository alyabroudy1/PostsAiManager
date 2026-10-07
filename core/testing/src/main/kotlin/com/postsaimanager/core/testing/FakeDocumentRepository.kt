package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.FamilySource
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.SourceType
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.ValueSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * In-memory [DocumentRepository] for tests.
 *
 * Backed by [MutableStateFlow] so the reactive queries emit on every mutation, which is
 * what ViewModel tests need in order to observe state transitions.
 */
class FakeDocumentRepository : DocumentRepository {

    private val documents = MutableStateFlow<List<Document>>(emptyList())
    private val pages = MutableStateFlow<Map<String, List<DocumentPage>>>(emptyMap())
    private val extracted = MutableStateFlow<Map<String, List<ExtractedData>>>(emptyMap())

    /** When set, every suspend call fails with this error. */
    var failWith: PamError? = null

    /** When set, the reactive document flow throws — for exercising `catch` branches. */
    var throwOnObserve: Throwable? = null

    fun seed(vararg items: Document) {
        documents.value = documents.value + items
    }

    fun seedExtracted(documentId: String, vararg fields: ExtractedData) {
        extracted.value = extracted.value + (documentId to fields.toList())
    }

    fun seedPages(documentId: String, vararg items: DocumentPage) {
        pages.value = pages.value + (documentId to items.toList())
    }

    /** Documents as an import leaves them: each with its one page (a document without pages is a cut-short import). */
    fun seedImported(vararg items: Document) {
        seed(*items)
        items.forEach { seedPages(it.id, DocumentPage(id = "${it.id}-p1", documentId = it.id, pageNumber = 1, imagePath = "file:///p1.jpg")) }
    }

    fun clear() {
        documents.value = emptyList()
    }

    private fun <T> guard(block: () -> PamResult<T>): PamResult<T> =
        failWith?.let { PamResult.Error(it) } ?: block()

    override fun getDocuments(): Flow<List<Document>> =
        (throwOnObserve?.let { error -> kotlinx.coroutines.flow.flow<List<Document>> { throw error } } ?: documents)
            .map { list -> list.filterNot { it.isTrashed } }

    override fun getDocumentsByStatus(status: DocumentStatus): Flow<List<Document>> =
        documents.map { list -> list.filter { it.status == status && !it.isTrashed } }

    override fun getFavoriteDocuments(): Flow<List<Document>> =
        documents.map { list -> list.filter { it.isFavorite && !it.isTrashed } }

    override fun searchDocuments(query: String): Flow<List<Document>> =
        documents.map { list ->
            list.filter { it.title.contains(query, ignoreCase = true) && !it.isTrashed }
        }

    override suspend fun findBySourceHash(hash: String): Document? =
        documents.value.filter { it.sourceHash == hash }.sortedWith(compareBy({ it.isTrashed }, { it.createdAt })).firstOrNull()

    override suspend fun getDocumentById(id: String): PamResult<Document> = guard {
        documents.value.firstOrNull { it.id == id }
            ?.let { PamResult.Success(it) }
            ?: PamResult.Error(PamError.FileNotFound("No document $id"))
    }

    override suspend fun getDocumentPages(documentId: String): PamResult<List<DocumentPage>> =
        guard { PamResult.Success(pages.value[documentId].orEmpty()) }

    override suspend fun createDocument(
        document: Document,
        pages: List<DocumentPage>,
    ): PamResult<Document> = guard {
        documents.value = documents.value + document
        this.pages.value = this.pages.value + (document.id to pages)
        PamResult.Success(document)
    }

    override suspend fun updateDocument(document: Document): PamResult<Unit> = guard {
        documents.value = documents.value.map { if (it.id == document.id) document else it }
        PamResult.Success(Unit)
    }

    override fun observeTrash(): Flow<List<Document>> =
        documents.map { list -> list.filter { it.isTrashed }.sortedByDescending { it.deletedAt } }

    override suspend fun moveToTrash(id: String): PamResult<Unit> = guard {
        documents.value = documents.value.map {
            if (it.id == id) it.copy(deletedAt = System.currentTimeMillis()) else it
        }
        PamResult.Success(Unit)
    }

    override suspend fun restore(id: String): PamResult<Unit> = guard {
        documents.value = documents.value.map { if (it.id == id) it.copy(deletedAt = null) else it }
        PamResult.Success(Unit)
    }

    override suspend fun deletePermanently(id: String): PamResult<Unit> = guard {
        documents.value = documents.value.filterNot { it.id == id }
        pages.value = pages.value - id
        extracted.value = extracted.value - id
        PamResult.Success(Unit)
    }

    override suspend fun purgeTrashOlderThan(cutoff: Long): PamResult<Int> {
        failWith?.let { return PamResult.Error(it) }
        val expired = documents.value.filter { it.isTrashed && (it.deletedAt ?: 0L) < cutoff }
        expired.forEach { deletePermanently(it.id) }
        return PamResult.Success(expired.size)
    }

    override suspend fun toggleFavorite(id: String): PamResult<Unit> = guard {
        documents.value = documents.value.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
        }
        PamResult.Success(Unit)
    }

    override suspend fun renameDocument(id: String, title: String): PamResult<Unit> = guard {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) {
            documents.value = documents.value.map {
                if (it.id == id) it.copy(title = trimmed, isUserTitle = true, titleCode = null, titleArgs = emptyList()) else it
            }
        }
        PamResult.Success(Unit)
    }

    override suspend fun updateDocumentStatus(
        id: String,
        status: DocumentStatus,
    ): PamResult<Unit> = guard {
        documents.value = documents.value.map { if (it.id == id) it.copy(status = status) else it }
        PamResult.Success(Unit)
    }

    override suspend fun confirmExtractedField(fieldId: String): PamResult<Unit> = guard {
        extracted.value = extracted.value.mapValues { (_, fields) ->
            fields.map { if (it.id == fieldId) it.copy(isConfirmed = true, reviewState = ReviewState.CONFIRMED) else it }
        }
        PamResult.Success(Unit)
    }

    override suspend fun confirmAllExtractedFields(
        documentId: String,
        onlyConfident: Boolean,
        onlyFieldIds: Set<String>?,
    ): PamResult<List<ExtractedData>> = guard {
        val current = extracted.value[documentId].orEmpty()
        val toConfirm = current.filter {
            (onlyFieldIds == null || it.id in onlyFieldIds) &&
                it.reviewState == ReviewState.UNREVIEWED && (!onlyConfident || !it.needsReview)
        }
        if (toConfirm.isNotEmpty()) {
            val confirmIds = toConfirm.map { it.id }.toSet()
            extracted.value = extracted.value + (
                documentId to current.map {
                    if (it.id in confirmIds) {
                        it.copy(isConfirmed = true, source = ValueSource.USER, reviewState = ReviewState.CONFIRMED)
                    } else {
                        it
                    }
                }
                )
        }
        PamResult.Success(toConfirm)
    }

    override suspend fun setFieldReviewState(fieldId: String, state: ReviewState): PamResult<Unit> = guard {
        if (state == ReviewState.EDITED) {
            return@guard PamResult.Error(PamError.ValidationError("reviewState", "EDITED needs a value; use updateExtractedField"))
        }
        if (extracted.value.values.none { fields -> fields.any { it.id == fieldId } }) {
            return@guard PamResult.Error(PamError.DatabaseError())
        }
        extracted.value = extracted.value.mapValues { (_, fields) ->
            fields.map {
                if (it.id != fieldId) {
                    it
                } else {
                    it.copy(
                        reviewState = state,
                        isConfirmed = state == ReviewState.CONFIRMED || state == ReviewState.EDITED,
                        deletedByUser = state == ReviewState.IGNORED,
                        source = if (state == ReviewState.CONFIRMED || state == ReviewState.EDITED) ValueSource.USER else it.source,
                    )
                }
            }
        }
        PamResult.Success(Unit)
    }

    override suspend fun setDocumentFamily(documentId: String, familyId: String): PamResult<Unit> = guard {
        documents.value = documents.value.map {
            if (it.id == documentId) it.copy(extractionType = familyId, familySource = FamilySource.USER) else it
        }
        PamResult.Success(Unit)
    }

    override suspend fun updateSummary(documentId: String, text: String): PamResult<Unit> = guard {
        documents.value = documents.value.map {
            if (it.id == documentId) {
                it.copy(summary = text.trim(), summarySource = SummarySource.USER, summaryCode = null, summaryArgs = emptyList())
            } else {
                it
            }
        }
        PamResult.Success(Unit)
    }

    override suspend fun restoreExtractedFields(fields: List<ExtractedData>): PamResult<Unit> = guard {
        fields.groupBy { it.documentId }.forEach { (docId, restored) ->
            val current = extracted.value[docId].orEmpty()
            val byId = restored.associateBy { it.id }
            extracted.value = extracted.value + (docId to current.map { byId[it.id] ?: it })
        }
        PamResult.Success(Unit)
    }

    override suspend fun addExtractedField(field: ExtractedData): PamResult<Unit> = guard {
        val current = extracted.value[field.documentId].orEmpty()
        extracted.value = extracted.value + (field.documentId to current + field)
        PamResult.Success(Unit)
    }

    override suspend fun updateExtractedField(
        fieldId: String,
        name: String,
        value: String,
    ): PamResult<Unit> = guard {
        extracted.value = extracted.value.mapValues { (_, fields) ->
            // Like the real merge: a person's value protects the row (EDITED), and it is theirs from now on.
            fields.map {
                if (it.id == fieldId) {
                    it.copy(
                        fieldName = name, fieldValue = value, source = ValueSource.USER, isConfirmed = true,
                        deletedByUser = false, reviewState = ReviewState.EDITED,
                    )
                } else {
                    it
                }
            }
        }
        PamResult.Success(Unit)
    }

    override suspend fun deleteExtractedField(fieldId: String): PamResult<Unit> = guard {
        extracted.value = extracted.value.mapValues { (_, fields) ->
            fields.filterNot { it.id == fieldId }
        }
        PamResult.Success(Unit)
    }

    override fun observeDocument(id: String): Flow<Document?> =
        documents.map { list -> list.firstOrNull { it.id == id } }

    override fun observePages(documentId: String): Flow<List<DocumentPage>> =
        pages.map { it[documentId].orEmpty() }

    override fun observeExtractedData(documentId: String): Flow<List<ExtractedData>> =
        extracted.map { it[documentId].orEmpty() }

    override fun observeListFields(): Flow<Map<String, List<ExtractedData>>> =
        combine(documents, extracted) { docs, fields ->
            val live = docs.filterNot { it.isTrashed }.map { it.id }.toSet()
            fields.filterKeys { it in live }.filterValues { it.isNotEmpty() }
        }

    override fun observeFirstPagePaths(): Flow<Map<String, String>> =
        combine(documents, pages) { docs, allPages ->
            val live = docs.filterNot { it.isTrashed }.map { it.id }.toSet()
            allPages.filterKeys { it in live }
                .mapNotNull { (id, list) -> list.minByOrNull { it.pageNumber }?.let { id to it.imagePath } }
                .toMap()
        }

    override suspend fun getOcrTexts(): Map<String, String> {
        val live = documents.value.filterNot { it.isTrashed }.map { it.id }.toSet()
        return pages.value.filterKeys { it in live }
            .mapValues { (_, list) -> list.sortedBy { it.pageNumber }.mapNotNull { it.ocrText }.joinToString("\n") }
            .filterValues { it.isNotEmpty() }
    }

    override suspend fun setConcernedProfiles(documentId: String, profileIds: List<String>) {
        documents.value = documents.value.map { if (it.id == documentId) it.copy(concernedProfileIds = profileIds) else it }
    }

    override suspend fun resetConcernedProfiles(documentIds: Collection<String>) {
        documents.value = documents.value.map { if (it.id in documentIds) it.copy(concernedProfileIds = null) else it }
    }

    override suspend fun getDocumentIdsAwaitingPeopleCheck(): List<String> =
        documents.value.filter { !it.isTrashed && it.concernedProfileIds == null && it.extractorVersion != null }.map { it.id }
}

/** Convenience builder for test documents. */
fun testDocument(
    id: String = "d1",
    title: String = "Test Document",
    status: DocumentStatus = DocumentStatus.NEW,
    isFavorite: Boolean = false,
    createdAt: Long = 0L,
    deletedAt: Long? = null,
    extractorVersion: String? = null,
    extractionType: String? = null,
    language: String? = null,
) = Document(
    language = language,
    extractionType = extractionType,
    id = id,
    title = title,
    status = status,
    sourceType = SourceType.CAMERA,
    isFavorite = isFavorite,
    createdAt = createdAt,
    modifiedAt = createdAt,
    deletedAt = deletedAt,
    extractorVersion = extractorVersion,
)
