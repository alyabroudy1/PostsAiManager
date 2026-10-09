package com.postsaimanager.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.postsaimanager.core.data.database.dao.ContactDao
import com.postsaimanager.core.data.database.dao.ConversationDao
import com.postsaimanager.core.data.database.dao.DismissedEntityDao
import com.postsaimanager.core.data.database.dao.DocumentDao
import com.postsaimanager.core.data.database.dao.DocumentNoteDao
import com.postsaimanager.core.data.database.dao.DocumentChunkDao
import com.postsaimanager.core.data.database.dao.FieldRevisionDao
import com.postsaimanager.core.data.database.dao.FormFillDao
import com.postsaimanager.core.data.database.dao.MessageDao
import com.postsaimanager.core.data.database.dao.ProfileDao
import com.postsaimanager.core.data.database.dao.ProfileEventDao
import com.postsaimanager.core.data.database.dao.ProfileFactDao
import com.postsaimanager.core.data.database.dao.ProfileSuggestionDao
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
        DismissedEntityEntity::class,
        MessageSourceEntity::class,
        ProfileFactEntity::class,
        FormFillEntity::class,
        FormFieldEntity::class,
        ContactPersonEntity::class,
        DocumentContactEntity::class,
        OrganisationReferenceEntity::class,
        DocumentNoteEntity::class,
        CaseEntity::class,
        ProfileEventEntity::class,
        ProfileEventPersonEntity::class,
        ProfileSuggestionEntity::class,
    ],
    version = 29,
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
    abstract fun dismissedEntityDao(): DismissedEntityDao
    abstract fun profileFactDao(): ProfileFactDao
    abstract fun formFillDao(): FormFillDao
    abstract fun contactDao(): ContactDao
    abstract fun documentNoteDao(): DocumentNoteDao
    abstract fun profileEventDao(): ProfileEventDao
    abstract fun profileSuggestionDao(): ProfileSuggestionDao

    companion object {
        const val DATABASE_NAME = "pam_database"
    }
}
