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
    suspend fun toggleFavorite(id: String): PamResult<Unit>

    /**
     * A person renames [id]: the title becomes [title] (trimmed; a blank one changes nothing) and is
     * marked as theirs (`isUserTitle`), so extraction never replaces it; a default title's code is
     * cleared, since the words are now real. One targeted write, so it cannot lose a race with
     * processing the way a whole-document update could.
     */
    suspend fun renameDocument(id: String, title: String): PamResult<Unit>
    suspend fun updateDocumentStatus(id: String, status: DocumentStatus): PamResult<Unit>
    suspend fun confirmExtractedField(fieldId: String): PamResult<Unit>

    /**
     * Confirms every field of [documentId] that is not already confirmed and has not been
     * user-deleted, in one batched write rather than one [confirmExtractedField] call per
     * field (5.3) — see `DocumentRepositoryImpl` for how that batching is done.
     *
     * @return the confirmed fields exactly as they were *before* confirming — nothing but a
     *   caller passing this list straight back to [restoreExtractedFields] undoes the action.
     */
    suspend fun confirmAllExtractedFields(documentId: String): PamResult<List<ExtractedData>>

    /**
     * Writes [fields] back verbatim — the undo half of [confirmAllExtractedFields]. Cheap:
     * one batched write and no merge/revision bookkeeping, since this restores a state that
     * was already recorded rather than producing a new one.
     */
    suspend fun restoreExtractedFields(fields: List<ExtractedData>): PamResult<Unit>

    suspend fun addExtractedField(field: ExtractedData): PamResult<Unit>
    suspend fun updateExtractedField(fieldId: String, name: String, value: String): PamResult<Unit>
    suspend fun deleteExtractedField(fieldId: String): PamResult<Unit>

    // Reactive queries for detail screen
    fun observeDocument(id: String): Flow<Document?>
    fun observePages(documentId: String): Flow<List<DocumentPage>>
    fun observeExtractedData(documentId: String): Flow<List<ExtractedData>>

    // ── Trash — see documentation/07-document-pipeline.md, "Deleting documents" ──

    /** Trashed documents, most recently deleted first. Powers the "Recently deleted" screen. */
    fun observeTrash(): Flow<List<Document>>

    /**
     * Moves a document to the trash: stamps `deletedAt`, cancels its processing work, and
     * hides it from every list, search and chat-retrieval path. Rows and files are left
     * alone so [restore] is a plain field flip. Idempotent.
     */
    suspend fun moveToTrash(id: String): PamResult<Unit>

    /** Brings a trashed document back. Its conversation, if any, becomes visible again. */
    suspend fun restore(id: String): PamResult<Unit>

    /**
     * Deletes a document for good: its row (children cascade), its conversation(s) and any
     * stray `message_sources` elsewhere that cite it, then its page images. See
     * `DocumentRepositoryImpl.deletePermanently` for the exact order and why.
     */
    suspend fun deletePermanently(id: String): PamResult<Unit>

    /** Permanently deletes every document trashed before [cutoff]. Returns how many. */
    suspend fun purgeTrashOlderThan(cutoff: Long): PamResult<Int>
}
