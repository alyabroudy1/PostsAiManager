package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ReviewState
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
     * user-deleted (with [onlyConfident], only those that do not need review: the "Confirm n
     * confident" button), in one batched write rather than one [confirmExtractedField] call per
     * field (5.3) — see `DocumentRepositoryImpl` for how that batching is done.
     *
     * @param onlyFieldIds when not null, only fields with these ids are considered (the Extracted tab confirms its essential rows and
     *   leaves "All details" alone)
     * @return the confirmed fields exactly as they were *before* confirming — nothing but a
     *   caller passing this list straight back to [restoreExtractedFields] undoes the action.
     */
    suspend fun confirmAllExtractedFields(
        documentId: String,
        onlyConfident: Boolean = false,
        onlyFieldIds: Set<String>? = null,
    ): PamResult<List<ExtractedData>>

    /**
     * Sets a field's review state: [ReviewState.CONFIRMED] adopts the stored value,
     * [ReviewState.IGNORED] tombstones it (a re-read never brings it back) and
     * [ReviewState.UNREVIEWED] restores an ignored or confirmed field to "nobody has looked". The
     * legacy `isConfirmed` and `deletedByUser` columns are written in step. An unknown id is an error.
     *
     * [ReviewState.EDITED] is rejected: an edit carries a new value and its revision, so it goes through
     * [updateExtractedField].
     */
    suspend fun setFieldReviewState(fieldId: String, state: ReviewState): PamResult<Unit>

    /**
     * A person chose the family of [documentId] ("Change type"): it is stored as the document's
     * extraction type with `familySource = USER`, so a re-read keeps it. Topics are left as they are.
     */
    suspend fun setDocumentFamily(documentId: String, familyId: String): PamResult<Unit>

    /** A person wrote the summary of [documentId]: stored with `summarySource = USER`, never replaced by a re-read. */
    suspend fun updateSummary(documentId: String, text: String): PamResult<Unit>

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

    /**
     * The fields of every non-trashed document, grouped by document id, in one query: what a list
     * row needs (who, when, how much, which need review) without one read per row. Only the columns
     * a row reads are filled (no evidence, no bounds); a document without fields has no entry.
     */
    fun observeListFields(): Flow<Map<String, List<ExtractedData>>>

    /** The image path of page 1 of every non-trashed document that has a page, by document id. */
    fun observeFirstPagePaths(): Flow<Map<String, String>>

    /** The stored OCR text of every non-trashed document that has some (pages joined in order), by document id; one batch. */
    suspend fun getOcrTexts(): Map<String, String>

    /**
     * Stores who [documentId] is for or about (profile ids; empty: nobody). Writes only that column, so a stale copy of the document
     * cannot undo it and a re-read does not lose it.
     */
    suspend fun setConcernedProfiles(documentId: String, profileIds: List<String>)

    /** Sets the decision of [documentIds] back to "not asked yet" (null), so the next check asks again. */
    suspend fun resetConcernedProfiles(documentIds: Collection<String>)

    /** The live documents a model has read whose decision is still "not asked yet", newest first: the backfill's work list. */
    suspend fun getDocumentIdsAwaitingPeopleCheck(): List<String>

    /** The earliest live (not trashed) document imported from the file or group with this SHA-256, or null. */
    suspend fun findBySourceHash(hash: String): Document?

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
