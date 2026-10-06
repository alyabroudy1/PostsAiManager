package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * Profile representing a person, authority, or family member
 * that appears in documents.
 */
@Serializable
data class Profile(
    val id: String,
    val type: ProfileType,
    val name: String,
    val organization: String? = null,
    val department: String? = null,
    val street: String? = null,
    val city: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val website: String? = null,
    val reference: String? = null,
    val notes: String? = null,
    val completionScore: Float = 0f,
    val missingFields: List<String> = emptyList(),
    val avatarPath: String? = null,
    /**
     * The document and entity name that machine-created this profile, or null for one a
     * person created directly.
     *
     * Recorded so a deletion can be turned into a tombstone (see `EntityLinkingUseCase`):
     * without it, reprocessing the same document would have no way to know the user already
     * rejected this profile, and would silently recreate it.
     */
    val sourceDocumentId: String? = null,
    val sourceEntityName: String? = null,
    /** How this person relates to the user ("Me", [ProfileType.USER_SELF]); null for "Me" and for non-family profiles. */
    val relationship: Relationship? = null,
    /** ISO yyyy-MM-dd; the one owner of the birth date (the `birth_date` form key reads it). */
    val birthDate: String? = null,
    /** A sensitive person: their documents follow the sensitive-document chat rules. */
    val sensitive: Boolean = false,
    val createdAt: Long,
    val modifiedAt: Long,
) {
    /** "Me": at most one profile has it (the repository enforces that). */
    val isSelf: Boolean get() = type == ProfileType.USER_SELF

    /** A person the user fills forms for: "Me" or a family member. Derived from [type], never stored. */
    val isManaged: Boolean get() = type == ProfileType.USER_SELF || type == ProfileType.FAMILY_MEMBER
}

@Serializable
enum class ProfileType {
    AUTHORITY,
    PERSON,
    FAMILY_MEMBER,
    USER_SELF,
}

/**
 * Role of a profile in relation to a document.
 */
@Serializable
enum class ProfileRole {
    SENDER,
    RECEIVER,
    SUBJECT,
    CASE_WORKER,
    RELATED,

    /**
     * The model read the whole letter and decided it is for or about this managed person ("Me" or a family member).
     * The only link kind the document list's person chips read; written by `DecideConcernedPeopleUseCase`.
     * (A document and a profile have one link row, so this replaces an entity linker's row for the same pair.)
     */
    CONCERNS,
}
