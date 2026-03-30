package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.data.repository.DocumentProcessingPipeline
import com.postsaimanager.core.data.repository.MatchType
import com.postsaimanager.core.data.repository.ProcessingState
import com.postsaimanager.core.data.repository.ProfileMatcher
import com.postsaimanager.core.data.repository.ProfileSuggestion
import com.postsaimanager.core.data.util.PdfGenerator
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.domain.document.GetDocumentDetailUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getDocumentDetailUseCase: GetDocumentDetailUseCase,
    private val documentRepository: DocumentRepository,
    private val profileRepository: ProfileRepository,
    private val processingPipeline: DocumentProcessingPipeline,
    private val profileMatcher: ProfileMatcher,
    private val pdfGenerator: PdfGenerator,
) : ViewModel() {

    val documentId: String = checkNotNull(savedStateHandle["documentId"])

    private val _selectedTab = MutableStateFlow(DetailTab.PAGES)
    val selectedTab: StateFlow<DetailTab> = _selectedTab.asStateFlow()

    private val _processingProgress = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingProgress: StateFlow<ProcessingState> = _processingProgress.asStateFlow()

    private val _profileSuggestions = MutableStateFlow<List<ProfileSuggestion>>(emptyList())
    val profileSuggestions: StateFlow<List<ProfileSuggestion>> = _profileSuggestions.asStateFlow()

    /** Holds the suggestion that triggered profile creation — shown in ProfileEditSheet */
    private val _editingProfileSuggestion = MutableStateFlow<ProfileSuggestion?>(null)
    val editingProfileSuggestion: StateFlow<ProfileSuggestion?> = _editingProfileSuggestion.asStateFlow()

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
                if (state is ProcessingState.Completed) runProfileMatching()
            }
        }
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
                _profileSuggestions.value = _profileSuggestions.value.map {
                    if (it === suggestion) it.copy(isAutoLinked = true) else it
                }
            }
        }
    }

    // ── Tab ──
    fun selectTab(tab: DetailTab) { _selectedTab.value = tab }

    // ── Processing ──
    fun startProcessing() {
        viewModelScope.launch {
            _profileSuggestions.value = emptyList()
            processingPipeline.processDocument(documentId)
        }
    }

    // ── Field CRUD ──
    fun confirmField(fieldId: String) { viewModelScope.launch { documentRepository.confirmExtractedField(fieldId) } }

    fun addField(name: String, value: String, type: ExtractedFieldType) {
        viewModelScope.launch {
            documentRepository.addExtractedField(ExtractedData(
                id = UuidGenerator.generate(), documentId = documentId,
                fieldName = name, fieldValue = value, fieldType = type,
                confidence = 1.0f, isConfirmed = true,
            ))
        }
    }

    fun updateField(fieldId: String, name: String, value: String) {
        viewModelScope.launch { documentRepository.updateExtractedField(fieldId, name, value) }
    }

    fun deleteField(fieldId: String) {
        viewModelScope.launch { documentRepository.deleteExtractedField(fieldId) }
    }

    // ── Profile linking ──
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

    /** Opens the ProfileEditSheet pre-filled with extracted data */
    fun openProfileCreation(suggestion: ProfileSuggestion) {
        _editingProfileSuggestion.value = suggestion
    }

    fun dismissProfileCreation() {
        _editingProfileSuggestion.value = null
    }

    /** Called when user confirms profile creation from the edit sheet */
    fun saveProfileFromForm(formData: ProfileFormData, suggestion: ProfileSuggestion) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val profile = Profile(
                id = UuidGenerator.generate(),
                type = formData.type,
                name = formData.name.ifBlank { formData.organization },
                organization = formData.organization.ifBlank { null },
                department = formData.department.ifBlank { null },
                street = formData.street.ifBlank { null },
                city = formData.city.ifBlank { null },
                postalCode = formData.postalCode.ifBlank { null },
                country = formData.country.ifBlank { null },
                phone = formData.phone.ifBlank { null },
                email = formData.email.ifBlank { null },
                website = formData.website.ifBlank { null },
                reference = formData.reference.ifBlank { null },
                notes = formData.notes.ifBlank { null },
                createdAt = now,
                modifiedAt = now,
            )

            val result = profileRepository.createProfile(profile)
            if (result is PamResult.Success) {
                profileRepository.linkProfileToDocument(profile.id, documentId, suggestion.role)
                _profileSuggestions.value = _profileSuggestions.value.map {
                    if (it.role == suggestion.role && it.matchType == MatchType.NEW_PROFILE) {
                        it.copy(isAutoLinked = true, existingProfile = profile)
                    } else it
                }
            }
            _editingProfileSuggestion.value = null
        }
    }

    fun dismissSuggestion(suggestion: ProfileSuggestion) {
        _profileSuggestions.value = _profileSuggestions.value.filter { it !== suggestion }
    }

    // ── PDF generation ──
    fun generatePdf(): File? {
        val state = uiState.value
        if (state !is DocumentDetailUiState.Success) return null
        val paths = state.pages.map { it.imagePath }
        val title = state.document.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
        return pdfGenerator.generatePdf(paths, "PAM_$title")
    }

    // ── Favorites ──
    fun toggleFavorite() { viewModelScope.launch { documentRepository.toggleFavorite(documentId) } }

    fun deleteDocument(onDeleted: () -> Unit) {
        viewModelScope.launch { documentRepository.deleteDocument(documentId); onDeleted() }
    }
}

enum class DetailTab { PAGES, EXTRACTED, TIMELINE }
