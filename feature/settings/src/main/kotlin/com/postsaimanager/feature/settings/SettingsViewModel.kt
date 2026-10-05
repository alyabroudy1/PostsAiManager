package com.postsaimanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.applock.DeviceAuthAvailability
import com.postsaimanager.core.domain.applock.DeviceAuthPurpose
import com.postsaimanager.core.domain.applock.DeviceAuthResult
import com.postsaimanager.core.domain.applock.DeviceAuthenticator
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.domain.usecase.InferenceSettingsUiState
import com.postsaimanager.core.domain.usecase.ObserveInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.ResetInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.UpdateInferenceSettingUseCase
import com.postsaimanager.core.model.AppLockTimeouts
import com.postsaimanager.core.model.AppTheme
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why turning the app lock on did not happen. The screen decides the wording. */
enum class AppLockNotice {
    /** No biometric and no screen lock set up: offer the system security settings. */
    NotEnrolled,

    /** The device cannot authenticate at all. */
    Unavailable,

    /** The prompt ended in an error rather than a cancel. */
    AuthenticationFailed,
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val observeInferenceSettings: ObserveInferenceSettingsUseCase,
    private val updateInferenceSetting: UpdateInferenceSettingUseCase,
    private val resetInferenceSettings: ResetInferenceSettingsUseCase,
    private val deviceAuthenticator: DeviceAuthenticator,
    private val externalFlowGuard: ExternalFlowGuard,
) : ViewModel() {

    private var securitySettingsFlow: ExternalFlowToken? = null

    /**
     * Call just before sending the user to the system's security/enrolment settings, so coming
     * back does not lock them out. Pair with [onSecuritySettingsLaunchFailed] if nothing opened.
     */
    fun onOpeningSecuritySettings() {
        externalFlowGuard.finish(securitySettingsFlow)
        securitySettingsFlow = externalFlowGuard.expect("security-settings")
    }

    fun onSecuritySettingsLaunchFailed() {
        externalFlowGuard.finish(securitySettingsFlow)
        securitySettingsFlow = null
    }

    override fun onCleared() {
        externalFlowGuard.finish(securitySettingsFlow)
    }

    private val _appLockNotice = MutableStateFlow<AppLockNotice?>(null)

    /** Why the last attempt to turn the app lock on did not take effect; null when nothing to say. */
    val appLockNotice: StateFlow<AppLockNotice?> = _appLockNotice.asStateFlow()

    val preferences: StateFlow<UserPreferences> =
        userPreferencesRepository.getUserPreferences()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UserPreferences(),
            )

    val inferenceSettings: StateFlow<InferenceSettingsUiState> =
        observeInferenceSettings()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = InferenceSettingsUiState(
                    schema = emptyList(),
                    effectiveConfig = InferenceConfig(contextTokens = 4096, threads = 2),
                    overrides = InferenceOverrides.NONE,
                ),
            )

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { userPreferencesRepository.setTheme(theme) }
    }

    fun setAutoProcess(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAutoProcess(enabled) }
    }

    fun setUpdateOlderLettersAutomatically(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setUpdateOlderLettersAutomatically(enabled) }
    }

    fun setDefaultLanguage(language: String) {
        viewModelScope.launch { userPreferencesRepository.setDefaultLanguage(language) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setNotificationsEnabled(enabled) }
    }

    /**
     * Turns the app lock on or off. Off needs nothing — the user is already inside the app.
     * On requires a successful authentication first, so a household cannot lock itself out with
     * a credential nobody can produce, and so the toggle proves the prompt works on this device;
     * if the device has nothing enrolled the switch stays off and [appLockNotice] says why.
     */
    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                userPreferencesRepository.setBiometricEnabled(false)
                return@launch
            }
            when (deviceAuthenticator.availability()) {
                DeviceAuthAvailability.NOT_ENROLLED -> _appLockNotice.value = AppLockNotice.NotEnrolled
                DeviceAuthAvailability.UNAVAILABLE -> _appLockNotice.value = AppLockNotice.Unavailable
                DeviceAuthAvailability.AVAILABLE ->
                    when (deviceAuthenticator.authenticate(DeviceAuthPurpose.ENABLE_APP_LOCK)) {
                        DeviceAuthResult.Success -> userPreferencesRepository.setBiometricEnabled(true)
                        DeviceAuthResult.Cancelled -> Unit
                        DeviceAuthResult.Failed -> _appLockNotice.value = AppLockNotice.AuthenticationFailed
                    }
            }
        }
    }

    fun setAppLockTimeoutMinutes(minutes: Int) {
        if (minutes !in AppLockTimeouts.OPTIONS_MINUTES) return
        viewModelScope.launch { userPreferencesRepository.setAppLockTimeoutMinutes(minutes) }
    }

    fun dismissAppLockNotice() {
        _appLockNotice.value = null
    }

    /** [value] is whatever the [ConfigSpec]'s own control produced — see the use case doc. */
    fun setInferenceSetting(key: String, value: Any) {
        viewModelScope.launch { updateInferenceSetting(key, value) }
    }

    fun resetInference() {
        viewModelScope.launch { resetInferenceSettings() }
    }
}
