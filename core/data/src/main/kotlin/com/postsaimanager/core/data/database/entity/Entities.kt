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
    /**
     * Positioned OCR blocks, JSON-encoded.
     *
     * Kept so extraction can re-run without re-reading the page — the expensive stage — and
     * so a later, layout-aware extractor can use pages that were scanned before it existed.
     * Text alone cannot be re-derived into positions.
     */
    val ocrBlocks: String? = null,
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
    /** See [com.postsaimanager.core.model.Profile.sourceDocumentId]. */
    val sourceDocumentId: String? = null,
    val sourceEntityName: String? = null,
)

/**
 * A recognised entity the user has said no to for one document — either by dismissing a
 * proposal outright, or by deleting a profile [EntityLinkingUseCase] auto-created from it.
 *
 * Mirrors `extracted_data.deletedByUser`: without this, [DocumentProcessingPipeline]
 * re-running on the same document would have no memory of the refusal, and would recreate or
 * re-propose the exact thing the user just removed on every subsequent scan.
 *
 * Keyed by (documentId, entityName) rather than a profile id, because a dismissed *proposal*
 * never had a profile to key on in the first place.
 */
@Entity(
    tableName = "dismissed_entities",
    primaryKeys = ["documentId", "entityName"],
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
data class DismissedEntityEntity(
    val documentId: String,
    /** Normalised (trimmed, lower-cased) — see `EntityProfileLinker.normalise`. */
    val entityName: String,
    val dismissedAt: Long,
)

/**
 * A recognised entity [EntityLinkingUseCase] would not act on automatically, persisted so the
 * question survives past the process that discovered it — see `EntityProposalService` and
 * `EntityProposal` in `:core:model`.
 *
 * Keyed by a generated [id] rather than (documentId, entityNameKey) directly, because
 * accepting or dismissing needs to name one row. The uniqueness that stops reprocessing from
 * duplicating a still-pending proposal is enforced instead by the index on
 * (documentId, entityNameKey), combined with `OnConflictStrategy.IGNORE` on insert — the
 * conflicting insert (including its freshly generated id) is dropped, so the original row the
 * UI may already be showing keeps its id.
 */
@Entity(
    tableName = "entity_proposals",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "entityNameKey"], unique = true)],
)
data class EntityProposalEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val entityName: String,
    /** Normalised (trimmed, lower-cased) — see `EntityProfileLinker.normalise`. */
    val entityNameKey: String,
    /** `EntityKind` name. */
    val kind: String,
    /** `EntityRole` name — what the entity was doing in the document. */
    val entityRole: String,
    val relation: String,
    /** `ProfileRole` name — what this would be linked as if accepted. */
    val role: String,
    /** `ProfileType` name. */
    val profileType: String,
    val organization: String?,
    val existingProfileId: String?,
    val confidence: Float,
    val createdAt: Long,
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
    // Unique on the slot, not just indexed on the document. A field is identified by
    // (documentId, fieldName) so re-extraction can be matched against what is stored;
    // without the constraint a merge bug would quietly produce duplicate slots and the
    // next merge would pick between them arbitrarily.
    indices = [Index(value = ["documentId", "fieldName"], unique = true)],
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
    /** `MACHINE` or `USER`; see `ValueSource`. */
    val source: String = "MACHINE",
    val machineValue: String? = null,
    val machineConfidence: Float? = null,
    val deletedByUser: Boolean = false,
    val hasUnreviewedMachineChange: Boolean = false,
    val engineVersion: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * Append-only history for a field slot.
 *
 * Deliberately not indexed by a foreign key to `extracted_data`: rows there are keyed by a
 * regenerated id and a slot can outlive any particular row. The document is the owning
 * entity, so the cascade hangs off that.
 */
@Entity(
    tableName = "field_revisions",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId", "fieldName", "createdAt"])],
)
data class FieldRevisionEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val fieldName: String,
    val value: String,
    val source: String,
    val confidence: Float?,
    val engineVersion: String?,
    val createdAt: Long,
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
    /** The model's reasoning trace, display-only — see [com.postsaimanager.core.model.AiMessage]. */
    val thinking: String? = null,
    val thinkingDurationMs: Long? = null,
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

/**
 * A slice of a document's OCR text with its embedding, for semantic retrieval.
 *
 * Chunked rather than whole-document because a 4–8 k context cannot hold a multi-page
 * letter, and because retrieval is more precise over passages than over whole files.
 *
 * [embedding] is a float32 vector serialised little-endian. Stored as a BLOB rather than
 * in a vector database: a few hundred documents at ~5 chunks each is under a megabyte of
 * floats, and brute-force cosine over that is sub-millisecond. A vector store would be
 * infrastructure without a problem to solve.
 */
@Entity(
    tableName = "document_chunks",
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
data class DocumentChunkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val ordinal: Int,
    val text: String,
    val embedding: ByteArray?,
    /** Which model produced [embedding]; vectors from different models are incomparable. */
    val embeddingModelId: String?,
    val createdAt: Long,
) {
    // ByteArray uses identity equality, so a data class would compare embeddings by
    // reference and silently report equal rows as different.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DocumentChunkEntity) return false
        return id == other.id &&
            documentId == other.documentId &&
            ordinal == other.ordinal &&
            text == other.text &&
            embeddingModelId == other.embeddingModelId &&
            createdAt == other.createdAt &&
            (embedding?.contentEquals(other.embedding) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + ordinal
        result = 31 * result + text.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + (embeddingModelId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}
