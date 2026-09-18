package com.postsaimanager.core.domain.document

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileSuggestion

/**
 * The port through which a feature matches extracted sender/receiver data against existing
 * profiles, and acts on what it finds.
 *
 * Lives in `:core:domain` because features may only see the domain layer (architecture
 * rule 1). `:core:data`'s `ProfileMatcher` is the only implementation exposed here.
 * `:core:data` remains free to keep depending on the concrete `ProfileMatcher` class for its
 * own internal wiring — `EntityProfileLinker` calls `ProfileMatcher.findBestMatch` directly,
 * a capability this port does not expose because no feature needs it. The rule constrains
 * features, not the data layer talking to itself.
 */
interface ProfileMatchingService {

    /** One suggestion per sender/receiver role present in [extractedData]. */
    suspend fun matchProfiles(
        documentId: String,
        extractedData: List<ExtractedData>,
    ): List<ProfileSuggestion>

    /** Creates a new profile from [suggestion]'s extracted fields and links it to its document. */
    suspend fun createAndLinkProfile(suggestion: ProfileSuggestion): PamResult<Profile>

    /**
     * Links [suggestion]'s existing profile to its document, backfilling any contact details
     * the profile is missing without ever overwriting a value it already has.
     */
    suspend fun linkExistingProfile(suggestion: ProfileSuggestion): PamResult<Unit>
}
