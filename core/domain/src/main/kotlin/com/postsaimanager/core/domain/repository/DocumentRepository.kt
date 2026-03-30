package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.TimelineEvent
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for document operations.
 * Defined in domain layer — implemented in data layer.
 */
interface DocumentRepository {
    fun getDocuments(): Flow<List<Document>>
    fun getDocumentsByStatus(status: DocumentStatus): Flow<List<Document>>
    fun getFavoriteDocuments(): Flow<List<Document>>
    fun searchDocuments(query: String): Flow<List<Document>>
    suspend fun getDocumentById(id: String): PamResult<Document>
    suspend fun getDocumentPages(documentId: String): PamResult<List<DocumentPage>>
    suspend fun createDocument(document: Document, pages: List<DocumentPage>): PamResult<Document>
    suspend fun updateDocument(document: Document): PamResult<Unit>
    suspend fun deleteDocument(id: String): PamResult<Unit>
    suspend fun toggleFavorite(id: String): PamResult<Unit>
    suspend fun updateDocumentStatus(id: String, status: DocumentStatus): PamResult<Unit>
    suspend fun confirmExtractedField(fieldId: String): PamResult<Unit>

    // Reactive queries for detail screen
    fun observeDocument(id: String): Flow<Document?>
    fun observePages(documentId: String): Flow<List<DocumentPage>>
    fun observeExtractedData(documentId: String): Flow<List<ExtractedData>>
}
