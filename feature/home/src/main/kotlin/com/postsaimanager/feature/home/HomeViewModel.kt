package com.postsaimanager.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.document.GetDocumentsUseCase
import com.postsaimanager.core.model.Document
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    getDocumentsUseCase: GetDocumentsUseCase,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        getDocumentsUseCase()
            .map<List<Document>, HomeUiState> { documents ->
                if (documents.isEmpty()) {
                    HomeUiState.Empty
                } else {
                    HomeUiState.Success(
                        recentDocuments = documents.take(10),
                        totalCount = documents.size,
                    )
                }
            }
            .catch { emit(HomeUiState.Error(it.message ?: "Unknown error")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeUiState.Loading,
            )
}

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Empty : HomeUiState
    data class Success(
        val recentDocuments: List<Document>,
        val totalCount: Int,
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}
