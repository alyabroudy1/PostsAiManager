package com.postsaimanager.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.ProcessingState
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
    observeDocumentListItems: ObserveDocumentListItemsUseCase,
    documentProcessor: DocumentProcessor,
) : ViewModel() {

    /** See `DocumentsViewModel.processingState` — same idea, for the recent-documents list. */
    val processingState: StateFlow<ProcessingState> = documentProcessor.processingState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingState.Idle)

    val uiState: StateFlow<HomeUiState> =
        observeDocumentListItems()
            .map<List<DocumentListItem>, HomeUiState> { documents ->
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
        val recentDocuments: List<DocumentListItem>,
        val totalCount: Int,
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}
