package com.postsaimanager.core.data.backup

import android.content.Context
import com.postsaimanager.core.domain.backup.BackupPreferences
import com.postsaimanager.core.domain.backup.BackupProgress
import com.postsaimanager.core.domain.backup.BackupSettings
import com.postsaimanager.core.domain.backup.BackupStatus
import com.postsaimanager.core.domain.backup.BackupStatusStore
import com.postsaimanager.core.domain.backup.BackupWorkspace
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two backup switches and the last result, on the app's private preferences (`drive_backup`). They are deliberately not part of a
 * backup: a restored phone keeps its own choices, and its own last-backup line.
 */
@Singleton
class SharedPreferencesBackupState @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupPreferences, BackupStatusStore {

    private val preferences get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val settingsState = MutableStateFlow(readSettings())
    private val statusState = MutableStateFlow(readStatus())
    private val progressState = MutableStateFlow<BackupProgress?>(null)

    override val settings: Flow<BackupSettings> = settingsState.asStateFlow()
    override val status: Flow<BackupStatus> = statusState.asStateFlow()
    override val progress: Flow<BackupProgress?> = progressState.asStateFlow()

    override suspend fun current(): BackupSettings = settingsState.value

    override suspend fun setAutoBackup(enabled: Boolean) {
        runCatching { preferences.edit().putBoolean(KEY_AUTO, enabled).apply() }
        settingsState.value = settingsState.value.copy(autoBackup = enabled)
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) {
        runCatching { preferences.edit().putBoolean(KEY_WIFI_ONLY, wifiOnly).apply() }
        settingsState.value = settingsState.value.copy(wifiOnly = wifiOnly)
    }

    override fun setProgress(progress: BackupProgress?) {
        progressState.value = progress
    }

    override suspend fun recordSuccess(at: Long, sizeBytes: Long) {
        runCatching {
            preferences.edit().putLong(KEY_SUCCESS_AT, at).putLong(KEY_SIZE, sizeBytes).remove(KEY_ERROR).remove(KEY_ERROR_AT).apply()
        }
        statusState.value = BackupStatus(lastSuccessAt = at, lastSizeBytes = sizeBytes)
    }

    override suspend fun recordFailure(at: Long, message: String) {
        runCatching { preferences.edit().putString(KEY_ERROR, message).putLong(KEY_ERROR_AT, at).apply() }
        statusState.value = statusState.value.copy(lastError = message, lastErrorAt = at)
    }

    private fun readSettings(): BackupSettings = runCatching {
        BackupSettings(autoBackup = preferences.getBoolean(KEY_AUTO, false), wifiOnly = preferences.getBoolean(KEY_WIFI_ONLY, true))
    }.getOrDefault(BackupSettings())

    private fun readStatus(): BackupStatus = runCatching {
        BackupStatus(
            lastSuccessAt = preferences.getLong(KEY_SUCCESS_AT, 0L).takeIf { it > 0 },
            lastSizeBytes = preferences.getLong(KEY_SIZE, 0L).takeIf { it > 0 },
            lastError = preferences.getString(KEY_ERROR, null),
            lastErrorAt = preferences.getLong(KEY_ERROR_AT, 0L).takeIf { it > 0 },
        )
    }.getOrDefault(BackupStatus())

    private companion object {
        const val FILE = "drive_backup"
        const val KEY_AUTO = "auto_backup"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_SUCCESS_AT = "last_success_at"
        const val KEY_SIZE = "last_size"
        const val KEY_ERROR = "last_error"
        const val KEY_ERROR_AT = "last_error_at"
    }
}

/** Archives and downloads live in the app's cache folder `backup/`, so the system can reclaim them and nothing of them is backed up or kept. */
class CacheBackupWorkspace @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackupWorkspace {

    private val directory get() = File(context.cacheDir, "backup").apply { mkdirs() }

    override fun newFile(name: String): File = File(directory, name).also { it.delete() }

    override fun discard(file: File) {
        runCatching { file.delete() }
    }
}
