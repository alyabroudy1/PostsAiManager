package com.postsaimanager.core.data.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.form.PersonDataSource
import com.postsaimanager.core.domain.form.PersonValue
import com.postsaimanager.core.domain.form.ProfileColumns
import com.postsaimanager.core.domain.repository.ProfileFactRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.FormValueSource
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileFact
import javax.inject.Inject

/**
 * Reads a person's details for form filling: the profile's own columns (the keys that name a
 * [com.postsaimanager.core.model.FormDataKey.profileColumn]) and the saved facts, merged. A fact never overrides a
 * column key: the profile stays the one owner of its columns.
 */
class PersonDataSourceImpl @Inject constructor(
    private val profiles: ProfileRepository,
    private val facts: ProfileFactRepository,
) : PersonDataSource {

    override suspend fun valueOf(profileId: String, keyId: String): PersonValue? {
        val column = FormDataKeys.of(keyId)?.let(ProfileColumns::columnOf)
        if (column != null) {
            val profile = profile(profileId) ?: return null
            return columnValue(profile, keyId, column)
        }
        return facts.facts(profileId).firstOrNull { it.key == keyId }?.let(::factValue)
    }

    override suspend fun allOf(profileId: String): Map<String, PersonValue> {
        val result = linkedMapOf<String, PersonValue>()
        facts.facts(profileId).filter { FormDataKeys.of(it.key)?.let(ProfileColumns::columnOf) == null }
            .forEach { result[it.key] = factValue(it) }
        profile(profileId)?.let { profile ->
            for (key in FormDataKeys.ALL) {
                val column = ProfileColumns.columnOf(key) ?: continue
                columnValue(profile, key.id, column)?.let { result[key.id] = it }
            }
        }
        return result
    }

    private suspend fun profile(profileId: String): Profile? =
        (profiles.getProfileById(profileId) as? PamResult.Success)?.data

    private fun columnValue(profile: Profile, keyId: String, column: String): PersonValue? =
        ProfileColumns.read(profile, column)?.let {
            PersonValue(it, FormValueSource.PROFILE, profile.modifiedAt, FormDataKeys.isSensitive(keyId))
        }

    private fun factValue(fact: ProfileFact) =
        PersonValue(fact.value, FormValueSource.FACT, fact.updatedAt, fact.sensitive)
}
