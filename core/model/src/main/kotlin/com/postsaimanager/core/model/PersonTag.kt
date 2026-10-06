package com.postsaimanager.core.model

/**
 * One person chip of a document list row: a managed profile (Me or a family member) the model decided the document is for or about
 * (see [Document.concernedProfileIds]).
 *
 * @property displayName the short name the chip shows (a first name; the full name when two profiles would read the same).
 * @property isMe the "Me" profile; the row says "You" instead of the name.
 */
data class PersonTag(
    val profileId: String,
    val displayName: String,
    val isMe: Boolean,
)
