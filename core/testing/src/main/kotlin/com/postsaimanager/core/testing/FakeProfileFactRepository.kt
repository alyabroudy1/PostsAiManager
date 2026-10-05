package com.postsaimanager.core.testing

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.form.FormDataKeys
import com.postsaimanager.core.domain.repository.ProfileFactRepository
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.ProfileFact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [ProfileFactRepository] for tests; mirrors the real one (unique per (profile, key), sensitivity from the registry). */
class FakeProfileFactRepository : ProfileFactRepository {

    private val all = MutableStateFlow<List<ProfileFact>>(emptyList())
    private var counter = 0

    /** When set, every write fails with this error. */
    var failWith: PamError? = null

    fun seed(vararg facts: ProfileFact) {
        all.value = all.value + facts
    }

    override fun observeFacts(profileId: String): Flow<List<ProfileFact>> =
        all.map { list -> list.filter { it.profileId == profileId } }

    override suspend fun facts(profileId: String): List<ProfileFact> = all.value.filter { it.profileId == profileId }

    override suspend fun upsert(
        profileId: String,
        key: String,
        value: String,
        source: FactSource,
        sourceDocumentId: String?,
    ): PamResult<ProfileFact> {
        failWith?.let { return PamResult.Error(it) }
        val existing = all.value.firstOrNull { it.profileId == profileId && it.key == key }
        val fact = ProfileFact(
            id = existing?.id ?: "fact-${counter++}",
            profileId = profileId,
            key = key,
            value = value,
            source = source,
            sourceDocumentId = sourceDocumentId,
            sensitive = FormDataKeys.isSensitive(key),
            createdAt = existing?.createdAt ?: 1L,
            updatedAt = 2L,
        )
        all.value = all.value.filterNot { it.profileId == profileId && it.key == key } + fact
        return PamResult.Success(fact)
    }

    override suspend fun delete(profileId: String, key: String): PamResult<Unit> {
        failWith?.let { return PamResult.Error(it) }
        all.value = all.value.filterNot { it.profileId == profileId && it.key == key }
        return PamResult.Success(Unit)
    }
}
