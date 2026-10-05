package com.postsaimanager.core.domain.form

import com.postsaimanager.core.model.FormValueSource

/** A [PersonDataSource] over plain maps: profile id to key id to value. */
class FakePersonDataSource(private val people: Map<String, Map<String, PersonValue>>) : PersonDataSource {

    override suspend fun valueOf(profileId: String, keyId: String): PersonValue? = people[profileId]?.get(keyId)

    override suspend fun allOf(profileId: String): Map<String, PersonValue> = people[profileId].orEmpty()

    companion object {
        fun profile(value: String, updatedAt: Long, sensitive: Boolean = false, source: FormValueSource = FormValueSource.PROFILE) =
            PersonValue(value, source, updatedAt, sensitive)
    }
}
