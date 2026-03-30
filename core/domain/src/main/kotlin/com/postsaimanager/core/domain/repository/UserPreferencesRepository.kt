package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user preferences.
 */
interface UserPreferencesRepository {
    val userPreferences: Flow<UserPreferences>
    suspend fun updatePreferences(update: (UserPreferences) -> UserPreferences)
}
