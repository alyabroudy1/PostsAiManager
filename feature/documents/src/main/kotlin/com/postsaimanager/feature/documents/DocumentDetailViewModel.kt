package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.data.repository.DocumentProcessingPipeline
import com.postsaimanager.core.data.repository.ProcessingState
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.domain.document.GetDocumentDetailUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getDocumentDetailUseCase: GetDocumentDetailUseCase,
    private val documentRepository: DocumentRepository,
    private val processingPipeline: DocumentProcessingPipeline,
) : ViewModel() {

    private val documentId: String = checkNotNull(savedStateHandle["documentId"])

    private val _selectedTab = MutableStateFlow(DetailTab.PAGES)
    val selectedTab: StateFlow<DetailTab> = _selectedTab.asStateFlow()

    private val _processingProgress = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingProgress: StateFlow<ProcessingState> = _processingProgress.asStateFlow()

    val uiState: StateFlow<DocumentDetailUiState> =
        getDocumentDetailUseCase(documentId)
            .catch { emit(DocumentDetailUiState.Error(it.message ?: "Unknown error")) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = DocumentDetailUiState.Loading,
            )

    init {
        viewModelScope.launch {
            processingPipeline.processingState.collect { state ->
                _processingProgress.value = state
            }
        }
    }

    fun selectTab(tab: DetailTab) {
        _selectedTab.value = tab
    }

    fun startProcessing() {
        viewModelScope.launch {
            processingPipeline.processDocument(documentId)
        }
    }

    fun confirmField(fieldId: String) {
        viewModelScope.launch {
            documentRepository.confirmExtractedField(fieldId)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            documentRepository.toggleFavorite(documentId)
        }
    }

    fun deleteDocument(onDeleted: () -> Unit) {
        viewModelScope.launch {
            documentRepository.deleteDocument(documentId)
            onDeleted()
        }
    }
}

enum class DetailTab { PAGES, EXTRACTED, TIMELINE }
