package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.SuggestionField
import kotlinx.coroutines.flow.Flow

/**
 * The values letters showed for an organisation profile and the user has not answered yet. One owner of the suggestion concept: nothing
 * else reads or writes its table. A suggestion never changes the profile by itself; the use cases that accept one do.
 */
interface ProfileSuggestionRepository {
    /** The suggestions waiting for the user, oldest first. */
    fun observePending(profileId: String): Flow<List<ProfileSuggestion>>

    /** Every suggestion of the profile, pending and dismissed (so a value that was offered before is not offered again). */
    suspend fun all(profileId: String): List<ProfileSuggestion>

    suspend fun get(id: String): ProfileSuggestion?

    /** Stores [suggestion] unless the profile already has one with the same field and value (pending or dismissed): the first offer wins. */
    suspend fun offer(suggestion: ProfileSuggestion)

    /** The user does not want it: it stays as a dismissed row, so the same value is not offered again. */
    suspend fun dismiss(id: String)

    /** Removes the pending suggestions of one field (the field has its value now, whichever way it got it). Dismissed rows stay. */
    suspend fun clearPending(profileId: String, field: SuggestionField)
}
