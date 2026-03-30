package com.postsaimanager.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.postsaimanager.core.data.database.entity.TimelineEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TimelineDao {

    @Query("SELECT * FROM timeline_events WHERE documentId = :docId ORDER BY createdAt DESC")
    fun observeForDocument(docId: String): Flow<List<TimelineEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: TimelineEventEntity)
}
