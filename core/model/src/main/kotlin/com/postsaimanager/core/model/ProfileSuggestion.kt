package com.postsaimanager.core.model

/**
 * A candidate link between extracted sender/receiver data and a profile — or, when nothing
 * on file resembles it closely enough, an offer to create one.
 */
data class ProfileSuggestion(
    val role: ProfileRole,
    val matchType: MatchType,
    val existingProfile: Profile?,
    val confidence: Float,
    val extractedName: String?,
    val extractedOrganization: String?,
    val extractedEmail: String?,
    val extractedPhone: String?,
    val extractedAddress: String?,
    val documentId: String,
    val isAutoLinked: Boolean,
)

enum class MatchType {
    EXACT_MATCH,      // >= 95% confidence → auto-link suggested
    POSSIBLE_MATCH,   // < 95% → user confirms
    NEW_PROFILE,      // No match → offer to create
}
