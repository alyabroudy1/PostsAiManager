package com.postsaimanager.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.repository.UserPreferencesRepository
import com.postsaimanager.core.model.AppTheme
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
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> =
        userPreferencesRepository.getUserPreferences()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = UserPreferences(),
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
}
