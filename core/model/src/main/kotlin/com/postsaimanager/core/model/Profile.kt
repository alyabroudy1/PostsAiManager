package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * A party the user deals with: a person or an organisation. What it is ([kind]) and how it relates to the user's
 * household ([householdRole]) are two separate things.
 */
@Serializable
data class Profile(
    val id: String,
    val kind: ProfileKind = ProfileKind.PERSON,
    /** Null: not part of the household. [HouseholdRole.SELF] is "Me" (at most one); [HouseholdRole.MEMBER] uses [relationship]. */
    val householdRole: HouseholdRole? = null,
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
    /** How this person relates to the user (a [HouseholdRole.MEMBER]); null for "Me" and for anyone outside the household. */
    val relationship: Relationship? = null,
    /** ISO yyyy-MM-dd; the one owner of the birth date (the `birth_date` form key reads it). */
    val birthDate: String? = null,
    /** A sensitive person: their documents follow the sensitive-document chat rules. */
    val sensitive: Boolean = false,
    val createdAt: Long,
    val modifiedAt: Long,
) {
    /** "Me": at most one profile has it (the repository enforces that). */
    val isSelf: Boolean get() = householdRole == HouseholdRole.SELF

    /** A person the user fills forms for: "Me" or a household member. Derived from [householdRole], never stored. */
    val isManaged: Boolean get() = householdRole != null

    /** The legacy one-axis type, derived 1:1 from [kind] and [householdRole]. Read-only; new code uses kind and role. */
    val type: ProfileType get() = ProfileType.of(kind, householdRole)
}

/** What a profile is. */
@Serializable
enum class ProfileKind { PERSON, ORGANISATION }

/** How a person belongs to the user's household; a profile outside it has no role. */
@Serializable
enum class HouseholdRole { SELF, MEMBER }

/**
 * The old single-axis type (what it is plus how it relates to the user), kept readable for one version and mapped 1:1 to
 * [ProfileKind] and [HouseholdRole]. Do not use it in new code.
 */
@Serializable
enum class ProfileType {
    AUTHORITY,
    PERSON,
    FAMILY_MEMBER,
    USER_SELF,
    ;

    val kind: ProfileKind get() = if (this == AUTHORITY) ProfileKind.ORGANISATION else ProfileKind.PERSON

    val householdRole: HouseholdRole?
        get() = when (this) {
            USER_SELF -> HouseholdRole.SELF
            FAMILY_MEMBER -> HouseholdRole.MEMBER
            else -> null
        }

    companion object {
        /** An organisation is always [AUTHORITY]: a household role on one has no legacy equivalent and is ignored. */
        fun of(kind: ProfileKind, role: HouseholdRole?): ProfileType = when {
            kind == ProfileKind.ORGANISATION -> AUTHORITY
            role == HouseholdRole.SELF -> USER_SELF
            role == HouseholdRole.MEMBER -> FAMILY_MEMBER
            else -> PERSON
        }
    }
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
}
