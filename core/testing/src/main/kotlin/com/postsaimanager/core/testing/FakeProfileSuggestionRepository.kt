package com.postsaimanager.core.testing

import com.postsaimanager.core.domain.repository.ProfileSuggestionRepository
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.SuggestionField
import com.postsaimanager.core.model.SuggestionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [ProfileSuggestionRepository] for tests: the same "first offer wins per (profile, field, value)" rule as the table. */
class FakeProfileSuggestionRepository : ProfileSuggestionRepository {

    val rows = MutableStateFlow<List<ProfileSuggestion>>(emptyList())

    override fun observePending(profileId: String): Flow<List<ProfileSuggestion>> =
        rows.map { list -> list.filter { it.profileId == profileId && it.status == SuggestionStatus.PENDING } }

    override suspend fun all(profileId: String): List<ProfileSuggestion> = rows.value.filter { it.profileId == profileId }

    override suspend fun get(id: String): ProfileSuggestion? = rows.value.firstOrNull { it.id == id }

    override suspend fun offer(suggestion: ProfileSuggestion) {
        if (rows.value.none { it.profileId == suggestion.profileId && it.field == suggestion.field && it.value == suggestion.value }) {
            rows.value = rows.value + suggestion
        }
    }

    override suspend fun dismiss(id: String) {
        rows.value = rows.value.map { if (it.id == id) it.copy(status = SuggestionStatus.DISMISSED) else it }
    }

    override suspend fun clearPending(profileId: String, field: SuggestionField) {
        rows.value = rows.value.filterNot { it.profileId == profileId && it.field == field && it.status == SuggestionStatus.PENDING }
    }
}
