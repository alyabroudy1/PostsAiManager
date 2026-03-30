package com.postsaimanager.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.DeleteDocumentUseCase
import com.postsaimanager.core.domain.document.GetDocumentsUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.Document
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
class DocumentsViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val deleteDocumentUseCase: DeleteDocumentUseCase,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val uiState: StateFlow<DocumentsUiState> =
        _searchQuery
            .flatMapLatest { query ->
                if (query.isBlank()) {
                    documentRepository.getDocuments()
                } else {
                    documentRepository.searchDocuments(query)
                }
            }
            .map<List<Document>, DocumentsUiState> { documents ->
                if (documents.isEmpty()) DocumentsUiState.Empty
                else DocumentsUiState.Success(documents)
            }
            .catch { emit(DocumentsUiState.Error(it.message ?: "Unknown error")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DocumentsUiState.Loading,
            )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onToggleFavorite(documentId: String) {
        viewModelScope.launch {
            documentRepository.toggleFavorite(documentId)
        }
    }

    fun onDeleteDocument(documentId: String) {
        viewModelScope.launch {
            deleteDocumentUseCase(documentId)
        }
    }
}

sealed interface DocumentsUiState {
    data object Loading : DocumentsUiState
    data object Empty : DocumentsUiState
    data class Success(val documents: List<Document>) : DocumentsUiState
    data class Error(val message: String) : DocumentsUiState
}
