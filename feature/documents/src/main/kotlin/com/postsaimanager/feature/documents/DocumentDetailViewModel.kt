package com.postsaimanager.feature.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.applock.ExternalFlowToken
import com.postsaimanager.core.domain.contacts.ConfirmContactUseCase
import com.postsaimanager.core.domain.contacts.DiscardContactUseCase
import com.postsaimanager.core.domain.contacts.LetterContactFields
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
import com.postsaimanager.core.domain.reading.ViewingState
import com.postsaimanager.core.domain.repository.DocumentRepository
import com.postsaimanager.core.domain.repository.InstalledModelsRepository
import com.postsaimanager.core.domain.repository.ProfileRepository
import com.postsaimanager.core.domain.document.actions.ActionEdit
import com.postsaimanager.core.domain.timeline.CaseChoices
import com.postsaimanager.core.domain.timeline.CaseTarget
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.HouseholdRole
import com.postsaimanager.core.model.Profile
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val viewing: ViewingState,
    private val letterContactFields: LetterContactFields,
    private val confirmContact: ConfirmContactUseCase,
    private val discardContact: DiscardContactUseCase,
    private val edits: LetterEditActions,
) : ViewModel() {

    val documentId: String = checkNotNull(savedStateHandle["documentId"])

    /** The screen is showing: no "Letter understood" notification for this letter while the person is looking at it. */
    fun onScreenShown() = viewing.documentOpened(documentId)

    /** The screen left the composition (balanced with [onScreenShown]). */
    fun onScreenHidden() = viewing.documentClosed(documentId)

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
     * The matter this letter is part of, for the "Part of" row; null while it belongs to none (the row then says "none" and offers to add
     * the letter to a matter, see [caseChoices]).
     */
    val caseRow: StateFlow<DocumentCaseUi?> = observeCase(documentId)
        .map { found -> found?.let { TimelinePresenter.documentCase(documentId, it.case, it.events, showPlain = true) } }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The matters this letter can be moved to (those of its sender) and the one it is in; null while it has no sender to belong to. */
    val caseChoices: StateFlow<CaseChoices?> = edits.caseChoices(documentId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The household people (Me and the family) the letter can be for: the chips of "Who this is for". */
    val householdPeople: StateFlow<List<Profile>> = profileRepository.getProfiles()
        .map { all -> all.filter { it.isManaged } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
    fun ignoreField(fieldId: String) = ignoreFields(listOf(fieldId))

    /** "Restore" in the Ignored footer: back to unreviewed. */
    fun restoreField(fieldId: String) = setReviewState(listOf(fieldId), ReviewState.UNREVIEWED)

    /** Block-level Confirm (an address block): every row of the block. */
    fun confirmFields(fieldIds: List<String>) = setReviewState(fieldIds, ReviewState.CONFIRMED)

    /**
     * Block-level Ignore (an address block): every row of the block. The letter's contact field and the contact it names are one thing:
     * ignoring it discards the contact too (with its tombstone), see [LetterContactFields].
     */
    fun ignoreFields(fieldIds: List<String>) {
        viewModelScope.launch { letterContactFields.ignore(documentId, fieldIds) }
    }

    /** The letter's suggested contact is right: its field is confirmed, here and on the organisation page. */
    fun confirmLetterContact(contactId: String) {
        viewModelScope.launch { confirmContact(contactId) }
    }

    /** The letter's suggested contact is not wanted: discarded with its tombstone, so reading the letter again does not bring it back. */
    fun discardLetterContact(contactId: String) {
        viewModelScope.launch { discardContact(contactId) }
    }

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
        viewModelScope.launch { fieldWrites.withLock { letterContactFields.edit(documentId, fieldId, name, value) } }
    }

    /** The person chose what a date or an amount means ([meaningId] of `ValueMeanings`; null: none of them). Kept by every re-read. */
    fun setFieldMeaning(fieldId: String, meaningId: String?) {
        viewModelScope.launch { fieldWrites.withLock { edits.setMeaning(documentId, fieldId, meaningId) } }
    }

    /** The edits of one row (its value, then its meaning) are separate writes of the same row: one after the other, in the order made. */
    private val fieldWrites = Mutex()

    // ── What the letter asks, who it is for, the contact, the matter ──
    /** The person edited an action: its kind, wording or due date. Kept by every re-read. */
    fun editAction(original: ActionItem, edit: ActionEdit) {
        viewModelScope.launch { edits.editAction(documentId, original, edit) }
    }

    /** The person deleted an action; a re-read does not bring it back. */
    fun deleteAction(original: ActionItem) {
        viewModelScope.launch { edits.deleteAction(documentId, original) }
    }

    /** The person added an action of their own. */
    fun addAction(edit: ActionEdit) {
        viewModelScope.launch { edits.addAction(documentId, edit) }
    }

    /** The person says who the letter is for: the people check never changes it afterwards. */
    fun setConcernedPeople(profileIds: List<String>) {
        viewModelScope.launch { edits.setPeople(documentId, profileIds) }
    }

    /** The person edited the contact person of the letter (name, role, phone, e-mail), the same as on the organisation's page. */
    fun updateLetterContact(contact: ContactPerson) {
        viewModelScope.launch { edits.updateContact(contact) }
    }

    /** The person set the letter's language: kept by every re-read, and the language the summary and answers are written in. */
    fun setLanguage(tag: String) {
        viewModelScope.launch { edits.setLanguage(documentId, tag) }
    }

    /** The person edited an event of the matter (kind, day, text); a re-read keeps it. */
    fun editEvent(eventId: String, kindId: String, eventDate: Long, title: String) {
        viewModelScope.launch { edits.editEvent(eventId, kindId, eventDate, title) }
    }

    /** The person deleted an event of the matter; a re-read does not bring it back. */
    fun deleteEvent(eventId: String) {
        viewModelScope.launch { edits.deleteEvent(eventId) }
    }

    /** The person added an event to this letter's timeline. */
    fun addEvent(kindId: String, eventDate: Long, title: String) {
        viewModelScope.launch { edits.addEvent(documentId, kindId, eventDate, title) }
    }

    /** The person set the matter's status, or null to hand it back to the letters' events. */
    fun setCaseStatus(caseId: String, status: com.postsaimanager.core.model.CaseStatus?) {
        viewModelScope.launch { edits.setCaseStatus(caseId, status) }
    }

    /** The person renamed the matter this letter is part of. */
    fun renameCase(caseId: String, title: String) {
        viewModelScope.launch { edits.renameCase(caseId, title) }
    }

    /** The person moved the letter to another matter, a new one, or none; reading it again leaves it there. */
    fun moveToCase(target: CaseTarget) {
        viewModelScope.launch { edits.moveToCase(documentId, target) }
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
