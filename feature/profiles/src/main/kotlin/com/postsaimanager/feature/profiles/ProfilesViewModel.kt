package com.postsaimanager.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
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
import kotlinx.coroutines.launch
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

    /**
     * The profile awaiting a delete confirmation, or null when no dialog should be showing.
     *
     * Deletion is destructive and not obviously undoable from a list row, so it is modelled as
     * two steps here rather than one: [requestDelete] only records intent, [confirmDelete] is
     * what actually calls [ProfileRepository.deleteProfile]. A screen collecting this can render
     * a confirmation dialog off it directly, and — just as importantly — this is what makes "no
     * delete on first tap" a property of the ViewModel itself, testable without Compose.
     */
    private val _pendingDeletion = MutableStateFlow<Profile?>(null)
    val pendingDeletion: StateFlow<Profile?> = _pendingDeletion.asStateFlow()

    /** A one-off message for the screen to show (e.g. in a snackbar) and then [consumeMessage]. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun requestDelete(profile: Profile) {
        _pendingDeletion.value = profile
    }

    fun cancelDelete() {
        _pendingDeletion.value = null
    }

    /**
     * Deletes the profile passed to the most recent [requestDelete], if any is still pending.
     *
     * [uiState] does not need updating here: it observes [ProfileRepository.getProfiles], which
     * reflects the deletion on its own. A failure is surfaced through [message] rather than
     * swallowed — silently doing nothing would leave the user staring at a profile that looks
     * deleted (the dialog closed) but is not, with no way to tell why the delete never took.
     */
    fun confirmDelete() {
        val profile = _pendingDeletion.value ?: return
        _pendingDeletion.value = null
        viewModelScope.launch {
            when (val result = profileRepository.deleteProfile(profile.id)) {
                is PamResult.Success -> Unit
                is PamResult.Error -> _message.value = result.error.userMessage
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}

sealed interface ProfilesUiState {
    data object Loading : ProfilesUiState
    data object Empty : ProfilesUiState
    data class Success(val profiles: List<Profile>) : ProfilesUiState
    data class Error(val message: String) : ProfilesUiState
}
