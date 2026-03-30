package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.result.getOrNull
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches extracted sender/receiver data against existing profiles.
 * Returns suggestions with confidence scores for linking or creating new profiles.
 */
@Singleton
class ProfileMatcher @Inject constructor(
    private val profileRepository: ProfileRepository,
) {
    /**
     * Analyze extracted data and produce profile suggestions for sender and receiver.
     */
    suspend fun matchProfiles(
        documentId: String,
        extractedData: List<ExtractedData>,
    ): List<ProfileSuggestion> {
        val suggestions = mutableListOf<ProfileSuggestion>()

        // Build sender info from extracted fields
        val senderName = extractedData.firstOrNull { it.fieldName == "Sender Name" }?.fieldValue
        val senderOrg = extractedData.firstOrNull { it.fieldName == "Sender Organization" }?.fieldValue
        val senderEmail = extractedData.firstOrNull { it.fieldName == "Sender Email" }?.fieldValue
        val senderPhone = extractedData.firstOrNull { it.fieldName == "Sender Phone" }?.fieldValue
        val senderAddress = extractedData.firstOrNull { it.fieldName == "Sender Address" }?.fieldValue

        if (senderName != null || senderOrg != null) {
            val suggestion = findOrSuggestProfile(
                role = ProfileRole.SENDER,
                name = senderName,
                organization = senderOrg,
                email = senderEmail,
                phone = senderPhone,
                address = senderAddress,
                documentId = documentId,
            )
            if (suggestion != null) suggestions.add(suggestion)
        }

        // Build receiver info
        val receiverName = extractedData.firstOrNull { it.fieldName == "Receiver Name" }?.fieldValue
        val receiverOrg = extractedData.firstOrNull { it.fieldName == "Receiver Organization" }?.fieldValue
        val receiverAddress = extractedData.firstOrNull { it.fieldName == "Receiver Address" }?.fieldValue

        if (receiverName != null || receiverOrg != null) {
            val suggestion = findOrSuggestProfile(
                role = ProfileRole.RECEIVER,
                name = receiverName,
                organization = receiverOrg,
                email = null,
                phone = null,
                address = receiverAddress,
                documentId = documentId,
            )
            if (suggestion != null) suggestions.add(suggestion)
        }

        return suggestions
    }

    private suspend fun findOrSuggestProfile(
        role: ProfileRole,
        name: String?,
        organization: String?,
        email: String?,
        phone: String?,
        address: String?,
        documentId: String,
    ): ProfileSuggestion? {
        val searchName = organization ?: name ?: return null

        // Search existing profiles
        val similar = profileRepository.findSimilarProfiles(searchName, organization).getOrNull() ?: emptyList()

        if (similar.isNotEmpty()) {
            val bestMatch = similar.first()
            val confidence = calculateMatchConfidence(bestMatch, name, organization, email)

            return ProfileSuggestion(
                role = role,
                matchType = if (confidence >= 0.95f) MatchType.EXACT_MATCH else MatchType.POSSIBLE_MATCH,
                existingProfile = bestMatch,
                confidence = confidence,
                extractedName = name,
                extractedOrganization = organization,
                extractedEmail = email,
                extractedPhone = phone,
                extractedAddress = address,
                documentId = documentId,
                isAutoLinked = false,
            )
        }

        // No match found — suggest creating new
        return ProfileSuggestion(
            role = role,
            matchType = MatchType.NEW_PROFILE,
            existingProfile = null,
            confidence = 0f,
            extractedName = name,
            extractedOrganization = organization,
            extractedEmail = email,
            extractedPhone = phone,
            extractedAddress = address,
            documentId = documentId,
            isAutoLinked = false,
        )
    }

    private fun calculateMatchConfidence(
        profile: Profile,
        name: String?,
        organization: String?,
        email: String?,
    ): Float {
        var score = 0f
        var checks = 0

        // Organization exact match is strongest signal
        if (organization != null && profile.organization != null) {
            checks++
            val orgMatch = organization.lowercase().trim() == profile.organization!!.lowercase().trim()
            val orgContains = profile.organization!!.lowercase().contains(organization.lowercase()) ||
                organization.lowercase().contains(profile.organization!!.lowercase())
            score += when {
                orgMatch -> 1.0f
                orgContains -> 0.8f
                else -> 0f
            }
        }

        // Name match
        if (name != null) {
            checks++
            val nameMatch = name.lowercase().trim() == profile.name.lowercase().trim()
            val nameContains = profile.name.lowercase().contains(name.lowercase()) ||
                name.lowercase().contains(profile.name.lowercase())
            score += when {
                nameMatch -> 1.0f
                nameContains -> 0.7f
                else -> 0f
            }
        }

        // Email exact match
        if (email != null && profile.email != null) {
            checks++
            score += if (email.lowercase() == profile.email!!.lowercase()) 1.0f else 0f
        }

        return if (checks > 0) score / checks else 0f
    }

    /**
     * Create a new profile from extracted data and link to document.
     */
    suspend fun createAndLinkProfile(suggestion: ProfileSuggestion): PamResult<Profile> {
        val now = System.currentTimeMillis()
        val profile = Profile(
            id = UuidGenerator.generate(),
            type = if (suggestion.extractedOrganization != null) ProfileType.AUTHORITY else ProfileType.PERSON,
            name = suggestion.extractedOrganization ?: suggestion.extractedName ?: "Unknown",
            organization = suggestion.extractedOrganization,
            phone = suggestion.extractedPhone,
            email = suggestion.extractedEmail,
            street = suggestion.extractedAddress,
            createdAt = now,
            modifiedAt = now,
        )

        val result = profileRepository.createProfile(profile)
        if (result is PamResult.Success) {
            profileRepository.linkProfileToDocument(profile.id, suggestion.documentId, suggestion.role)
        }
        return result
    }

    /**
     * Link an existing profile to a document.
     * Also update profile with any new contact info from extraction.
     */
    suspend fun linkExistingProfile(suggestion: ProfileSuggestion): PamResult<Unit> {
        val profile = suggestion.existingProfile ?: return PamResult.Error(
            com.postsaimanager.core.common.result.PamError.FileNotFound("No profile to link")
        )

        // Update profile with new contact info if missing
        val updated = profile.copy(
            phone = profile.phone ?: suggestion.extractedPhone,
            email = profile.email ?: suggestion.extractedEmail,
            street = profile.street ?: suggestion.extractedAddress,
            modifiedAt = System.currentTimeMillis(),
        )
        if (updated != profile) {
            profileRepository.updateProfile(updated)
        }

        return profileRepository.linkProfileToDocument(profile.id, suggestion.documentId, suggestion.role)
    }
}

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
