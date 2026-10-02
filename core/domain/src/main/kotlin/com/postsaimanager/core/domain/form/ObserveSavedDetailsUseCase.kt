package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.repository.ProfileFactRepository
import com.postsaimanager.core.model.ProfileFact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * A person's saved details in the registry's order (facts whose key left the registry come last, by key), so a list
 * reads the same every time.
 */
class ObserveSavedDetailsUseCase @Inject constructor(
    private val facts: ProfileFactRepository,
) {
    operator fun invoke(profileId: String): Flow<List<ProfileFact>> {
        val order = FormDataKeys.ALL.withIndex().associate { (index, key) -> key.id to index }
        return facts.observeFacts(profileId).map { list ->
            list.sortedWith(compareBy<ProfileFact> { order[it.key] ?: Int.MAX_VALUE }.thenBy { it.key })
        }
    }
}
