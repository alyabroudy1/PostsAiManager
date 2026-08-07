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

        // Score EVERY candidate and take the highest. Previously `similar.first()` was
        // taken before any scoring, so the "best match" was whatever order Room happened
        // to return — a perfect match further down the list was silently ignored.
        val best = similar
            .map { it to calculateMatchConfidence(it, name, organization, email) }
            .maxByOrNull { it.second }

        // A candidate is only worth showing if it actually resembles the extracted party.
        // The old code branched on `similar.isNotEmpty()`, so a 0-confidence row was
        // presented as a POSSIBLE_MATCH and the user was asked to confirm a link between
        // two entirely unrelated organisations.
        if (best != null && best.second >= MIN_SUGGESTION_CONFIDENCE) {
            val (bestMatch, confidence) = best

            return ProfileSuggestion(
                role = role,
                matchType = if (confidence >= EXACT_MATCH_CONFIDENCE) {
                    MatchType.EXACT_MATCH
                } else {
                    MatchType.POSSIBLE_MATCH
                },
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
            val a = organization.lowercase().trim()
            val b = profile.organization!!.lowercase().trim()
            score += when {
                a == b -> 1.0f
                substringOverlap(a, b) -> 0.8f
                else -> 0f
            }
        }

        // Name match
        if (name != null) {
            checks++
            val a = name.lowercase().trim()
            val b = profile.name.lowercase().trim()
            score += when {
                a == b -> 1.0f
                substringOverlap(a, b) -> 0.7f
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
     * Substring containment, guarded against matches that carry no identifying signal.
     *
     * A bare legal form — "AG", "GmbH", "e.V." — appears in a large share of German
     * organisation names, so matching on it alone scored 0.8 against most of the
     * database. A plain length floor was the first attempt and was too blunt: it also
     * rejected legitimately short personal names such as "Max". Excluding the legal
     * forms explicitly is the targeted fix; the length floor stays only as a backstop
     * against one- and two-character fragments.
     */
    private fun substringOverlap(a: String, b: String): Boolean {
        val shorter = if (a.length <= b.length) a else b
        if (shorter.length < MIN_SUBSTRING_MATCH_LENGTH) return false
        if (shorter.trim().trimEnd('.') in NON_IDENTIFYING_TOKENS) return false
        return a.contains(b) || b.contains(a)
    }

    companion object {
        /** At or above this, the match is strong enough to offer auto-linking. */
        const val EXACT_MATCH_CONFIDENCE = 0.95f

        /** Below this, a candidate is not worth showing at all — suggest a new profile. */
        const val MIN_SUGGESTION_CONFIDENCE = 0.5f

        /** Shortest string that may participate in substring matching. */
        const val MIN_SUBSTRING_MATCH_LENGTH = 3

        /** Legal forms and generic words that identify nobody on their own. */
        val NON_IDENTIFYING_TOKENS = setOf(
            "ag", "gmbh", "mbh", "kg", "ohg", "gbr", "ug", "se", "e.v", "ev",
            "ltd", "inc", "llc", "plc", "co", "corp", "sa", "nv", "bv",
            "gmbh & co", "und", "and", "der", "die", "das", "the",
        )
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

        // Backfill only the contact details the profile is missing; never clobber
        // values the user already has.
        //
        // `modifiedAt` is deliberately NOT set here. It used to be part of this copy(),
        // which made `enriched != profile` always true — the guard was dead code and
        // every link issued a database write, leaving modifiedAt meaningless as a
        // "last actually changed" signal.
        val enriched = profile.copy(
            phone = profile.phone ?: suggestion.extractedPhone,
            email = profile.email ?: suggestion.extractedEmail,
            street = profile.street ?: suggestion.extractedAddress,
        )
        if (enriched != profile) {
            profileRepository.updateProfile(
                enriched.copy(modifiedAt = System.currentTimeMillis()),
            )
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
