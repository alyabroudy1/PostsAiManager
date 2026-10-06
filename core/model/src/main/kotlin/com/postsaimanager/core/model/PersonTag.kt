package com.postsaimanager.core.model

/**
 * One person chip of a document list row: a managed profile (Me or a family member) the model decided the document is for or about.
 *
 * @property displayName the short name the chip shows (a first name; the full name when two profiles would read the same).
 * @property isMe the "Me" profile; the row says "You" instead of the name.
 */
data class PersonTag(
    val profileId: String,
    val displayName: String,
    val isMe: Boolean,
)

/** A stored link between a document and a profile (what the entity linker or the concerned-people decision wrote), with its kind. */
data class DocumentProfileLink(
    val documentId: String,
    val profileId: String,
    val role: ProfileRole,
)
