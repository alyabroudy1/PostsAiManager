package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.FieldRevisionEntity
import kotlinx.coroutines.flow.Flow

/**
 * The history behind extracted values.
 *
 * Append-only by design — there is no update and no delete-by-id. A revision records that
 * something was true at a moment; editing one would be rewriting the past, and the whole
 * point of keeping it is that a user can see exactly what the machine read and what they
 * changed.
 *
 * Rows go only when the document does, by cascade.
 */
@Dao
interface FieldRevisionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(revisions: List<FieldRevisionEntity>)

    /** Newest first — the order a history is read in. */
    @Query(
        """
        SELECT * FROM field_revisions
        WHERE documentId = :documentId AND fieldName = :fieldName
        ORDER BY createdAt DESC
        """,
    )
    suspend fun getForField(documentId: String, fieldName: String): List<FieldRevisionEntity>

    @Query(
        """
        SELECT * FROM field_revisions
        WHERE documentId = :documentId AND fieldName = :fieldName
        ORDER BY createdAt DESC
        """,
    )
    fun observeForField(documentId: String, fieldName: String): Flow<List<FieldRevisionEntity>>

    @Query("SELECT * FROM field_revisions WHERE documentId = :documentId ORDER BY createdAt DESC")
    suspend fun getForDocument(documentId: String): List<FieldRevisionEntity>

    /** What extraction last read for a slot — the value a revert restores. */
    @Query(
        """
        SELECT * FROM field_revisions
        WHERE documentId = :documentId AND fieldName = :fieldName AND source = 'MACHINE'
        ORDER BY createdAt DESC LIMIT 1
        """,
    )
    suspend fun lastMachineRevision(
        documentId: String,
        fieldName: String,
    ): FieldRevisionEntity?

    @Query("SELECT COUNT(*) FROM field_revisions WHERE documentId = :documentId")
    suspend fun countForDocument(documentId: String): Int
}
