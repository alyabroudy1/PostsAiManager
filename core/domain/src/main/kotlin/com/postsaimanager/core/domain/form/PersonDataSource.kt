package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormValueSource

/** A person's stored value for one [com.postsaimanager.core.model.FormDataKey], with where it came from and when. */
data class PersonValue(
    val value: String,
    /** [FormValueSource.PROFILE] for a profile column, [FormValueSource.FACT] for a remembered fact. */
    val source: FormValueSource,
    val updatedAt: Long,
    val sensitive: Boolean,
)

/**
 * The one reader of a person's details for form filling: a profile's own columns (see
 * [com.postsaimanager.core.model.FormDataKey.profileColumn]) and its remembered facts, merged.
 * Implemented in core/data (F1); read by FillValues (F2/F3).
 */
interface PersonDataSource {
    suspend fun valueOf(profileId: String, keyId: String): PersonValue?

    suspend fun allOf(profileId: String): Map<String, PersonValue>
}
