package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.domain.document.DocumentExporter
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.EntityCoverageFilter
import com.postsaimanager.core.domain.document.EntityProposalService
import com.postsaimanager.core.domain.document.GetDocumentDetailUseCase
import com.postsaimanager.core.domain.document.ProfileMatchingService
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.EntityProposal
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.MatchType
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.Profile
import com.postsaimanager.core.model.ProfileRole
import com.postsaimanager.core.model.ProfileSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
    private val documentProcessor: DocumentProcessor,
    private val profileMatchingService: ProfileMatchingService,
    private val entityProposalService: EntityProposalService,
    private val documentExporter: DocumentExporter,
) : ViewModel() {

    val documentId: String = checkNotNull(savedStateHandle["documentId"])

    private val _selectedTab = MutableStateFlow(DetailTab.PAGES)
    val selectedTab: StateFlow<DetailTab> = _selectedTab.asStateFlow()

    private val _processingProgress = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingProgress: StateFlow<ProcessingState> = _processingProgress.asStateFlow()

    /**
     * Every field-based suggestion `ProfileMatchingService` produced, before
     * [EntityCoverageFilter] removes the ones the entity path already covers — see
     * [profileSuggestions]. Kept separate so [linkSuggestionToProfile] and friends, which
     * update this list by identity, are not fighting the filtered view over what "the list"
     * means.
     */
    private val _profileSuggestions = MutableStateFlow<List<ProfileSuggestion>>(emptyList())

    /**
     * Entities the app found but would not act on automatically — see
     * [EntityProposalService]. Document-scoped and answered here, not a global inbox: see
     * [EntityProposal]'s class doc.
     */
    val entityProposals: StateFlow<List<EntityProposal>> =
        entityProposalService.pendingProposals(documentId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Field-based suggestions actually worth showing: [EntityCoverageFilter] drops any that
     * ask about a person [entityProposals] already asks about, or that a profile the entity
     * path linked to this document already covers (task 7.14.11d — a Jobcenter letter used to
     * be able to put up two differently-worded cards asking about the same Jobcenter).
     *
     * Combined here, in the ViewModel, rather than behind a new domain port: the two inputs
     * ([entityProposalService.pendingProposals] and [profileRepository.getProfilesForDocument])
     * are already domain-level flows this ViewModel holds a reference to for other reasons, and
     * the actual dedupe rule is [EntityCoverageFilter] — a pure, independently-tested function
     * in `:core:domain`. A new port here would only wrap the same two calls this class already
     * makes, without moving any decision out of the ViewModel.
     */
    val profileSuggestions: StateFlow<List<ProfileSuggestion>> = combine(
        _profileSuggestions,
        entityProposals,
        profileRepository.getProfilesForDocument(documentId),
    ) { suggestions, proposals, linkedProfiles ->
        EntityCoverageFilter.apply(
            suggestions, proposals, linkedProfiles.map { (profile, _) -> profile },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            documentProcessor.processingState.collect { state ->
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
            val suggestions = profileMatchingService.matchProfiles(documentId, state.extractedData)
            _profileSuggestions.value = suggestions
            // Auto-link exact matches
            suggestions.filter { it.matchType == MatchType.EXACT_MATCH && !it.isAutoLinked }.forEach { suggestion ->
                profileMatchingService.linkExistingProfile(suggestion)
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
            documentProcessor.processDocument(documentId)
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
            profileMatchingService.linkExistingProfile(suggestion)
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

    // ── Entity proposals ──
    // Both answers are fire-and-forget from the UI's perspective: entityProposals is backed by
    // the same Room row the service just resolved, so the list updates on its own once the
    // write lands — there is no local list to reconcile here, unlike profileSuggestions above.
    fun acceptProposal(proposal: EntityProposal) {
        viewModelScope.launch { entityProposalService.accept(proposal) }
    }

    fun dismissProposal(proposal: EntityProposal) {
        viewModelScope.launch { entityProposalService.dismiss(proposal) }
    }

    // ── PDF generation ──
    fun generatePdf(): File? {
        val state = uiState.value
        if (state !is DocumentDetailUiState.Success) return null
        val paths = state.pages.map { it.imagePath }
        val title = state.document.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
        // The port speaks paths, not File — see DocumentExporter's doc comment. The
        // Composable still wants a File for FileProvider, so the feature re-wraps it here.
        return documentExporter.exportPdf(paths, "PAM_$title")?.let(::File)
    }

    // ── Favorites ──
    fun toggleFavorite() { viewModelScope.launch { documentRepository.toggleFavorite(documentId) } }

    fun deleteDocument(onDeleted: () -> Unit) {
        viewModelScope.launch { documentRepository.deleteDocument(documentId); onDeleted() }
    }
}

enum class DetailTab { PAGES, EXTRACTED, TIMELINE }
