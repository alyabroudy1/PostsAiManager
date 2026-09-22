package com.postsaimanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.domain.usecase.InferenceSettingsUiState
import com.postsaimanager.core.domain.usecase.ObserveInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.ResetInferenceSettingsUseCase
import com.postsaimanager.core.domain.usecase.UpdateInferenceSettingUseCase
import com.postsaimanager.core.model.AppTheme
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InferenceConfig
import com.postsaimanager.core.model.InferenceOverrides
import com.postsaimanager.core.model.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val observeInferenceSettings: ObserveInferenceSettingsUseCase,
    private val updateInferenceSetting: UpdateInferenceSettingUseCase,
    private val resetInferenceSettings: ResetInferenceSettingsUseCase,
) : ViewModel() {

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

    fun setDefaultLanguage(language: String) {
        viewModelScope.launch { userPreferencesRepository.setDefaultLanguage(language) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setNotificationsEnabled(enabled) }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setBiometricEnabled(enabled) }
    }

    /** [value] is whatever the [ConfigSpec]'s own control produced — see the use case doc. */
    fun setInferenceSetting(key: String, value: Any) {
        viewModelScope.launch { updateInferenceSetting(key, value) }
    }

    fun resetInference() {
        viewModelScope.launch { resetInferenceSettings() }
    }
}
