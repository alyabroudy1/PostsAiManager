package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.DismissedEntityEntity

/**
 * Tombstones for entities the user has said no to — see [DismissedEntityEntity].
 *
 * Append/lookup only, like [FieldRevisionDao]'s history: there is no "undismiss", because
 * nothing in the app currently offers one. Rows go only when the document does, by cascade.
 */
@Dao
interface DismissedEntityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(entity: DismissedEntityEntity)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM dismissed_entities WHERE documentId = :documentId " +
            "AND entityName = :entityName)",
    )
    suspend fun isDismissed(documentId: String, entityName: String): Boolean
}
