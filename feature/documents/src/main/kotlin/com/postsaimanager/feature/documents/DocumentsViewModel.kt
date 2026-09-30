package com.postsaimanager.feature.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.MoveDocumentToTrashUseCase
import com.postsaimanager.core.domain.document.RestoreDocumentUseCase
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.ProcessingState
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
    private val moveToTrashUseCase: MoveDocumentToTrashUseCase,
    private val restoreDocumentUseCase: RestoreDocumentUseCase,
    private val observeDocumentListItems: ObserveDocumentListItemsUseCase,
    documentProcessor: DocumentProcessor,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * Whichever document is currently being read/understood, for the list row that matches
     * its id to show real progress ("page x/y") instead of a static "Processing" label — see
     * `DocumentProcessor.processingState`'s doc comment for why this is one flow, not one
     * per document.
     */
    val processingState: StateFlow<ProcessingState> = documentProcessor.processingState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingState.Idle)

    val uiState: StateFlow<DocumentsUiState> =
        _searchQuery
            .flatMapLatest { query -> observeDocumentListItems(query) }
            .map<List<DocumentListItem>, DocumentsUiState> { documents ->
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

    /** Swipe-to-delete on a list row. The list's own snackbar (in `DocumentsScreen`) offers Undo. */
    fun onDeleteDocument(documentId: String) {
        viewModelScope.launch {
            moveToTrashUseCase(documentId)
        }
    }

    /** Undo for [onDeleteDocument] — brings the document straight back. */
    fun onRestoreDocument(documentId: String) {
        viewModelScope.launch {
            restoreDocumentUseCase(documentId)
        }
    }
}

sealed interface DocumentsUiState {
    data object Loading : DocumentsUiState
    data object Empty : DocumentsUiState
    data class Success(val documents: List<DocumentListItem>) : DocumentsUiState
    data class Error(val message: String) : DocumentsUiState
}
