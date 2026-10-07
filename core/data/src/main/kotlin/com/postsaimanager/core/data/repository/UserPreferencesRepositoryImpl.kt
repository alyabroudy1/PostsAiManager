package com.postsaimanager.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.postsaimanager.core.common.dispatcher.Dispatcher
import com.postsaimanager.core.common.dispatcher.PamDispatcher
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.AppLockTimeouts
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
    val APP_LOCK_TIMEOUT_MINUTES = intPreferencesKey("app_lock_timeout_minutes")
    val NOTIFICATION_PERMISSION_REQUESTED =booleanPreferencesKey("notification_permission_requested")
    val UPDATE_OLDER_LETTERS = booleanPreferencesKey("update_older_letters_automatically")
    val SEARCH_MODEL_HINT_DISMISSED = booleanPreferencesKey("search_model_hint_dismissed")
    val MODEL_SETUP_SKIPPED = booleanPreferencesKey("model_setup_skipped")
    val HOUSEHOLD_PROMPT_DISMISSED = booleanPreferencesKey("household_prompt_dismissed")
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
                    appLockTimeoutMinutes = prefs[PrefsKeys.APP_LOCK_TIMEOUT_MINUTES]
                        ?.takeIf { it in AppLockTimeouts.OPTIONS_MINUTES }
                        ?: AppLockTimeouts.DEFAULT_MINUTES,
                    notificationPermissionRequested =
                        prefs[PrefsKeys.NOTIFICATION_PERMISSION_REQUESTED] ?: false,
                    updateOlderLettersAutomatically = prefs[PrefsKeys.UPDATE_OLDER_LETTERS] ?: true,
                    searchModelHintDismissed = prefs[PrefsKeys.SEARCH_MODEL_HINT_DISMISSED] ?: false,
                    modelSetupSkipped = prefs[PrefsKeys.MODEL_SETUP_SKIPPED] ?: false,
                    householdPromptDismissed = prefs[PrefsKeys.HOUSEHOLD_PROMPT_DISMISSED] ?: false,
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

    override suspend fun setAppLockTimeoutMinutes(minutes: Int): PamResult<Unit> =
        editPrefs { it[PrefsKeys.APP_LOCK_TIMEOUT_MINUTES] = minutes }

    override suspend fun setNotificationPermissionRequested(requested: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.NOTIFICATION_PERMISSION_REQUESTED] = requested }

    override suspend fun setUpdateOlderLettersAutomatically(enabled: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.UPDATE_OLDER_LETTERS] = enabled }

    override suspend fun setSearchModelHintDismissed(dismissed: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.SEARCH_MODEL_HINT_DISMISSED] = dismissed }

    override suspend fun setModelSetupSkipped(skipped: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.MODEL_SETUP_SKIPPED] = skipped }

    override suspend fun setHouseholdPromptDismissed(dismissed: Boolean): PamResult<Unit> =
        editPrefs { it[PrefsKeys.HOUSEHOLD_PROMPT_DISMISSED] = dismissed }

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
