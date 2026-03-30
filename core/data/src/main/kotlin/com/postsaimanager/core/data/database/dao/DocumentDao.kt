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
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun observeFavorites(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: String): DocumentEntity?

    @Query("""
        SELECT d.* FROM documents d
        INNER JOIN document_pages dp ON d.id = dp.documentId
        WHERE dp.ocrText LIKE '%' || :query || '%'
        GROUP BY d.id
        ORDER BY d.createdAt DESC
    """)
    fun search(query: String): Flow<List<DocumentEntity>>

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

    @Query("UPDATE documents SET status = :status, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, modifiedAt: Long = System.currentTimeMillis())

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

    @Query("UPDATE extracted_data SET isConfirmed = 1 WHERE id = :id")
    suspend fun confirmExtraction(id: String)
}
