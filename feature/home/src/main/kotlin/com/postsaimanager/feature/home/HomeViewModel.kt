package com.postsaimanager.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.MoveDocumentToTrashUseCase
import com.postsaimanager.core.domain.document.RestoreDocumentUseCase
import com.postsaimanager.core.domain.importing.ImportQueue
import com.postsaimanager.core.domain.importing.ImportStatus
import com.postsaimanager.core.domain.document.list.ObserveDocumentListItemsUseCase
import com.postsaimanager.core.domain.household.DismissHouseholdPromptUseCase
import com.postsaimanager.core.domain.household.ObserveHouseholdPromptUseCase
import com.postsaimanager.core.domain.setup.ObserveModelBannerUseCase
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.ModelBannerState
import com.postsaimanager.core.model.ProcessingState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    observeDocumentListItems: ObserveDocumentListItemsUseCase,
    documentProcessor: DocumentProcessor,
    observeModelBanner: ObserveModelBannerUseCase,
    observeHouseholdPrompt: ObserveHouseholdPromptUseCase,
    private val dismissHouseholdPrompt: DismissHouseholdPromptUseCase,
    private val importQueue: ImportQueue,
    private val externalFlowGuard: ExternalFlowGuard,
    private val moveToTrash: MoveDocumentToTrashUseCase,
    private val restoreDocument: RestoreDocumentUseCase,
) : ViewModel() {

    /** Swipe-to-delete on a recent document, the same use case as the Documents tab. The screen's snackbar offers Undo. */
    fun onDeleteDocument(documentId: String) {
        viewModelScope.launch { moveToTrash(documentId) }
    }

    /** Undo for [onDeleteDocument]. */
    fun onRestoreDocument(documentId: String) {
        viewModelScope.launch { restoreDocument(documentId) }
    }

    /**
     * The one-time "Add yourself and your family" card: the household role the profile editor should open with, or null for no card
     * (a "Me" exists, it was dismissed, or there is no document yet).
     */
    val householdPrompt: StateFlow<HouseholdRole?> = observeHouseholdPrompt()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Not now": the card never comes back. */
    fun onDismissHouseholdPrompt() {
        viewModelScope.launch { dismissHouseholdPrompt() }
    }

    private var pickerFlow: ExternalFlowToken? = null

    /**
     * Files being turned into pages in the background, or an import that ended with a problem: the list shows "Importing…" (a
     * document only exists once its pages do) or the failure until it is dismissed.
     */
    val importStatus: StateFlow<ImportStatus> = importQueue.status
        .catch { emit(ImportStatus()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImportStatus())

    /** The system file picker is a trip out of the app and back: the app lock must not treat the return as a background. */
    fun onImportPickerLaunching() {
        externalFlowGuard.finish(pickerFlow)
        pickerFlow = externalFlowGuard.expect("import-file-picker")
    }

    fun onImportPickerResult() {
        externalFlowGuard.finish(pickerFlow)
        pickerFlow = null
    }

    fun dismissImportFailures() = importQueue.dismissFailures()

    override fun onCleared() {
        externalFlowGuard.finish(pickerFlow)
    }

    /**
     * The model banner: "AI model not installed · Install" after the user skipped the first-run setup, the download progress
     * ("Setting up AI · 1 of 3 · 45%") while the models download in the background, or the failure with a way back to the models.
     * It goes away once everything is installed.
     */
    val modelBanner: StateFlow<ModelBannerState> = observeModelBanner()
        .catch { emit(ModelBannerState.Hidden) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelBannerState.Hidden)

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
            .catch { emit(HomeUiState.Error(it.message)) }
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
    /** [message] is the raw cause, if any; the screen shows a localized fallback when it is null. */
    data class Error(val message: String?) : HomeUiState
}
