package com.postsaimanager.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.postsaimanager.core.data.database.dao.ConversationDao
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.DocumentChunkDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.dao.MessageDao
import com.postsaimanager.core.data.database.dao.ProfileDao
import com.postsaimanager.core.data.database.dao.TimelineDao
import com.postsaimanager.core.data.database.entity.*

@Database(
    entities = [
        DocumentEntity::class,
        DocumentPageEntity::class,
        ProfileEntity::class,
        DocumentProfileLinkEntity::class,
        ExtractedDataEntity::class,
        TimelineEventEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        DocumentRelationEntity::class,
        ReminderEntity::class,
        DocumentChunkEntity::class,
        FieldRevisionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class PamDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun profileDao(): ProfileDao
    abstract fun timelineDao(): TimelineDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun fieldRevisionDao(): FieldRevisionDao

    companion object {
        const val DATABASE_NAME = "pam_database"
    }
}
