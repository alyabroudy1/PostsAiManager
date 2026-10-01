package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.ProfileFact
import kotlinx.coroutines.flow.Flow

/**
 * The remembered details ("saved details") of a person: one value per (profile, key).
 * Keys come from `FormDataKeys`; keys that live in a profile column are not stored here.
 */
interface ProfileFactRepository {
    fun observeFacts(profileId: String): Flow<List<ProfileFact>>

    suspend fun facts(profileId: String): List<ProfileFact>

    /**
     * Inserts or replaces the value of [key] for [profileId]. The sensitive flag is taken from
     * `FormDataKeys.isSensitive(key)`, never from the caller. A replaced fact keeps its id and creation time.
     */
    suspend fun upsert(
        profileId: String,
        key: String,
        value: String,
        source: FactSource,
        sourceDocumentId: String? = null,
    ): PamResult<ProfileFact>

    suspend fun delete(profileId: String, key: String): PamResult<Unit>
}
