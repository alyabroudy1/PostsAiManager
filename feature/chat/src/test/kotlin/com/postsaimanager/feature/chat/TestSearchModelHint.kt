package com.postsaimanager.feature.chat

import com.postsaimanager.core.domain.usecase.SearchModelHint
import com.postsaimanager.core.testing.FakeEmbeddingService
import com.postsaimanager.core.testing.FakeUserPreferencesRepository

/** A [SearchModelHint] over in-memory fakes; the search model is installed unless [searchModelInstalled] says otherwise. */
fun testSearchModelHint(
    preferences: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    searchModelInstalled: Boolean = true,
) = SearchModelHint(preferences, FakeEmbeddingService().apply { isReady = searchModelInstalled })
