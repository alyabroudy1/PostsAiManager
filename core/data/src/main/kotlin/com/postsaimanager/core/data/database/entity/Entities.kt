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
    /** See [com.postsaimanager.core.model.Document.extractionPagesRead] (5.4). */
    val extractionPagesRead: Int? = null,
    val extractionTotalPages: Int? = null,
    /**
     * When set, the document is in the trash: hidden from every list, search and chat
     * retrieval path, but its rows and files are kept so it can be restored. Null means
     * "not deleted". See documentation/07-document-pipeline.md, "Deleting documents".
     */
    val deletedAt: Long? = null,
    /** See [com.postsaimanager.core.model.Document.extractionType]. */
    val extractionType: String? = null,
    val extractionTypeConfidence: Float? = null,
    val extractorVersion: String? = null,
    val isUserTitle: Boolean = false,
    /** JSON list of strings; see [com.postsaimanager.core.model.Document.suggestedQuestions]. */
    val suggestedQuestions: String? = null,
    val summary: String? = null,
    /** See [com.postsaimanager.core.model.Document.titleCode]; [titleArgs] is a JSON list of strings. */
    val titleCode: String? = null,
    val titleArgs: String? = null,
    /** JSON list of topic ids; see [com.postsaimanager.core.model.Document.topics]. */
    val topics: String? = null,
    /** `MODEL` or `USER`; null reads as `MODEL`. See `FamilySource`. */
    val familySource: String? = null,
    /** `DEFAULT`, `COMPOSED`, `MODEL` or `USER`; see `TitleSource`. */
    val titleSource: String? = null,
    /** `MODEL`, `TEMPLATE` or `USER`; see `SummarySource`. */
    val summarySource: String? = null,
    val summaryCode: String? = null,
    /** JSON list of strings. */
    val summaryArgs: String? = null,
    val layoutTemplate: String? = null,
    /** See [com.postsaimanager.core.model.Document.enrichmentAttempts]. */
    val enrichmentAttempts: Int = 0,
    /** See [com.postsaimanager.core.model.Document.enrichmentPending]. */
    val enrichmentPending: Boolean = false,
    /**
     * JSON list of objects (`{"kind":"pay","bindings":{"date":"due_date"}}`); see [com.postsaimanager.core.model.Document.actionItems].
     * A value an earlier build stored (a list of plain strings) reads as no actions.
     */
    val actionItems: String? = null,
    /** JSON list of profile ids; null: not checked yet. See [com.postsaimanager.core.model.Document.concernedProfileIds]. */
    val concernedProfileIds: String? = null,
    /** See [com.postsaimanager.core.model.Document.sourceHash]. Added in v21. */
    val sourceHash: String? = null,
    /** See [com.postsaimanager.core.model.Document.originalFilePath]. Added in v21. */
    val originalFilePath: String? = null,
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
    val relationship: String? = null,
    val birthDate: String? = null,
    @ColumnInfo(defaultValue = "0") val sensitive: Boolean = false,
    /** PERSON or ORGANISATION (see `ProfileKind`). Added in v22, filled from [type]. */
    @ColumnInfo(defaultValue = "'PERSON'") val kind: String = "PERSON",
    /** SELF, MEMBER or null (see `HouseholdRole`). Added in v22, filled from [type]. */
    val householdRole: String? = null,
)

/**
 * A person inside one organisation profile (see `ContactPerson`). Gone with the organisation. Added in v22.
 */
@Entity(
    tableName = "contact_persons",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["organisationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("organisationId")],
)
data class ContactPersonEntity(
    @PrimaryKey val id: String,
    val organisationId: String,
    val name: String,
    val title: String?,
    val department: String?,
    val phone: String?,
    val email: String?,
    val room: String?,
    val firstSeen: Long,
    val lastSeen: Long,
    val active: Boolean,
)

/** Which contact handled which document; gone with either side. Added in v22. */
@Entity(
    tableName = "document_contacts",
    primaryKeys = ["documentId", "contactId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContactPersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId"), Index("contactId")],
)
data class DocumentContactEntity(
    val documentId: String,
    val contactId: String,
    val createdAt: Long,
)

