package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.AppTheme
import com.postsaimanager.core.model.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pam_preferences")

private object PrefsKeys {
    val THEME = stringPreferencesKey("theme")
    val AUTO_PROCESS = booleanPreferencesKey("auto_process_after_scan")
    val DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val AI_MODEL_ID = stringPreferencesKey("ai_model_id")
    val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
}

@Singleton
class UserPreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(PamDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) : UserPreferencesRepository {

    override fun getUserPreferences(): Flow<UserPreferences> =
        context.dataStore.data
            .map { prefs ->
                UserPreferences(
                    theme = prefs[PrefsKeys.THEME]?.let {
                        runCatching { AppTheme.valueOf(it) }.getOrDefault(AppTheme.SYSTEM)
                    } ?: AppTheme.SYSTEM,
                    autoProcessAfterScan = prefs[PrefsKeys.AUTO_PROCESS] ?: true,
                    defaultLanguage = prefs[PrefsKeys.DEFAULT_LANGUAGE] ?: "de",
                    notificationsEnabled = prefs[PrefsKeys.NOTIFICATIONS_ENABLED] ?: true,
                    selectedAiModelId = prefs[PrefsKeys.AI_MODEL_ID],
                    biometricEnabled = prefs[PrefsKeys.BIOMETRIC_ENABLED] ?: false,
                )
            }
            .catch { emit(UserPreferences()) }
            .flowOn(ioDispatcher)

    override suspend fun setTheme(theme: AppTheme): PamResult<Unit> =
        editPrefs { it[PrefsKeys.THEME] = theme.name }

    override suspend fun setAutoProcess(enabled: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.AUTO_PROCESS] = enabled }

    override suspend fun setDefaultLanguage(language: String): PamResult<Unit> =
        editPrefs { it[PrefsKeys.DEFAULT_LANGUAGE] = language }

    override suspend fun setNotificationsEnabled(enabled: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.NOTIFICATIONS_ENABLED] = enabled }

    override suspend fun setAiModelId(modelId: String?): PamResult<Unit> =
        editPrefs {
            if (modelId != null) it[PrefsKeys.AI_MODEL_ID] = modelId
            else it.remove(PrefsKeys.AI_MODEL_ID)
        }

    override suspend fun setBiometricEnabled(enabled: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.BIOMETRIC_ENABLED] = enabled }

    private suspend fun editPrefs(
        block: (MutablePreferences) -> Unit,
    ): PamResult<Unit> = withContext(ioDispatcher) {
        try {
            context.dataStore.edit(block)
            PamResult.Success(Unit)
        } catch (e: Exception) {
            PamResult.Error(com.postsaimanager.core.common.result.PamError.DatabaseError(cause = e))
        }
    }
}
