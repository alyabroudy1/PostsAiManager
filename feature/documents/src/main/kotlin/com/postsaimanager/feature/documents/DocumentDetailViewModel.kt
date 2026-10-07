package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.domain.contacts.LetterContacts
import com.postsaimanager.core.domain.contacts.LoadLetterContactsUseCase
import com.postsaimanager.core.designsystem.component.DocumentCaseUi
import com.postsaimanager.core.designsystem.component.TimelinePresenter
import com.postsaimanager.core.domain.timeline.ObserveCaseForDocumentUseCase
import com.postsaimanager.core.domain.document.ChangeDocumentFamilyUseCase
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.domain.document.DocumentExporter
import com.postsaimanager.core.domain.document.DocumentProcessor
import com.postsaimanager.core.domain.document.GetDocumentDetailUseCase
import com.postsaimanager.core.domain.document.ReadAgainAsFamilyUseCase
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.model.HouseholdRole
import kotlinx.coroutines.flow.combine
import com.postsaimanager.core.domain.usecase.GetDocumentPreviewUseCase
import com.postsaimanager.core.model.DocumentPreview
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.ReviewState
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.ValueSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** The page preview, from "Show on page" (a field's box marked) or a tap on a page (nothing marked): loading, then the pages. */
data class FieldPreviewState(
    val loading: Boolean = true,
    val preview: DocumentPreview? = null,
    /** Index into [DocumentPreview.pages] of the field's page. */
    val initialPageIndex: Int = 0,
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getDocumentDetailUseCase: GetDocumentDetailUseCase,
    private val documentRepository: DocumentRepository,
    private val documentProcessor: DocumentProcessor,
    private val readAgainAsFamily: ReadAgainAsFamilyUseCase,
    private val changeDocumentFamily: ChangeDocumentFamilyUseCase,
    private val getDocumentPreview: GetDocumentPreviewUseCase,
    private val documentExporter: DocumentExporter,
    private val externalFlowGuard: ExternalFlowGuard,
    installedModels: InstalledModelsRepository,
    profileRepository: ProfileRepository,
    loadLetterContacts: LoadLetterContactsUseCase,
    observeCase: ObserveCaseForDocumentUseCase,
) : ViewModel() {

    val documentId: String = checkNotNull(savedStateHandle["documentId"])

    private var externalFlow: ExternalFlowToken? = null

    /**
     * Called just before the share sheet or another app is launched from this screen, so coming back
     * does not trigger the app lock. Pair with [onExternalLaunchFinished] when the launch fails.
     */
    fun onExternalLaunching(reason: String) {
        externalFlowGuard.finish(externalFlow)
        externalFlow = externalFlowGuard.expect(reason)
    }

    /** The launch failed or its result came back: the protection is no longer needed. */
    fun onExternalLaunchFinished() {
        externalFlowGuard.finish(externalFlow)
        externalFlow = null
    }

    override fun onCleared() {
        externalFlowGuard.finish(externalFlow)
    }

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

    /**
     * The reading's second stage (summary, extras, title) of this document is queued or running, so the summary card says
     * "Summary coming…" instead of staying silent.
     */
    val summaryComing: StateFlow<Boolean> = documentProcessor.enrichingDocuments
        .map { documentId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What the Pages card needs beyond the document: whether an AI model is installed, and the "Me" profile's name. */
    val pagesContext: StateFlow<PagesContext> = combine(
        installedModels.installed.map { it.isNotEmpty() }.catch { emit(true) },
        profileRepository.getProfilesByRole(HouseholdRole.SELF).map { it.firstOrNull()?.name }.catch { emit(null) },
    ) { aiInstalled, selfName -> PagesContext(aiInstalled, selfName) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PagesContext())

    /** The contact this letter names and the sender organisation's current contact: the "From" chip and what a "contact" action offers. */
    val letterContacts: StateFlow<LetterContacts> = loadLetterContacts.observe(documentId)
        .catch { emit(LetterContacts()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LetterContacts())

    /**
     * The matter this letter is part of, for the "Part of" row; null while it belongs to none, or to one that is only this letter
     * (see [TimelinePresenter.isPlainEvent]).
     */
    val caseRow: StateFlow<DocumentCaseUi?> = observeCase(documentId)
        .map { found -> found?.let { TimelinePresenter.documentCase(documentId, it.case, it.events) } }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Guards the auto-enqueue below so opening a `NEW` document does not re-enqueue on
     * every recomposition — `enqueue` is idempotent via `ExistingWorkPolicy.KEEP` anyway,
     * but there is no reason to keep hitting Room and WorkManager on every emission. */
    private var autoEnqueued = false

    init {
        viewModelScope.launch {
            documentProcessor.processingState.collect { state ->
                // The pipeline's processingState is one flow for "whichever document last
                // started" (see DocumentProcessor's doc comment) — without this filter, a
                // second document processing in the background would flash its progress on
                // this screen too.
                val documentIdOfState = when (state) {
                    is ProcessingState.Running -> state.documentId
                    is ProcessingState.Completed -> state.documentId
                    is ProcessingState.Failed -> state.documentId
                    ProcessingState.Idle -> null
                }
                if (documentIdOfState == documentId) _processingProgress.value = state
            }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                if (state !is DocumentDetailUiState.Success) return@collect

                // A document becomes searchable because it was captured, not because
                // someone opened it (documentation/07-document-pipeline.md §7) — but a
                // legacy or otherwise-untouched NEW document still needs a first push, and
                // opening its detail screen is that push.
                if (state.document.status == DocumentStatus.NEW && !autoEnqueued) {
                    autoEnqueued = true
                    documentProcessor.enqueue(documentId)
                }
            }
        }
    }

    // ── Tab ──
    fun selectTab(tab: DetailTab) { _selectedTab.value = tab }

    // ── Processing ──
    /**
     * Enqueues background processing for this document. [force] restarts a document that is
     * already `EXTRACTED`/`REVIEWED` (the detail screen's "Reprocess") or retries one that
     * `FAILED`; without it, a document already queued or running just keeps going.
     *
     * Never runs the pipeline itself — `DocumentProcessor.processDocument` is called only
     * by `DocumentProcessingWorker`, so processing survives this screen closing.
     */
    fun startProcessing(force: Boolean = false) {
        viewModelScope.launch { documentProcessor.enqueue(documentId, force = force) }
    }

    // ── The family chip ──
    /**
     * "Change type": the person says what the document is. The type is stored at once and the document is read again with it pinned,
     * so the fields fit the type; what the person confirmed or edited is kept.
     */
    fun changeFamily(familyId: String) {
        viewModelScope.launch { changeDocumentFamily(documentId, familyId) }
    }

    /** "Read again as …": a fresh read with the family forced; rows a person already reviewed are kept. */
    fun readAgainAs(familyId: String) {
        viewModelScope.launch { readAgainAsFamily(documentId, familyId) }
    }

    // ── Review ──
    /**
     * The fields a confirm-many just confirmed, exactly as they were before — non-null only while an undo is still
     * offered. `DocumentDetailScreen` shows the Snackbar for this and clears it via [undoConfirmAll]/[dismissConfirmAllUndo]
     * once the Snackbar resolves.
     */
    private val _pendingConfirmAllUndo = MutableStateFlow<List<ExtractedData>?>(null)
    val pendingConfirmAllUndo: StateFlow<List<ExtractedData>?> = _pendingConfirmAllUndo.asStateFlow()

    /** ✓ on a row: the person accepts the value as it is. */
    fun confirmField(fieldId: String) = setReviewState(listOf(fieldId), ReviewState.CONFIRMED)

    /** ✕ on a row: the person does not want this value. It moves to "Ignored" and a re-read never brings it back. */
    fun ignoreField(fieldId: String) = setReviewState(listOf(fieldId), ReviewState.IGNORED)

    /** "Restore" in the Ignored footer: back to unreviewed. */
    fun restoreField(fieldId: String) = setReviewState(listOf(fieldId), ReviewState.UNREVIEWED)

    /** Block-level Confirm (an address block): every row of the block. */
    fun confirmFields(fieldIds: List<String>) = setReviewState(fieldIds, ReviewState.CONFIRMED)

    /** Block-level Ignore (an address block): every row of the block. */
    fun ignoreFields(fieldIds: List<String>) = setReviewState(fieldIds, ReviewState.IGNORED)

    // EDITED is never set here: an edit carries a value and goes through updateField.
    private fun setReviewState(fieldIds: List<String>, state: ReviewState) {
        viewModelScope.launch { fieldIds.forEach { documentRepository.setFieldReviewState(it, state) } }
    }

    /**
     * "Confirm n confident": confirms every open field the extraction was sure of and leaves the uncertain ones for the
     * person (a field nobody has looked at is never confirmed on their behalf). Offers the undo.
     *
     * @param fieldIds the rows it works on (the Extracted tab passes its essential rows, so "All details" is never confirmed in bulk);
     *   null: every row of the document
     */
    fun confirmConfidentFields(fieldIds: List<String>? = null) = confirmMany(onlyConfident = true, fieldIds)

    /** "Confirm all": once nothing uncertain is left, confirms every open field of [fieldIds] (null: of the document). Offers the undo. */
    fun confirmAllFields(fieldIds: List<String>? = null) = confirmMany(onlyConfident = false, fieldIds)

    private fun confirmMany(onlyConfident: Boolean, fieldIds: List<String>?) {
        viewModelScope.launch {
            val result = documentRepository.confirmAllExtractedFields(documentId, onlyConfident = onlyConfident, onlyFieldIds = fieldIds?.toSet())
            if (result is PamResult.Success && result.data.isNotEmpty()) {
                _pendingConfirmAllUndo.value = result.data
            }
        }
    }

    /** Reverts the last confirm-many to exactly what it was before — the Snackbar's Undo. */
    fun undoConfirmAll() {
        val fields = _pendingConfirmAllUndo.value ?: return
        _pendingConfirmAllUndo.value = null
        viewModelScope.launch { documentRepository.restoreExtractedFields(fields) }
    }

    /** The Snackbar timed out or was dismissed without Undo — nothing left to revert. */
    fun dismissConfirmAllUndo() { _pendingConfirmAllUndo.value = null }

    // ── Field CRUD ──
    /** A field the person adds by hand: their own value, so it is theirs from the start. */
    fun addField(name: String, value: String, type: ExtractedFieldType) {
        viewModelScope.launch {
            documentRepository.addExtractedField(ExtractedData(
                id = UuidGenerator.generate(), documentId = documentId,
                fieldName = name, fieldValue = value, fieldType = type,
                confidence = 1.0f, isConfirmed = true, source = ValueSource.USER,
            ))
        }
    }

    /** ✎ Edit: the person's value (and name, for a row they named themselves). The repository marks the row edited. */
    fun updateField(fieldId: String, name: String, value: String) {
        viewModelScope.launch { documentRepository.updateExtractedField(fieldId, name, value) }
    }

    // ── Summary ──
    /** The person's own summary: kept from now on, never replaced by a later reading. */
    fun updateSummary(text: String) {
        viewModelScope.launch { documentRepository.updateSummary(documentId, text) }
    }

    // ── "Show on page" ──
    private val _fieldPreview = MutableStateFlow<FieldPreviewState?>(null)

    /** The page preview opened by "Show on page", or null while it is closed. */
    val fieldPreview: StateFlow<FieldPreviewState?> = _fieldPreview.asStateFlow()

    private var previewJob: Job? = null

    /** Opens the pages on [page] with [bbox] marked. */
    fun showOnPage(page: Int?, bbox: TextBounds?) {
        previewJob?.cancel()
        _fieldPreview.value = FieldPreviewState(loading = true)
        previewJob = viewModelScope.launch {
            val loaded = getDocumentPreview.forField(documentId, page, bbox)
            _fieldPreview.value = FieldPreviewState(
                loading = false,
                preview = loaded,
                initialPageIndex = loaded?.pages?.indexOfFirst { it.pageNumber == page }?.coerceAtLeast(0) ?: 0,
            )
        }
    }

    /** Opens the pages full screen on [page] (1-based) with nothing marked: tapping a page in the Pages tab. */
    fun openPage(page: Int) = showOnPage(page, bbox = null)

    fun closeFieldPreview() {
        previewJob?.cancel()
        _fieldPreview.value = null
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

    // ── Title ──
    /** The person's own title: kept from now on, never replaced by a later reading. */
    fun renameDocument(title: String) { viewModelScope.launch { documentRepository.renameDocument(documentId, title) } }

    // ── Favorites ──
    fun toggleFavorite() { viewModelScope.launch { documentRepository.toggleFavorite(documentId) } }

    /**
     * Moves this document to the trash (overflow menu "Delete", and the FAILED banner's
     * delete), then calls [onDone] with its id. The caller navigates back and shows the
     * "moved to Recently deleted / Undo" snackbar — waiting for the write first, since
     * leaving the screen clears this ViewModel's scope.
     */
    fun moveToTrash(onDone: (String) -> Unit) {
        viewModelScope.launch {
            documentRepository.moveToTrash(documentId)
            onDone(documentId)
        }
    }

    /** Restores this document — used from the "This document was deleted" state. */
    fun restoreDocument() {
        viewModelScope.launch { documentRepository.restore(documentId) }
    }
}

enum class DetailTab { PAGES, EXTRACTED, TIMELINE }
