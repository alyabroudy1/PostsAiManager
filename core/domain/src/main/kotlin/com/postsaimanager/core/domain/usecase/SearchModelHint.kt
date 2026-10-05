package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * The chat's one-time hint that answers can show their sources once the search model is installed.
 *
 * The hint is owed while the search model is not installed ([EmbeddingService.checkReady] false) and the user has not dismissed it;
 * a dismissal is stored in the preferences and is final. Without the search model, answers still read the document (keyword passages,
 * or the document in reading order), they just cannot be matched by meaning, so the user is told why sources may be missing.
 */
class SearchModelHint @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val embeddingService: EmbeddingService,
) {

    /**
     * Whether the hint is owed: now, and again after every preference change (a dismissal ends it). The search model's presence is
     * read on each of those, so collect again (a new flow) to see a model installed since.
     */
    fun observe(): Flow<Boolean> =
        preferences.getUserPreferences().map { !it.searchModelHintDismissed && !embeddingService.checkReady() }

    /** The user dismissed the hint, or followed it to the models screen: it is not shown again. */
    suspend fun dismiss() {
        preferences.setSearchModelHintDismissed(true)
    }
}
