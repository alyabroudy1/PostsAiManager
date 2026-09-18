package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.EntityProposalEntity
import kotlinx.coroutines.flow.Flow

/** Pending proposals a person has not yet answered — see [EntityProposalEntity]. */
@Dao
interface EntityProposalDao {

    /**
     * Ignored, not replaced, on a conflict with the unique (documentId, entityNameKey) index:
     * reprocessing a document that already has an unanswered proposal for this entity must
     * leave the original row — including its original id — exactly as it was, not overwrite
     * it with a new id the UI has not seen.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(proposal: EntityProposalEntity)

    @Query("SELECT * FROM entity_proposals WHERE documentId = :documentId ORDER BY createdAt")
    fun getForDocument(documentId: String): Flow<List<EntityProposalEntity>>

    @Query("DELETE FROM entity_proposals WHERE id = :id")
    suspend fun delete(id: String)
}
