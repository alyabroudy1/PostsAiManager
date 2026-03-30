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
    val createdAt: Long,
    val modifiedAt: Long,
)

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
}
