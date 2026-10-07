package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.DocumentNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentNoteDao {

    /** Pinned first, then the newest edit first. */
    @Query("SELECT * FROM document_notes WHERE documentId = :documentId ORDER BY pinned DESC, updatedAt DESC, createdAt DESC")
    fun observeByDocument(documentId: String): Flow<List<DocumentNoteEntity>>

    @Query("SELECT * FROM document_notes WHERE documentId = :documentId ORDER BY pinned DESC, updatedAt DESC, createdAt DESC")
    suspend fun getByDocument(documentId: String): List<DocumentNoteEntity>

    @Query("SELECT * FROM document_notes WHERE profileId = :profileId ORDER BY pinned DESC, updatedAt DESC, createdAt DESC")
    fun observeByProfile(profileId: String): Flow<List<DocumentNoteEntity>>

    @Query("SELECT * FROM document_notes WHERE profileId = :profileId ORDER BY pinned DESC, updatedAt DESC, createdAt DESC")
    suspend fun getByProfile(profileId: String): List<DocumentNoteEntity>

    /** The notes of the all-documents chat: those of a person and the household-wide ones (every note that is not about a document). */
    @Query("SELECT * FROM document_notes WHERE documentId IS NULL ORDER BY pinned DESC, updatedAt DESC, createdAt DESC")
    fun observeOutsideDocuments(): Flow<List<DocumentNoteEntity>>

    @Query("SELECT * FROM document_notes WHERE documentId IS NULL ORDER BY pinned DESC, updatedAt DESC, createdAt DESC")
    suspend fun getOutsideDocuments(): List<DocumentNoteEntity>

    @Query("SELECT * FROM document_notes WHERE id = :id")
    suspend fun getById(id: String): DocumentNoteEntity?

    @Query("SELECT * FROM document_notes WHERE documentId = :documentId AND source = :source AND sourceRef = :sourceRef LIMIT 1")
    suspend fun getByRef(documentId: String, source: String, sourceRef: String): DocumentNoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: DocumentNoteEntity)

    @Query("DELETE FROM document_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM document_notes WHERE source = :source AND sourceRef = :sourceRef")
    suspend fun deleteByRef(source: String, sourceRef: String)
}