/** A number a household person has at one organisation (see `OrganisationReference`). Empty until phase 3. Added in v22. */
@Entity(
    tableName = "organisation_references",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["organisationId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("organisationId"), Index("profileId")],
)
data class OrganisationReferenceEntity(
    @PrimaryKey val id: String,
    val organisationId: String,
    val profileId: String,
    val label: String,
    val value: String,
    val sourceDocumentId: String?,
    val createdAt: Long,
)

/** A remembered detail of a person (see `ProfileFact`); one row per (profile, key). */
@Entity(
    tableName = "profile_facts",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId"), Index(value = ["profileId", "key"], unique = true)],
)
data class ProfileFactEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val key: String,
    val value: String,
    val source: String,
    val sourceDocumentId: String?,
    val sensitive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One fill of a document's form (see `FormFill`): the conversation's state. `roleProfiles`, `confirmedRoles` and `awaiting` are
 * JSON text (lenient on read, see `FormFillMapper`). Gone with its document.
 */
@Entity(
    tableName = "form_fills",
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
data class FormFillEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val status: String,
    val roleProfiles: String,
    val confirmedRoles: String,
    val conversationId: String?,
    val currentFieldId: String?,
    val localeTag: String?,
    val awaiting: String?,
    val roundAsked: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** The way of reading plus the OCR the fields were built from (see `FormFill.readingKey`). */
    val readingKey: String? = null,
)

/** One blank of a form (see `FormField`); `labelBox`, `fillBox` and `options` are JSON text. Gone with its fill. */
@Entity(
    tableName = "form_fields",
    foreignKeys = [
        ForeignKey(
            entity = FormFillEntity::class,
            parentColumns = ["id"],
            childColumns = ["formFillId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("formFillId"), Index("documentId")],
)
data class FormFieldEntity(
    @PrimaryKey val id: String,
    val formFillId: String,
    val documentId: String,
    val page: Int,
    val labelText: String,
    val labelBox: String?,
    val fillBox: String?,
    val kind: String,
    val section: String?,
    val options: String?,
    val dataKey: String?,
    val role: String?,
    val confidence: Float,
    val value: String?,
    val valueSource: String,
    val profileId: String?,
    val reviewState: String,
    val required: Boolean,
    val alreadyFilled: String?,
    val reconfirm: Boolean,
    val skipped: Boolean,
    val orderIndex: Int,
    val updatedAt: Long,
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
    /** The extractor version of this row (the "extractorVersion" of the v2 design). */
    val engineVersion: String? = null,
    val updatedAt: Long = 0L,
    /** See [com.postsaimanager.core.model.ExtractedData.slotKey] and the fields after it. */
    val slotKey: String? = null,
    val role: String? = null,
    val origin: String? = null,
    val aiConfidence: Float? = null,
    val evidence: String? = null,
    /** JSON of a `TextBounds`. */
    val bbox: String? = null,
    /** `UNREVIEWED`, `CONFIRMED`, `EDITED` or `IGNORED`; see `ReviewState`. Kept in step with [isConfirmed] and [deletedByUser]. */
    val reviewState: String = "UNREVIEWED",
    /** JSON list of `FieldAlternative`. */
    val alternatives: String? = null,
    /** See [com.postsaimanager.core.model.ExtractedData.importance]; NULL on a row that is not key information. */
    val importance: Float? = null,
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
    /** See [com.postsaimanager.core.model.TimelineEvent.code]; [args] is a JSON list of strings. */
    val code: String? = null,
    val args: String? = null,
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
    /** See [com.postsaimanager.core.model.AiMessage.incomplete]. */
    val incomplete: Boolean = false,
    /** See [com.postsaimanager.core.model.AiMessage.cutOff]. */
    val cutOff: Boolean = false,
)

/**
 * One passage an assistant [MessageEntity] was grounded on — see
 * [com.postsaimanager.core.model.MessageSource] for what each field means and why the shape
 * is this minimal.
 *
 * A child table rather than a JSON column on `messages`, matching the rest of this schema's
 * one-to-many shapes (`field_revisions`, `entity_proposals`, `dismissed_entities`): a message
 * routinely has 0–4 sources (`SendChatMessageUseCase.RETRIEVAL_LIMIT`), each with its own
 * FK-checkable `documentId` and a natural, queryable `pageNumber` — exactly the case Room's
 * relational tooling (`@Relation`, used by [com.postsaimanager.core.data.database.dao
 * .MessageWithSources]) is for. A JSON blob would give up both: no FK integrity if the
 * source document is deleted, and every reader would need to deserialise it just to render a
 * chip.
 *
 * No FK to `documents`: a source document can be deleted while the conversation that cited
 * it survives (chat history is not deleted alongside a document unless its own conversation
 * is), and the chip this powers already degrades gracefully — see `ChatScreen`'s handling of
 * a source whose document no longer exists.
 */
@Entity(
    tableName = "message_sources",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class MessageSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val documentId: String,
    val pageNumber: Int?,
    val chunkId: String,
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
    /** Which page this passage came from — null for a chunk indexed before 4.0. */
    val pageNumber: Int? = null,
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
            pageNumber == other.pageNumber &&
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
        result = 31 * result + (pageNumber ?: 0)
        return result
    }
}
