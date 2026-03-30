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
)

@Serializable
enum class AppTheme {
    LIGHT,
    DARK,
    SYSTEM,
}
