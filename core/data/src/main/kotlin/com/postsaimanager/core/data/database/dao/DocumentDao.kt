package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.postsaimanager.core.data.database.entity.DocumentEntity
import com.postsaimanager.core.data.database.entity.DocumentPageEntity
import com.postsaimanager.core.data.database.entity.ExtractedDataEntity
import com.postsaimanager.core.data.mapper.JsonColumns
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    // Every read below that feeds a list, count or search excludes trashed documents
    // (`deletedAt IS NULL`) — a trashed document must not resurface anywhere except the
    // trash itself until it's restored. `getById`/`observeById` stay unfiltered: the detail
    // screen and restore flow need to find a trashed (or just-deleted) document by id.

    @Query("SELECT * FROM documents WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE status = :status AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeByStatus(status: String): Flow<List<DocumentEntity>>

    /** One-shot read, for startup recovery — see `DocumentProcessingRecovery`. */
    @Query("SELECT * FROM documents WHERE status = :status AND deletedAt IS NULL ORDER BY createdAt DESC")
    suspend fun getByStatus(status: String): List<DocumentEntity>

    /**
     * The documents a model of [version] read whose second stage never completed: EXTRACTED, with a family and `enrichmentPending`
     * (set when the first stage is stored, cleared when the second settles). Keyed on the flag, not on a missing summary: a re-read
     * keeps its earlier summary. For startup recovery of a lost ticket.
     */
    @Query(
        "SELECT * FROM documents WHERE status = 'EXTRACTED' AND deletedAt IS NULL AND extractionType IS NOT NULL " +
            "AND enrichmentPending = 1 AND extractorVersion = :version ORDER BY createdAt DESC",
    )
    suspend fun getAwaitingEnrichment(version: String): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE isFavorite = 1 AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeFavorites(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    /**
     * The document imported from the file or group with [hash], for "you added this file on ...": the earliest live (not trashed)
     * one, and only when there is none, the earliest one in the trash (its `deletedAt` tells the caller it is trashed). Like
     * `getById`, this one sees the trash on purpose: a person who deleted a file and shares it again is offered the restore.
     */
    @Query(
        "SELECT * FROM documents WHERE sourceHash = :hash " +
            "ORDER BY (deletedAt IS NOT NULL) ASC, createdAt ASC LIMIT 1",
    )
    suspend fun findBySourceHash(hash: String): DocumentEntity?

    @Query("""
        SELECT d.* FROM documents d
        INNER JOIN document_pages dp ON d.id = dp.documentId
        WHERE dp.ocrText LIKE '%' || :query || '%' AND d.deletedAt IS NULL
        GROUP BY d.id
        ORDER BY d.createdAt DESC
    """)
    fun search(query: String): Flow<List<DocumentEntity>>

    // ── Trash ──

    @Query("UPDATE documents SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun setDeletedAt(id: String, deletedAt: Long)

    @Query("UPDATE documents SET deletedAt = NULL WHERE id = :id")
    suspend fun clearDeletedAt(id: String)

    @Query("SELECT * FROM documents WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrashed(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun getTrashedOlderThan(cutoff: Long): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<DocumentPageEntity>)

    @Transaction
    suspend fun insertDocumentWithPages(document: DocumentEntity, pages: List<DocumentPageEntity>) {
        insert(document)
        insertPages(pages)
    }

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("UPDATE documents SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    /** A person's title: kept by extraction from now on, and no longer a default with a code. */
    @Query(
        "UPDATE documents SET title = :title, isUserTitle = 1, titleSource = 'USER', titleCode = NULL, " +
            "titleArgs = NULL, modifiedAt = :modifiedAt WHERE id = :id",
    )
    suspend fun renameByUser(id: String, title: String, modifiedAt: Long = System.currentTimeMillis())

    /** A person's choice of family: kept by every re-read from now on. Topics stay as they are. */
    @Query(
        "UPDATE documents SET extractionType = :familyId, familySource = 'USER', modifiedAt = :modifiedAt WHERE id = :id",
    )
    suspend fun setFamilyByUser(id: String, familyId: String, modifiedAt: Long = System.currentTimeMillis())

    /** A person's summary: a template code and its arguments no longer describe it, and a re-read keeps it. */
    @Query(
        "UPDATE documents SET summary = :text, summarySource = 'USER', summaryCode = NULL, summaryArgs = NULL, " +
            "modifiedAt = :modifiedAt WHERE id = :id",
    )
    suspend fun setSummaryByUser(id: String, text: String, modifiedAt: Long = System.currentTimeMillis())

    @Query("UPDATE documents SET status = :status, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, modifiedAt: Long = System.currentTimeMillis())

    /** Moves the reading's stage (a `ReadingStage` name, or null for "a new reading starts"). Leaves `modifiedAt` alone: it is progress, not an edit. */
    @Query("UPDATE documents SET readingStage = :stage WHERE id = :id")
    suspend fun updateReadingStage(id: String, stage: String?)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)

    // ── Pages ──
    @Query("SELECT * FROM document_pages WHERE documentId = :docId ORDER BY pageNumber")
    suspend fun getPages(docId: String): List<DocumentPageEntity>

    @Query("SELECT * FROM document_pages WHERE documentId = :docId ORDER BY pageNumber")
    fun observePages(docId: String): Flow<List<DocumentPageEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeById(id: String): Flow<DocumentEntity?>

    // ── Extracted Data ──
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtractedData(data: List<ExtractedDataEntity>)

    @Query("SELECT * FROM extracted_data WHERE documentId = :docId")
    suspend fun getExtractedData(docId: String): List<ExtractedDataEntity>

    @Query("SELECT * FROM extracted_data WHERE documentId = :docId")
    fun observeExtractedData(docId: String): Flow<List<ExtractedDataEntity>>

    @Query("UPDATE extracted_data SET isConfirmed = 1, reviewState = 'CONFIRMED' WHERE id = :id")
    suspend fun confirmExtraction(id: String)

    @Query("SELECT * FROM extracted_data WHERE id = :id")
    suspend fun getExtractedField(id: String): ExtractedDataEntity?

    @Query("DELETE FROM extracted_data WHERE documentId = :docId")
    suspend fun deleteExtractedData(docId: String)

    @Query("UPDATE extracted_data SET fieldName = :name WHERE id = :id")
    suspend fun renameExtractedField(id: String, name: String)

    @Query("UPDATE extracted_data SET fieldValue = :value, fieldName = :name WHERE id = :id")
    suspend fun updateExtractedField(id: String, name: String, value: String)

    @Query("DELETE FROM extracted_data WHERE id = :id")
    suspend fun deleteExtractedField(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSingleExtractedData(data: ExtractedDataEntity)

    // ── List rows: one query each for the whole list, never one per document ──

    /** The columns a list row reads from every field of every live document (no evidence, no bounds). */
    @Query(
        "SELECT id, documentId, fieldName, fieldValue, fieldType, confidence, isConfirmed, source, " +
            "deletedByUser, hasUnreviewedMachineChange, slotKey, role, reviewState FROM extracted_data " +
            "WHERE documentId IN (SELECT id FROM documents WHERE deletedAt IS NULL)",
    )
    fun observeListFields(): Flow<List<ListFieldRow>>

    /** Page 1 of every live document: the lowest page number it has. */
    @Query(
        "SELECT p.documentId AS documentId, p.imagePath AS imagePath FROM document_pages p " +
            "WHERE p.documentId IN (SELECT id FROM documents WHERE deletedAt IS NULL) " +
            "AND p.pageNumber = (SELECT MIN(q.pageNumber) FROM document_pages q WHERE q.documentId = p.documentId)",
    )
    fun observeFirstPages(): Flow<List<FirstPageRow>>

    /** The stored OCR text of every page of every live document, in reading order; see [OcrTextRow]. */
    @Query(
        "SELECT documentId, ocrText FROM document_pages " +
            "WHERE ocrText IS NOT NULL AND documentId IN (SELECT id FROM documents WHERE deletedAt IS NULL) " +
            "ORDER BY documentId, pageNumber",
    )
    suspend fun getAllOcrTexts(): List<OcrTextRow>

    // ── Who a document is for or about (documents.concernedProfileIds: null = not asked yet, [] = asked, nobody) ──

    /** Writes the model's decision for one document, touching nothing else of the row (a stale copy of the document cannot clobber it). */
    @Query("UPDATE documents SET concernedProfileIds = :json WHERE id = :id")
    suspend fun setConcernedProfileIds(id: String, json: String?)

    /** Sets the decision back to "not asked yet" for [ids]. */
    @Query("UPDATE documents SET concernedProfileIds = NULL WHERE id IN (:ids)")
    suspend fun resetConcernedProfileIds(ids: List<String>)

    /**
     * The live documents a model has read (a family stamped by an extractor version, not found values) whose decision is still
     * "not asked yet": the backfill's work list.
     */
    @Query(
        "SELECT id FROM documents WHERE concernedProfileIds IS NULL AND deletedAt IS NULL " +
            "AND status IN ('EXTRACTED', 'REVIEWED', 'ARCHIVED') AND extractorVersion LIKE 'extraction-v2-%' ORDER BY createdAt DESC",
    )
    suspend fun getIdsAwaitingPeopleCheck(): List<String>

    @Query("SELECT id, concernedProfileIds FROM documents WHERE concernedProfileIds LIKE '%' || :quotedId || '%'")
    suspend fun getConcernedContaining(quotedId: String): List<ConcernedRow>

    /**
     * A profile was deleted: its id leaves every document's list, in one transaction (one place, however many documents name it).
     * An emptied list stays `[]` (asked, nobody).
     */
    @Transaction
    suspend fun removeConcernedProfile(profileId: String) {
        getConcernedContaining("\"$profileId\"").forEach { row ->
            val remaining = JsonColumns.decodeNullableStrings(row.concernedProfileIds)?.filterNot { it == profileId } ?: return@forEach
            setConcernedProfileIds(row.id, JsonColumns.encodeNullableStrings(remaining))
        }
    }
}

/** A document's stored decision; see [DocumentDao.getConcernedContaining]. */
data class ConcernedRow(
    val id: String,
    val concernedProfileIds: String?,
)

/** One page's OCR text; see [DocumentDao.getAllOcrTexts]. */
data class OcrTextRow(
    val documentId: String,
    val ocrText: String,
)

/** A field as a list row reads it; see [DocumentDao.observeListFields]. */
data class ListFieldRow(
    val id: String,
    val documentId: String,
    val fieldName: String,
    val fieldValue: String,
    val fieldType: String,
    val confidence: Float,
    val isConfirmed: Boolean,
    val source: String,
    val deletedByUser: Boolean,
    val hasUnreviewedMachineChange: Boolean,
    val slotKey: String?,
    val role: String?,
    val reviewState: String,
)

/** Page 1's image of a document; see [DocumentDao.observeFirstPages]. */
data class FirstPageRow(
    val documentId: String,
    val imagePath: String,
)
