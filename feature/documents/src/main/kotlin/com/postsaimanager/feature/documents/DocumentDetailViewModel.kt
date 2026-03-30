package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.repository.DocumentProcessingPipeline
import com.postsaimanager.core.data.repository.MatchType
import com.postsaimanager.core.data.repository.ProcessingState
import com.postsaimanager.core.data.repository.ProfileMatcher
import com.postsaimanager.core.data.repository.ProfileSuggestion
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.domain.document.GetDocumentDetailUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
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
    private val profileMatcher: ProfileMatcher,
) : ViewModel() {

    val documentId: String = checkNotNull(savedStateHandle["documentId"])

    private val _selectedTab = MutableStateFlow(DetailTab.PAGES)
    val selectedTab: StateFlow<DetailTab> = _selectedTab.asStateFlow()

    private val _processingProgress = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingProgress: StateFlow<ProcessingState> = _processingProgress.asStateFlow()

    private val _profileSuggestions = MutableStateFlow<List<ProfileSuggestion>>(emptyList())
    val profileSuggestions: StateFlow<List<ProfileSuggestion>> = _profileSuggestions.asStateFlow()

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
                // After processing completes, run profile matching
                if (state is ProcessingState.Completed) {
                    runProfileMatching()
                }
            }
        }
        // Also run matching on first load if already extracted
        viewModelScope.launch {
            uiState.collect { state ->
                if (state is DocumentDetailUiState.Success && state.extractedData.isNotEmpty() && _profileSuggestions.value.isEmpty()) {
                    runProfileMatching()
                }
            }
        }
    }

    private suspend fun runProfileMatching() {
        val state = uiState.value
        if (state is DocumentDetailUiState.Success) {
            val suggestions = profileMatcher.matchProfiles(documentId, state.extractedData)
            _profileSuggestions.value = suggestions

            // Auto-link exact matches
            suggestions.filter { it.matchType == MatchType.EXACT_MATCH && !it.isAutoLinked }.forEach { suggestion ->
                profileMatcher.linkExistingProfile(suggestion)
                // Mark as auto-linked
                _profileSuggestions.value = _profileSuggestions.value.map {
                    if (it === suggestion) it.copy(isAutoLinked = true) else it
                }
            }
        }
    }

    fun selectTab(tab: DetailTab) {
        _selectedTab.value = tab
    }

    fun startProcessing() {
        viewModelScope.launch {
            _profileSuggestions.value = emptyList()
            processingPipeline.processDocument(documentId)
        }
    }

    fun confirmField(fieldId: String) {
        viewModelScope.launch { documentRepository.confirmExtractedField(fieldId) }
    }

    fun addField(name: String, value: String, type: ExtractedFieldType) {
        viewModelScope.launch {
            documentRepository.addExtractedField(
                ExtractedData(
                    id = UuidGenerator.generate(),
                    documentId = documentId,
                    fieldName = name,
                    fieldValue = value,
                    fieldType = type,
                    confidence = 1.0f,
                    isConfirmed = true,
                )
            )
        }
    }

    fun updateField(fieldId: String, name: String, value: String) {
        viewModelScope.launch { documentRepository.updateExtractedField(fieldId, name, value) }
    }

    fun deleteField(fieldId: String) {
        viewModelScope.launch { documentRepository.deleteExtractedField(fieldId) }
    }

    fun linkSuggestionToProfile(suggestion: ProfileSuggestion) {
        viewModelScope.launch {
            profileMatcher.linkExistingProfile(suggestion)
            _profileSuggestions.value = _profileSuggestions.value.map {
                if (it.role == suggestion.role && it.existingProfile?.id == suggestion.existingProfile?.id) {
                    it.copy(isAutoLinked = true)
                } else it
            }
        }
    }

    fun createProfileFromSuggestion(suggestion: ProfileSuggestion) {
        viewModelScope.launch {
            val result = profileMatcher.createAndLinkProfile(suggestion)
            if (result is com.postsaimanager.core.common.result.PamResult.Success) {
                _profileSuggestions.value = _profileSuggestions.value.map {
                    if (it.role == suggestion.role && it.matchType == MatchType.NEW_PROFILE) {
                        it.copy(isAutoLinked = true, existingProfile = result.data)
                    } else it
                }
            }
        }
    }

    fun dismissSuggestion(suggestion: ProfileSuggestion) {
        _profileSuggestions.value = _profileSuggestions.value.filter { it !== suggestion }
    }

    fun toggleFavorite() {
        viewModelScope.launch { documentRepository.toggleFavorite(documentId) }
    }

    fun deleteDocument(onDeleted: () -> Unit) {
        viewModelScope.launch {
            documentRepository.deleteDocument(documentId)
            onDeleted()
        }
    }
}

enum class DetailTab { PAGES, EXTRACTED, TIMELINE }
