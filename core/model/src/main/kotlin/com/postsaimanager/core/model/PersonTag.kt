package com.postsaimanager.core.model

/** How a managed person (Me or a family member) relates to a document. */
enum class PersonRole {
    /** The document is addressed to this person. */
    FOR,

    /** The document is about this person while someone else is the addressee (a school letter to the parent about the child). */
    ABOUT,
}

/**
 * One person chip of a document list row: a managed profile the document is for or about.
 *
 * @property displayName the short name the chip shows (a first name; the full name when two profiles would read the same).
 * @property isMe the "Me" profile; the row says "For you" instead of the name.
 */
data class PersonTag(
    val profileId: String,
    val displayName: String,
    val role: PersonRole,
    val isMe: Boolean,
)

/** A stored link between a document and a profile (what the entity linker wrote), with the role it was linked in. */
data class DocumentProfileLink(
    val documentId: String,
    val profileId: String,
    val role: ProfileRole,
)
