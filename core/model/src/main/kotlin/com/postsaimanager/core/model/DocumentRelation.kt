package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Relation between two documents.
 */
@Serializable
data class DocumentRelation(
    val id: String,
    val sourceDocId: String,
    val targetDocId: String,
    val relationType: RelationType,
    val createdAt: Long,
)

@Serializable
enum class RelationType {
    REPLY_TO,
    FOLLOW_UP,
    RELATED,
    REFERENCE,
    ATTACHMENT,
    SUPERSEDES,
}

/**
 * A tag that can be applied to documents.
 */
@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: String? = null,
)
