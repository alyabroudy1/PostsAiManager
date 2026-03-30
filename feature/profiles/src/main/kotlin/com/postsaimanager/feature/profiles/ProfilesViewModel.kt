package com.postsaimanager.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.Profile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val uiState: StateFlow<ProfilesUiState> =
        _searchQuery
            .flatMapLatest { query ->
                if (query.isBlank()) profileRepository.getProfiles()
                else profileRepository.searchProfiles(query)
            }
            .map<List<Profile>, ProfilesUiState> { profiles ->
                if (profiles.isEmpty()) ProfilesUiState.Empty
                else ProfilesUiState.Success(profiles)
            }
            .catch { emit(ProfilesUiState.Error(it.message ?: "Unknown error")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ProfilesUiState.Loading,
            )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }
}

sealed interface ProfilesUiState {
    data object Loading : ProfilesUiState
    data object Empty : ProfilesUiState
    data class Success(val profiles: List<Profile>) : ProfilesUiState
    data class Error(val message: String) : ProfilesUiState
}
