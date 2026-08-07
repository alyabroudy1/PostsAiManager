package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.SourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    fun clear() {
        documents.value = emptyList()
    }

    private fun <T> guard(block: () -> PamResult<T>): PamResult<T> =
        failWith?.let { PamResult.Error(it) } ?: block()

    override fun getDocuments(): Flow<List<Document>> =
        throwOnObserve?.let { error -> kotlinx.coroutines.flow.flow { throw error } } ?: documents

    override fun getDocumentsByStatus(status: DocumentStatus): Flow<List<Document>> =
        documents.map { list -> list.filter { it.status == status } }

    override fun getFavoriteDocuments(): Flow<List<Document>> =
        documents.map { list -> list.filter { it.isFavorite } }

    override fun searchDocuments(query: String): Flow<List<Document>> =
        documents.map { list -> list.filter { it.title.contains(query, ignoreCase = true) } }

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

    override suspend fun deleteDocument(id: String): PamResult<Unit> = guard {
        documents.value = documents.value.filterNot { it.id == id }
        PamResult.Success(Unit)
    }

    override suspend fun toggleFavorite(id: String): PamResult<Unit> = guard {
        documents.value = documents.value.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
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
            fields.map { if (it.id == fieldId) it.copy(isConfirmed = true) else it }
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
            fields.map { if (it.id == fieldId) it.copy(fieldName = name, fieldValue = value) else it }
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
}

/** Convenience builder for test documents. */
fun testDocument(
    id: String = "d1",
    title: String = "Test Document",
    status: DocumentStatus = DocumentStatus.NEW,
    isFavorite: Boolean = false,
    createdAt: Long = 0L,
) = Document(
    id = id,
    title = title,
    status = status,
    sourceType = SourceType.CAMERA,
    isFavorite = isFavorite,
    createdAt = createdAt,
    modifiedAt = createdAt,
)
