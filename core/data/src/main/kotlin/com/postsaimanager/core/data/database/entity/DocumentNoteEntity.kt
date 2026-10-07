package com.postsaimanager.core.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A durable note about a document (see `DocumentNote`); gone with its document. */
@Entity(
    tableName = "document_notes",
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
data class DocumentNoteEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val text: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val sourceRef: String?,
)
