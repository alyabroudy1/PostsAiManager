package com.postsaimanager.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val status: String,
    val documentType: String?,
    val language: String?,
    val sourceType: String,
    val thumbnailPath: String?,
    val pageCount: Int,
    val isFavorite: Boolean = false,
    @ColumnInfo(index = true) val createdAt: Long,
    @ColumnInfo(index = true) val modifiedAt: Long,
    val syncStatus: String = "LOCAL",
)

@Entity(
    tableName = "document_pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class DocumentPageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val pageNumber: Int,
    val imagePath: String,
    val processedPath: String?,
    val ocrText: String?,
    val ocrConfidence: Float?,
    val width: Int,
    val height: Int,
)

@Entity(
    tableName = "profiles",
    indices = [Index("name"), Index("type")],
)
data class ProfileEntity(
    @PrimaryKey val id: String,
    val type: String,
    val name: String,
    val organization: String?,
    val department: String?,
    val street: String?,
    val city: String?,
    val postalCode: String?,
    val country: String?,
    val phone: String?,
    val email: String?,
    val website: String?,
    val reference: String?,
    val notes: String?,
    val completionScore: Float,
    val missingFields: String?,
    val avatarPath: String?,
    @ColumnInfo(index = true) val createdAt: Long,
    val modifiedAt: Long,
)

@Entity(
    tableName = "document_profile_links",
    primaryKeys = ["documentId", "profileId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("profileId")],
)
data class DocumentProfileLinkEntity(
    val documentId: String,
    val profileId: String,
    val role: String,
    val createdAt: Long,
)

@Entity(
    tableName = "extracted_data",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class ExtractedDataEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val fieldName: String,
    val fieldValue: String,
    val fieldType: String,
    val confidence: Float,
    val pageNumber: Int?,
    val isConfirmed: Boolean = false,
)

@Entity(
    tableName = "timeline_events",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("createdAt")],
)
data class TimelineEventEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val eventType: String,
    val title: String,
    val description: String?,
    val data: String?,
    val referenceId: String?,
    val referenceType: String?,
    val createdAt: Long,
)

@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String?,
)

@Entity(
    tableName = "document_tags",
    primaryKeys = ["documentId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("tagId")],
)
data class DocumentTagEntity(
    val documentId: String,
    val tagId: String,
)

@Entity(
    tableName = "conversations",
    indices = [Index("documentId")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val documentId: String?,
    val aiModelId: String?,
    val modelType: String,
    val title: String,
    val lastMessageAt: Long,
    val messageCount: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val mediaType: String = "TEXT",
    val mediaPath: String?,
    val toolCallId: String?,
    val toolName: String?,
    val toolArgs: String?,
    val toolResult: String?,
    val isStreaming: Boolean = false,
    val createdAt: Long,
)

@Entity(
    tableName = "document_relations",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceDocId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetDocId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sourceDocId"), Index("targetDocId")],
)
data class DocumentRelationEntity(
    @PrimaryKey val id: String,
    val sourceDocId: String,
    val targetDocId: String,
    val relationType: String,
    val createdAt: Long,
)

@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("dueDate")],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val title: String,
    val description: String?,
    val dueDate: Long,
    val isCompleted: Boolean = false,
    val createdAt: Long,
)
