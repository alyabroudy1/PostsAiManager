package com.postsaimanager.core.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A durable note (see `DocumentNote`): about a document (gone with it), about a household person (gone with the profile), or about
 * neither (a household-wide note of the all-documents chat). At most one of [documentId] and [profileId] is set.
 */
@Entity(
    tableName = "document_notes",
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
data class DocumentNoteEntity(
    @PrimaryKey val id: String,
    val documentId: String?,
    val profileId: String?,
    val text: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val sourceRef: String?,
)
