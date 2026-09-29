package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Timeline event tracking all actions on a document.
 */
@Serializable
data class TimelineEvent(
    val id: String,
    val documentId: String,
    val eventType: TimelineEventType,
    val title: String,
    val description: String? = null,
    val data: String? = null,
    val referenceId: String? = null,
    val referenceType: String? = null,
    val createdAt: Long,
)

@Serializable
enum class TimelineEventType {
    DOCUMENT_SCANNED,
    TEXT_EXTRACTED,
    ENTITIES_EXTRACTED,
    PROFILE_LINKED,
    PROFILE_CREATED,
    RELATION_ADDED,
    AI_CHAT_LOCAL,
    AI_CHAT_ONLINE,
    SUMMARY_GENERATED,
    DRAFT_CREATED,
    PDF_GENERATED,
    EMAIL_PREPARED,
    SHARED,
    TAG_ADDED,
    DOCUMENT_MODIFIED,
    DEADLINE_SET,
    REMINDER_SET,

    /**
     * A processing run ended without extracting anything — see
     * [com.postsaimanager.core.model.DocumentStatus.FAILED]. [TimelineEvent.data] carries a
     * short machine-readable reason code (`"no_pages"`, `"error"`) the detail screen can
     * branch on; [TimelineEvent.description] carries the human-readable detail.
     */
    PROCESSING_FAILED,
}
