package com.postsaimanager.core.model

import kotlinx.serialization.Serializable

/**
 * User preferences stored via DataStore.
 */
@Serializable
data class UserPreferences(
    val theme: AppTheme = AppTheme.SYSTEM,
    val autoProcessAfterScan: Boolean = true,
    val defaultLanguage: String = "de",
    val notificationsEnabled: Boolean = true,
    val selectedAiModelId: String? = null,
    val biometricEnabled: Boolean = false,
    /**
     * Whether the app has already asked the user, once, for the runtime `POST_NOTIFICATIONS`
     * permission (API 33+) — see `ScannerViewModel`. Tracked separately from
     * [notificationsEnabled], which is an in-app setting: this is about not nagging for the OS
     * permission a second time, regardless of what the user answered.
     */
    val notificationPermissionRequested: Boolean = false,
)

@Serializable
enum class AppTheme {
    LIGHT,
    DARK,
    SYSTEM,
}
