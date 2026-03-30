package com.postsaimanager.core.domain.repository

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.AppTheme
import com.postsaimanager.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for user preferences (DataStore-backed).
 */
interface UserPreferencesRepository {
    fun getUserPreferences(): Flow<UserPreferences>
    suspend fun setTheme(theme: AppTheme): PamResult<Unit>
    suspend fun setAutoProcess(enabled: Boolean): PamResult<Unit>
    suspend fun setDefaultLanguage(language: String): PamResult<Unit>
    suspend fun setNotificationsEnabled(enabled: Boolean): PamResult<Unit>
    suspend fun setAiModelId(modelId: String?): PamResult<Unit>
    suspend fun setBiometricEnabled(enabled: Boolean): PamResult<Unit>
}
