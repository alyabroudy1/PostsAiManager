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
    /**
     * Whether the optional app lock is on. Named for the first authenticator it shipped with;
     * what it gates is "biometric or device PIN/pattern" — see `AppLockState`.
     */
    val biometricEnabled: Boolean = false,
    /**
     * How long the app may sit in the background before the lock applies again. One of
     * [AppLockTimeouts.OPTIONS_MINUTES].
     */
    val appLockTimeoutMinutes: Int = AppLockTimeouts.DEFAULT_MINUTES,
    /**
     * Whether the app has already asked the user, once, for the runtime `POST_NOTIFICATIONS`
     * permission (API 33+) — see `ScannerViewModel`. Tracked separately from
     * [notificationsEnabled], which is an in-app setting: this is about not nagging for the OS
     * permission a second time, regardless of what the user answered.
     */
    val notificationPermissionRequested: Boolean = false,
    /**
     * Whether letters read by an older version of the extractor are quietly re-read in the background
     * (only while charging or idle) when the extractor improves. Never touches what the user confirmed
     * or edited.
     */
    val updateOlderLettersAutomatically: Boolean = true,
    /**
     * Whether the user dismissed the chat's one-time hint that answers can show their sources once the search model is
     * installed. Never shown again after that, whether or not the model is installed later.
     */
    val searchModelHintDismissed: Boolean = false,
    /**
     * Whether the user dismissed the one-time card on Home that asks them to add themselves and their family, so letters for them
     * are tagged. Never shown again after that.
     */
    val householdPromptDismissed: Boolean = false,
    /**
     * Whether the user chose "Skip for now" on the first-run AI model setup. While no chat model is installed the app then opens
     * Home with an "AI model not installed" banner instead of the setup screen; it is cleared when setup finishes.
     */
    val modelSetupSkipped: Boolean = false,
)

/** The grace periods the app lock offers. 0 means "lock every time the app leaves the screen". */
object AppLockTimeouts {
    const val DEFAULT_MINUTES = 1
    val OPTIONS_MINUTES: List<Int> = listOf(0, 1, 5, 15)
}

@Serializable
enum class AppTheme {
    LIGHT,
    DARK,
    SYSTEM,
}
