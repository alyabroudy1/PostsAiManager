package com.postsaimanager.feature.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.postsaimanager.core.common.extensions.toRelativeTime
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.component.documentDisplayTitle
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.EntityProposal
import com.postsaimanager.core.model.EntityRole
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ValueSource
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.MatchType
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.ProfileSuggestion
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    onNavigateBack: () -> Unit,
    onChatClick: (String) -> Unit,
    /**
     * Called once the document has been moved to the trash. The caller navigates away and
     * owns the "moved to Recently deleted / Undo" snackbar, so it outlives this screen.
     */
    onDeleted: (documentId: String) -> Unit,
    /**
     * A 1-based page to land on, e.g. from a chat citation chip (4.3) — jumps straight to the
     * Pages tab at that page instead of wherever the user last left this document. Null opens
     * on whatever [viewModel] would show anyway (the Pages tab by default).
     */
    initialPage: Int? = null,
    modifier: Modifier = Modifier,
    viewModel: DocumentDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val processingState by viewModel.processingProgress.collectAsStateWithLifecycle()
    val profileSuggestions by viewModel.profileSuggestions.collectAsStateWithLifecycle()
    val editingProfileSuggestion by viewModel.editingProfileSuggestion.collectAsStateWithLifecycle()
    val entityProposals by viewModel.entityProposals.collectAsStateWithLifecycle()
    val summaryComing by viewModel.summaryComing.collectAsStateWithLifecycle()
    val pendingConfirmAllUndo by viewModel.pendingConfirmAllUndo.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    (uiState as? DocumentDetailUiState.Success)?.document?.takeIf { showRenameDialog }?.let { document ->
        RenameDocumentDialog(
            currentTitle = documentDisplayTitle(document),
            onDismiss = { showRenameDialog = false },
            onSave = { title ->
                showRenameDialog = false
                viewModel.renameDocument(title)
            },
        )
    }

    // A citation chip always means "show me that page" — even if the user was last looking
    // at a different tab (Extracted, Timeline) when they left this document.
    LaunchedEffect(initialPage) {
        if (initialPage != null) viewModel.selectTab(DetailTab.PAGES)
    }

    // 5.3: "Confirm all" offers a cheap undo — the Snackbar itself both shows and resolves
    // it, so there is nothing to reconcile if the screen is left before it times out (the
    // ViewModel still holds the undo state; only its Snackbar is gone).
    LaunchedEffect(pendingConfirmAllUndo) {
        val confirmed = pendingConfirmAllUndo ?: return@LaunchedEffect
        val count = confirmed.size
        val result = snackbarHostState.showSnackbar(
            message = if (count == 1) "1 field confirmed" else "$count fields confirmed",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoConfirmAll()
        } else {
            viewModel.dismissConfirmAllUndo()
        }
    }

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = when (val state = uiState) {
                    is DocumentDetailUiState.Success -> documentDisplayTitle(state.document)
                    else -> "Document"
                },
                onNavigateBack = onNavigateBack,
                actions = {
                    if (uiState is DocumentDetailUiState.Success) {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(PamIcons.More, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_rename)) },
                                onClick = {
                                    showOverflowMenu = false
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.moveToTrash(onDeleted)
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // A Scaffold slot (not a Box overlay inside the tab) so snackbars are laid out above it.
        floatingActionButton = {
            val state = uiState
            if (state is DocumentDetailUiState.Success && !state.document.isTrashed &&
                selectedTab == DetailTab.EXTRACTED
            ) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(PamIcons.Add, contentDescription = "Add field")
                }
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        AnimatedContent(
            targetState = uiState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "detail_content",
            modifier = Modifier.padding(innerPadding),
        ) { state ->
            when {
                state is DocumentDetailUiState.Loading -> PamLoadingState()
                state is DocumentDetailUiState.Error ->
                    PamErrorState(message = state.message, icon = PamIcons.Error)
                state is DocumentDetailUiState.NotFound ->
                    PamErrorState(message = "This document no longer exists.", icon = PamIcons.Error)
                // A trashed document reaches Success too (GetDocumentDetailUseCase doesn't
                // filter it out) — rendered as its own state rather than the normal content,
                // e.g. when opened from Recently deleted or via a citation/deep link.
                state is DocumentDetailUiState.Success && state.document.isTrashed ->
                    TrashedDocumentState(
                        title = documentDisplayTitle(state.document),
                        onRestore = viewModel::restoreDocument,
                    )
                state is DocumentDetailUiState.Success -> DocumentDetailContent(
                    state = state,
                    selectedTab = selectedTab,
                    initialPage = initialPage,
                    processingState = processingState,
                    profileSuggestions = profileSuggestions,
                    entityProposals = entityProposals,
                    summaryComing = summaryComing,
                    onTabSelected = viewModel::selectTab,
                    onProcess = { force -> viewModel.startProcessing(force) },
                    onConfirmField = viewModel::confirmField,
                    onConfirmAllFields = viewModel::confirmAllFields,
                    onAddClick = { showAddDialog = true },
                    onUpdateField = viewModel::updateField,
                    onDeleteField = viewModel::deleteField,
                    onLinkProfile = viewModel::linkSuggestionToProfile,
                    onCreateProfile = viewModel::openProfileCreation,
                    onDismissSuggestion = viewModel::dismissSuggestion,
                    onAcceptProposal = viewModel::acceptProposal,
                    onDismissProposal = viewModel::dismissProposal,
                    onChatClick = { onChatClick(state.document.id) },
                    onToggleFavorite = viewModel::toggleFavorite,
                    onSharePdf = { viewModel.generatePdf() },
                    externalLaunch = ExternalLaunch(
                        expect = viewModel::onExternalLaunching,
                        finish = viewModel::onExternalLaunchFinished,
                    ),
                    onDelete = { viewModel.moveToTrash(onDeleted) },
                )
            }
        }
    }

    if (showAddDialog) {
        AddFieldDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, value, type ->
                viewModel.addField(name, value, type)
                showAddDialog = false
            },
        )
    }

    // Profile creation sheet
    editingProfileSuggestion?.let { suggestion ->
        ProfileEditSheet(
            initialData = ProfileFormData(
                name = suggestion.extractedName ?: "",
                organization = suggestion.extractedOrganization ?: "",
                phone = suggestion.extractedPhone ?: "",
                email = suggestion.extractedEmail ?: "",
                street = suggestion.extractedAddress ?: "",
                type = if (suggestion.extractedOrganization != null) com.postsaimanager.core.model.ProfileType.AUTHORITY
                    else com.postsaimanager.core.model.ProfileType.PERSON,
            ),
            isEditing = false,
            onSave = { formData -> viewModel.saveProfileFromForm(formData, suggestion) },
            onDismiss = { viewModel.dismissProfileCreation() },
        )
    }
}

@Composable
private fun DocumentDetailContent(
    state: DocumentDetailUiState.Success,
    selectedTab: DetailTab,
    initialPage: Int?,
    processingState: ProcessingState,
    profileSuggestions: List<ProfileSuggestion>,
    entityProposals: List<EntityProposal>,
    /** The reading's second stage (summary, extras) is still being written: the summary card says so. */
    summaryComing: Boolean,
    onTabSelected: (DetailTab) -> Unit,
    /** `force = true` restarts a document that is already `EXTRACTED`/`REVIEWED`, or retries
     * one that `FAILED`; `false` is used only for the auto-enqueue done by the ViewModel on
     * open, which this Composable never triggers directly. */
    onProcess: (force: Boolean) -> Unit,
    onConfirmField: (String) -> Unit,
    onConfirmAllFields: () -> Unit,
    onAddClick: () -> Unit,
    onUpdateField: (String, String, String) -> Unit,
    onDeleteField: (String) -> Unit,
    onLinkProfile: (ProfileSuggestion) -> Unit,
    onCreateProfile: (ProfileSuggestion) -> Unit,
    onDismissSuggestion: (ProfileSuggestion) -> Unit,
    onAcceptProposal: (EntityProposal) -> Unit,
    onDismissProposal: (EntityProposal) -> Unit,
    onSharePdf: () -> File?,
    externalLaunch: ExternalLaunch,
    onChatClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // A document starts processing itself the moment it is captured — there is no
        // mandatory button here any more. What shows is honest status: queued, running (with
        // real progress), or a failure with a retry — see documentation/07-document-pipeline.md
        // §7-§8.
        when {
            processingState is ProcessingState.Running && processingState.documentId == state.document.id ->
                ProcessingBanner(processingState)
            state.document.status == DocumentStatus.QUEUED -> QueuedBanner()
            state.document.status == DocumentStatus.FAILED ->
                FailedBanner(
                    // Latest, not first: a document can be reprocessed after a first failure,
                    // so the most recent PROCESSING_FAILED event is the one that actually
                    // explains the current FAILED status.
                    reason = state.timeline
                        .filter { it.eventType == TimelineEventType.PROCESSING_FAILED }
                        .maxByOrNull { it.createdAt },
                    onRetry = { onProcess(true) },
                    onDelete = onDelete,
                )
        }

        // Action row — nothing to act on yet while a document is new, queued or running.
        if (state.document.status != DocumentStatus.NEW &&
            state.document.status != DocumentStatus.QUEUED &&
            state.document.status != DocumentStatus.PROCESSING
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.document.status != DocumentStatus.FAILED) {
                    FilledTonalButton(onClick = onChatClick, modifier = Modifier.weight(1f)) {
                        Icon(PamIcons.AiChat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ask AI")
                    }
                    OutlinedButton(onClick = { onProcess(true) }) {
                        Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reprocess")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (state.document.isFavorite) PamIcons.Favorite else PamIcons.FavoriteOutlined,
                        contentDescription = "Toggle favorite",
                        tint = if (state.document.isFavorite) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab.ordinal) {
            DetailTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(when (tab) {
                            DetailTab.PAGES -> "Pages (${state.pages.size})"
                            DetailTab.EXTRACTED -> "Extracted (${state.extractedData.size})"
                            DetailTab.TIMELINE -> "Timeline (${state.timeline.size})"
                        })
                    },
                )
            }
        }

        when (selectedTab) {
            DetailTab.PAGES -> PagesTab(state.pages, onSharePdf, externalLaunch, initialPage)
            DetailTab.EXTRACTED -> ExtractedTemplateTab(
                document = state.document,
                data = state.extractedData,
                language = state.document.language,
                extractionPagesRead = state.document.extractionPagesRead,
                extractionTotalPages = state.document.extractionTotalPages,
                profileSuggestions = profileSuggestions,
                entityProposals = entityProposals,
                summaryComing = summaryComing,
                onConfirm = onConfirmField,
                onConfirmAll = onConfirmAllFields,
                onAddClick = onAddClick,
                onUpdate = onUpdateField,
                onDelete = onDeleteField,
                onLinkProfile = onLinkProfile,
                onCreateProfile = onCreateProfile,
                onDismissSuggestion = onDismissSuggestion,
                onAcceptProposal = onAcceptProposal,
                onDismissProposal = onDismissProposal,
                onReprocess = { onProcess(true) },
            )
            DetailTab.TIMELINE -> TimelineTab(state.timeline)
        }
    }
}

/**
 * Shown instead of the normal detail content for a document that is currently in the trash —
 * whether it was restored-from or opened via Recently deleted, or arrived here via a
 * citation chip/deep link into something already trashed. Restoring here brings back the
 * normal content in place, with no navigation.
 */
@Composable
private fun TrashedDocumentState(title: String, onRestore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            PamIcons.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("This document was deleted", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "\"$title\" is in Recently deleted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRestore) { Text("Restore") }
    }
}

@Composable
private fun ProcessingBanner(state: ProcessingState.Running) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp)) {
        Text(state.toDisplayMessage(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(modifier = Modifier.height(8.dp))
        // UNDERSTAND has no meaningful fraction: the pipeline freezes `progress` between
        // 0.7 and 0.9 for however long the on-device model takes, which is unbounded and
        // varies wildly with document length and device speed. A determinate bar that stops
        // moving reads as stuck; an indeterminate one reads as "still working" — which is
        // the truth. Every other stage does report real, moving progress and keeps the
        // determinate bar.
        if (state.stage == ProcessingStage.UNDERSTAND) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QueuedBanner() {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)) {
        Text(
            "Waiting to be read…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * `reason` is the latest `PROCESSING_FAILED` timeline event, when one was recorded — a
 * document that failed before this task's fix (or whose `failDocument` write itself failed)
 * has none, and falls back to the generic message. A no-pages document can never be read by
 * retrying it: the pages that would need OCR were never saved, so "Try again" would just fail
 * the same way again. Delete (or re-scanning) is the only way out — see
 * `DocumentProcessingPipeline.failDocument`'s `REASON_NO_PAGES`.
 */
@Composable
private fun FailedBanner(reason: TimelineEvent?, onRetry: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isNoPages = reason?.data == "no_pages"

    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(16.dp)) {
        Text(
            if (isNoPages) {
                "This document has no pages. Delete it or scan it again."
            } else {
                reason?.description ?: "Something went wrong while reading this document."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (isNoPages) {
            OutlinedButton(onClick = { showDeleteConfirm = true }) {
                Icon(PamIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete")
            }
        } else {
            OutlinedButton(onClick = onRetry) {
                Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Try again")
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this document?") },
            text = { Text("It has no pages to read, so nothing can be recovered from it.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Turns the data layer's structured progress into the English a user reads.
 *
 * `ProcessingState` carries a stage and numbers only — never a sentence (see its doc comment
 * in `:core:model`). Deciding what that sentence says is a presentation concern, so it lives
 * here rather than in `DocumentProcessingPipeline`. Kept as plain Kotlin rather than
 * `stringResource` for now: localisation is a separate, deliberately deferred task, not
 * something to introduce as a side effect of this boundary fix.
 */
private fun ProcessingState.Running.toDisplayMessage(): String = when (stage) {
    ProcessingStage.CAPTURE -> "Preparing document..."
    ProcessingStage.READ -> if (currentPage != null && totalPages != null) {
        "OCR: Page $currentPage/$totalPages"
    } else {
        "Starting OCR..."
    }
    ProcessingStage.UNDERSTAND -> "Understanding the letter — this can take a minute"
    ProcessingStage.LINK -> "Matching profiles..."
    ProcessingStage.INDEX -> "Indexing for search..."
}

// ═══════════════════════════════════════════════════════════
// Pages Tab — with working Share/Open/Re-scan
// ═══════════════════════════════════════════════════════════

@Composable
private fun PagesTab(
    pages: List<DocumentPage>,
    onSharePdf: () -> File?,
    externalLaunch: ExternalLaunch,
    initialPage: Int? = null,
) {
    if (pages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pages scanned yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // A citation's page number is 1-based (how a reader talks about a document); the pager
    // is 0-indexed, and clamped in case the citation is stale (the document was re-scanned
    // with fewer pages since).
    val startPage = initialPage?.minus(1)?.coerceIn(0, pages.size - 1) ?: 0
    val pagerState = rememberPagerState(initialPage = startPage, pageCount = { pages.size })
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { pageIndex ->
            val page = pages[pageIndex]
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                AsyncImage(
                    model = page.imagePath,
                    contentDescription = "Page ${page.pageNumber}",
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
                if (!page.ocrText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        Text(page.ocrText!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    // Share as PDF
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(onClick = { sharePdf(context, onSharePdf, externalLaunch) }) {
                            Icon(PamIcons.Pdf, contentDescription = "Share PDF", modifier = Modifier.size(20.dp))
                        }
                        Text("Share PDF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Open / Download
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(onClick = { openPageImage(context, page, externalLaunch) }) {
                            Icon(PamIcons.Gallery, contentDescription = "Open", modifier = Modifier.size(20.dp))
                        }
                        Text("Open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Re-scan
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalIconButton(onClick = {
                            Toast.makeText(context, "Re-scan coming in next update", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(PamIcons.Camera, contentDescription = "Re-scan", modifier = Modifier.size(20.dp))
                        }
                        Text("Re-scan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Copy text
                    if (!page.ocrText.isNullOrBlank()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledTonalIconButton(onClick = { copyOcrText(context, page) }) {
                                Icon(PamIcons.Edit, contentDescription = "Copy text", modifier = Modifier.size(20.dp))
                            }
                            Text("Copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        // Page indicator dots
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { index ->
                val selected = pagerState.currentPage == index
                Box(modifier = Modifier.padding(2.dp).size(if (selected) 10.dp else 6.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

private fun getFileUri(context: Context, path: String): Uri? {
    return try {
        val uri = Uri.parse(path)
        // If it's already a content:// URI, use directly
        if (uri.scheme == "content") return uri
        // If it's a file:// URI or raw path, use FileProvider
        val file = if (uri.scheme == "file") File(uri.path!!) else File(path)
        if (file.exists()) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else null
    } catch (_: Exception) { null }
}

/**
 * The screen's side of [com.postsaimanager.core.domain.applock.ExternalFlowGuard]: say a trip out of
 * the app is about to happen ([expect]), and say it did not (or has ended) ([finish]). The pattern is
 * expect, then launch, then finish when the launch fails or a result comes back; a return from the
 * background consumes the protection by itself.
 */
private class ExternalLaunch(val expect: (reason: String) -> Unit, val finish: () -> Unit)

private fun sharePdf(context: Context, generatePdf: () -> File?, external: ExternalLaunch) {
    val pdfFile = generatePdf()
    if (pdfFile != null && pdfFile.exists()) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // The share sheet sends the user to another app and back: the app lock must not treat the
        // return as an ordinary background.
        external.expect("share-pdf")
        try {
            context.startActivity(Intent.createChooser(shareIntent, "Share document as PDF"))
        } catch (_: Exception) {
            external.finish()
            Toast.makeText(context, context.getString(R.string.share_sheet_unavailable), Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Failed to generate PDF", Toast.LENGTH_SHORT).show()
    }
}

private fun openPageImage(context: Context, page: DocumentPage, external: ExternalLaunch) {
    val uri = getFileUri(context, page.imagePath)
    if (uri != null) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Opening the page in another app is a trip out and back, like the share sheet.
        external.expect("open-in-another-app")
        try {
            context.startActivity(viewIntent)
        } catch (_: Exception) {
            external.finish()
            Toast.makeText(context, "No app found to open images", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "Unable to open: file not found", Toast.LENGTH_SHORT).show()
    }
}

private fun copyOcrText(context: Context, page: DocumentPage) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR Text", page.ocrText))
    Toast.makeText(context, "Text copied to clipboard", Toast.LENGTH_SHORT).show()
}

// ═══════════════════════════════════════════════════════════
// Extracted Tab — with profile suggestions
// ═══════════════════════════════════════════════════════════

@Composable
private fun ExtractedTemplateTab(
    document: Document,
    data: List<ExtractedData>,
    language: String?,
    /** See [com.postsaimanager.core.model.Document.extractionPagesRead] (5.4). */
    extractionPagesRead: Int?,
    extractionTotalPages: Int?,
    profileSuggestions: List<ProfileSuggestion>,
    entityProposals: List<EntityProposal>,
    summaryComing: Boolean,
    onConfirm: (String) -> Unit,
    onConfirmAll: () -> Unit,
    onAddClick: () -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onLinkProfile: (ProfileSuggestion) -> Unit,
    onCreateProfile: (ProfileSuggestion) -> Unit,
    onDismissSuggestion: (ProfileSuggestion) -> Unit,
    onAcceptProposal: (EntityProposal) -> Unit,
    onDismissProposal: (EntityProposal) -> Unit,
    onReprocess: () -> Unit,
) {
    var editingField by remember { mutableStateOf<ExtractedData?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (data.isEmpty() && profileSuggestions.isEmpty() && entityProposals.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("No extracted data yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Process the document or add data manually", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onAddClick) {
                    Icon(PamIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Manually")
                }
            }
        } else {
            // The summary card first, then every field under "Details" grouped by what it is, then
            // the open extras the model found under "Other details" (collapsed). See
            // ExtractedPresenter for the grouping and for which extras start hidden.
            var showAllExtras by remember { mutableStateOf(false) }
            var extrasExpanded by remember { mutableStateOf(false) }
            val presentation = remember(document, data, showAllExtras, summaryComing) {
                ExtractedPresenter.present(document, data, showAllExtras, summaryComing)
            }

            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Language + retry header
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        val langName = when (language) { "de" -> "🇩🇪 German"; "ar" -> "🇸🇦 Arabic"; "en" -> "🇬🇧 English"; else -> "🌐 ${language ?: "Unknown"}" }
                        Text("Detected: $langName", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        OutlinedButton(onClick = onReprocess, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Re-extract", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // 5.4: the assistant did not see the whole document — a long letter's layout
                // had to be cut to fit the extraction budget. Subtle (a caption, not a
                // warning colour) because it is informational, not something wrong with the
                // extraction that already happened.
                if (extractionPagesRead != null && extractionTotalPages != null &&
                    extractionPagesRead < extractionTotalPages
                ) {
                    item {
                        Text(
                            LocalContext.current.resources.getQuantityString(
                                R.plurals.extraction_partial_notice, extractionTotalPages,
                                extractionPagesRead, extractionTotalPages,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }

                // ── Entity Proposals — found in the document, but not something the app
                // decides on its own (see EntityLinkingUseCase). Above the profile-matching
                // suggestions below: these are the ones still waiting on a person, so they
                // are seen first rather than after scrolling past what already matched.
                if (entityProposals.isNotEmpty()) {
                    item { EntityProposalsSummary(entityProposals) }
                    items(entityProposals, key = { it.id }) { proposal ->
                        EntityProposalCard(
                            proposal = proposal,
                            onAccept = { onAcceptProposal(proposal) },
                            onDismiss = { onDismissProposal(proposal) },
                        )
                    }
                }

                // ── Profile Suggestions ──
                val activeSuggestions = profileSuggestions.filter { !it.isAutoLinked }
                val autoLinked = profileSuggestions.filter { it.isAutoLinked }

                if (activeSuggestions.isNotEmpty()) {
                    item { SectionHeader("👤 Profile Suggestions") }
                    items(activeSuggestions) { suggestion ->
                        ProfileSuggestionCard(
                            suggestion = suggestion,
                            onLink = { onLinkProfile(suggestion) },
                            onCreate = { onCreateProfile(suggestion) },
                            onDismiss = { onDismissSuggestion(suggestion) },
                        )
                    }
                }

                if (autoLinked.isNotEmpty()) {
                    item { SectionHeader("✅ Linked Profiles") }
                    items(autoLinked) { suggestion ->
                        LinkedProfileCard(suggestion)
                    }
                }

                // ── The document at a glance ──
                if (!presentation.summary.isEmpty) {
                    item { SummaryCardView(presentation.summary) }
                }

                // ── Extracted Data Sections ──
                // Above the fields, so what needs attention is seen before the scroll
                // rather than found during it.
                item { ReviewSummary(data, onConfirmAll) }

                if (presentation.details.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.section_details)) }
                }
                presentation.details.forEach { section ->
                    item(key = "group-${section.group}") {
                        Text(
                            stringResource(groupTitle(section.group)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    items(section.fields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }

                if (presentation.extraCount > 0) {
                    item(key = "other-details") {
                        OtherDetailsHeader(
                            count = presentation.extraCount,
                            expanded = extrasExpanded,
                            onToggle = { extrasExpanded = !extrasExpanded },
                        )
                    }
                    if (extrasExpanded) {
                        items(presentation.extras, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                        if (presentation.hiddenExtras > 0) {
                            item(key = "extras-show-all") {
                                TextButton(onClick = { showAllExtras = true }) {
                                    Text(stringResource(R.string.other_details_show_all, presentation.hiddenExtras))
                                }
                            }
                        } else if (showAllExtras) {
                            item(key = "extras-show-fewer") {
                                TextButton(onClick = { showAllExtras = false }) {
                                    Text(stringResource(R.string.other_details_show_fewer))
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(72.dp)) }
            }
        }
    }

    editingField?.let { field ->
        EditFieldDialog(field = field, onDismiss = { editingField = null }, onSave = { name, value -> onUpdate(field.id, name, value); editingField = null })
    }
}

// ═══════════════════════════════════════════════════════════
// Profile Suggestion Cards
// ═══════════════════════════════════════════════════════════

@Composable
private fun ProfileSuggestionCard(
    suggestion: ProfileSuggestion,
    onLink: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (suggestion.matchType) {
                MatchType.EXACT_MATCH -> MaterialTheme.colorScheme.primaryContainer
                MatchType.POSSIBLE_MATCH -> MaterialTheme.colorScheme.tertiaryContainer
                MatchType.NEW_PROFILE -> MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (suggestion.extractedOrganization ?: suggestion.extractedName ?: "?")
                            .take(2).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (suggestion.role) {
                            com.postsaimanager.core.model.ProfileRole.SENDER -> "📤 Sender"
                            com.postsaimanager.core.model.ProfileRole.RECEIVER -> "📥 Receiver"
                            else -> "👤 Contact"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = suggestion.extractedOrganization ?: suggestion.extractedName ?: "Unknown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (suggestion.extractedName != null && suggestion.extractedOrganization != null) {
                        Text(
                            text = suggestion.extractedName!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(PamIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Match info
            when (suggestion.matchType) {
                MatchType.EXACT_MATCH -> {
                    Text(
                        text = "✅ Matches existing profile: ${suggestion.existingProfile?.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${(suggestion.confidence * 100).toInt()}% match confidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onLink, modifier = Modifier.fillMaxWidth()) {
                        Icon(PamIcons.Profiles, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Link to ${suggestion.existingProfile?.name}")
                    }
                }

                MatchType.POSSIBLE_MATCH -> {
                    Text(
                        text = "🔍 Possible match: ${suggestion.existingProfile?.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${(suggestion.confidence * 100).toInt()}% match — is this the same profile?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onLink, modifier = Modifier.weight(1f)) {
                            Text("Yes, link it")
                        }
                        OutlinedButton(onClick = onCreate, modifier = Modifier.weight(1f)) {
                            Text("No, new profile")
                        }
                    }
                }

                MatchType.NEW_PROFILE -> {
                    Text(
                        text = "🆕 No matching profile found",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    suggestion.extractedEmail?.let {
                        Text("📧 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    suggestion.extractedPhone?.let {
                        Text("📞 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onCreate,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    ) {
                        Icon(PamIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create Profile")
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedProfileCard(suggestion: ProfileSuggestion) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    (suggestion.existingProfile?.name ?: "?").take(2).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = suggestion.existingProfile?.name ?: suggestion.extractedOrganization ?: "Profile",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Linked as ${suggestion.role.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(PamIcons.Favorite, contentDescription = "Linked", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Add/Edit Dialogs
// ═══════════════════════════════════════════════════════════

@Composable
private fun AddFieldDialog(onDismiss: () -> Unit, onAdd: (String, String, ExtractedFieldType) -> Unit) {
    var fieldName by remember { mutableStateOf("") }
    var fieldValue by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ExtractedFieldType.TEXT) }

    val presets = listOf(
        "Receiver Name" to ExtractedFieldType.PERSON_NAME,
        "Receiver Organization" to ExtractedFieldType.ORGANIZATION,
        "Receiver Address" to ExtractedFieldType.ADDRESS,
        "Sender Name" to ExtractedFieldType.PERSON_NAME,
        "Sender Organization" to ExtractedFieldType.ORGANIZATION,
        "Subject" to ExtractedFieldType.SUBJECT,
        "Date" to ExtractedFieldType.DATE,
        "Deadline" to ExtractedFieldType.DEADLINE,
        "Reference Number" to ExtractedFieldType.REFERENCE_NUMBER,
        "IBAN" to ExtractedFieldType.IBAN,
        "Amount" to ExtractedFieldType.OTHER,
        "Tag" to ExtractedFieldType.TAG_SUGGESTION,
        "Note" to ExtractedFieldType.TEXT,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Field") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Quick templates:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (name, type) ->
                                OutlinedButton(onClick = { fieldName = name; selectedType = type }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(value = fieldName, onValueChange = { fieldName = it }, label = { Text("Field Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = fieldValue, onValueChange = { fieldValue = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }
        },
        confirmButton = { Button(onClick = { onAdd(fieldName.trim(), fieldValue.trim(), selectedType) }, enabled = fieldName.isNotBlank() && fieldValue.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDocumentDialog(currentTitle: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.rename_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onSave(title.trim()) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EditFieldDialog(field: ExtractedData, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    // A name that is a key (a found value's, or an extra's bare slot key) is edited as the words the
    // screen shows for it; left unchanged, the stored key is kept.
    val keyed = SlotLabels.found(field.slotKey) != null || SlotLabels.extraKeyName(field.fieldName) != null
    val shownName = fieldLabelText(field)
    var editName by remember { mutableStateOf(if (keyed) shownName else field.fieldName) }
    var editValue by remember { mutableStateOf(field.fieldValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Field") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Field Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = editValue, onValueChange = { editValue = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
                Text("Type: ${field.fieldType.name} · ${(field.confidence * 100).toInt()}% confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onSave(if (keyed && editName.trim() == shownName) field.fieldName else editName.trim(), editValue.trim()) }, enabled = editName.isNotBlank() && editValue.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ═══════════════════════════════════════════════════════════
// Shared Components
// ═══════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun groupTitle(group: DetailGroup): Int = when (group) {
    DetailGroup.PARTIES -> R.string.group_parties
    DetailGroup.MONEY -> R.string.group_money
    DetailGroup.DATES -> R.string.group_dates
    DetailGroup.REFERENCES -> R.string.group_references
    DetailGroup.TEXT -> R.string.group_text
}

/**
 * The document at a glance: from, for, type, amount, due and the AI's summary, marked as the AI's.
 * A line the checks flagged carries the "Worth checking" marker.
 */
@Composable
private fun SummaryCardView(card: SummaryCard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.from?.let { SummaryRow(stringResource(R.string.card_from), it) }
            card.forWhom?.let { SummaryRow(stringResource(R.string.card_for), it) }
            card.typeId?.let { id ->
                val typeName = SlotLabels.type(id)?.let { stringResource(it) } ?: id
                SummaryRow(stringResource(R.string.card_type), CardLine(typeName, worthChecking = false))
            }
            card.amount?.let { SummaryRow(stringResource(R.string.card_amount), it) }
            card.due?.let { SummaryRow(stringResource(R.string.card_due), it) }
            card.aiSummary?.let { summary ->
                if (card.from != null || card.forWhom != null || card.typeId != null || card.amount != null || card.due != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Text(
                    stringResource(R.string.card_ai_summary),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            // The summary is written after the result is shown (the reading's second stage): say so until it lands.
            if (card.aiSummary == null && card.summaryComing) {
                Text(
                    stringResource(R.string.card_summary_coming),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, line: CardLine) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(line.value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (line.worthChecking) {
                Text(
                    stringResource(R.string.card_worth_checking),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun OtherDetailsHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.section_other_details_count, count),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (expanded) "−" else "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * One extracted field.
 *
 * Three states are visually distinct, because they mean different things to the person
 * reading them:
 *
 *  - **Needs review** — the extractor was unsure, or now disagrees with the value here.
 *    Outlined in the error colour: it is the only state that is asking for something.
 *  - **Yours / confirmed** — you wrote or accepted this. The machine will not change it.
 *  - **Machine, confident** — plain. Most fields, and they should recede.
 */
@Composable
private fun FieldCard(field: ExtractedData, onConfirm: (String) -> Unit, onEdit: (ExtractedData) -> Unit, onDelete: (String) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val isUsers = field.source == ValueSource.USER

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = if (field.needsReview) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = when {
                field.needsReview -> MaterialTheme.colorScheme.errorContainer
                isUsers || field.isConfirmed -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp).clickable { onEdit(field) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    // The label is rendered from the slot key; a name the person typed, or an
                    // extra's label as the letter printed it, is shown as stored.
                    Text(
                        fieldLabelText(field),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(field.fieldValue, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        // Confidence is the extractor's opinion of its own reading. Once a
                        // person has set the value it says nothing, so it is not shown.
                        if (isUsers) stringResource(R.string.field_you_set_this)
                        else stringResource(R.string.field_confidence, (field.confidence * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(PamIcons.More, contentDescription = "More", modifier = Modifier.size(20.dp)) }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { showMenu = false; onEdit(field) }, leadingIcon = { Icon(PamIcons.Edit, null, Modifier.size(18.dp)) })
                        if (!field.isConfirmed) {
                            DropdownMenuItem(text = { Text("Confirm") }, onClick = { showMenu = false; onConfirm(field.id) }, leadingIcon = { Icon(PamIcons.Favorite, null, Modifier.size(18.dp)) })
                        }
                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { showMenu = false; onDelete(field.id) },
                            leadingIcon = { Icon(PamIcons.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) })
                    }
                }
            }

            // The disagreement, spelled out. "This changed" would not be enough for anyone
            // to decide anything — the previous reading is what makes it actionable.
            if (field.hasUnreviewedMachineChange && field.machineValue != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "The document now reads \"${field.machineValue}\" here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onConfirm(field.id) }) { Text("Keep mine") }
                    TextButton(onClick = { onEdit(field) }) { Text("Review") }
                }
            } else if (field.needsReview) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    stringResource(R.string.field_worth_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * A count of what wants attention, above the fields, plus "Confirm all" (5.3) when there is
 * anything left to confirm.
 *
 * Without the count, a low-confidence field is only found by scrolling and noticing a colour
 * — fine for three fields, useless for a document with twenty. Without "Confirm all", clearing
 * a document with many machine-confident fields means tapping Confirm on each one individually
 * even though most of them are not in question at all.
 */
@Composable
private fun ReviewSummary(fields: List<ExtractedData>, onConfirmAll: () -> Unit) {
    val needing = fields.count { it.needsReview }
    val unconfirmed = fields.count { !it.isConfirmed && !it.deletedByUser }
    if (needing == 0 && unconfirmed == 0) return

    // "Confirm all" stays available even with low-confidence fields among them — it just
    // asks once first, rather than being hidden or requiring them to be resolved individually.
    var showLowConfidencePrompt by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (needing > 0) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (needing > 0) {
                Text(
                    if (needing == 1) "1 field is worth checking" else "$needing fields are worth checking",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "The extractor was unsure, or the document now reads differently. " +
                        "Your corrections are kept when a document is processed again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (unconfirmed > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = if (needing > 0) 8.dp else 0.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { if (needing > 0) showLowConfidencePrompt = true else onConfirmAll() },
                    ) {
                        Text(if (unconfirmed == 1) "Confirm 1 field" else "Confirm all $unconfirmed fields")
                    }
                }
            }
        }
    }

    if (showLowConfidencePrompt) {
        AlertDialog(
            onDismissRequest = { showLowConfidencePrompt = false },
            title = { Text("Confirm anyway?") },
            text = {
                Text(
                    if (needing == 1) {
                        "1 field is worth checking — confirm anyway?"
                    } else {
                        "$needing fields are worth checking — confirm anyway?"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { showLowConfidencePrompt = false; onConfirmAll() }) {
                    Text("Confirm all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLowConfidencePrompt = false }) { Text("Cancel") }
            },
        )
    }
}

// ═══════════════════════════════════════════════════════════
// Entity Proposals — people/organisations the model found but would not act on alone
// ═══════════════════════════════════════════════════════════

/**
 * A count of what is waiting on a person's answer, above the individual questions.
 *
 * Mirrors [ReviewSummary]'s reasoning: without a total, "is this me?" for the recipient and
 * "add Layla?" for a mentioned spouse are each found only by scrolling — fine for one entity,
 * easy to miss for several.
 */
@Composable
private fun EntityProposalsSummary(proposals: List<EntityProposal>) {
    if (proposals.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (proposals.size == 1) "1 thing found in this document needs your answer"
                else "${proposals.size} things found in this document need your answer",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "The app will not create a profile for any of these on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * One [EntityProposal], asked in words rather than shown as raw data — never
 * "Entity proposal: Layla (MENTIONED, 0.95)". See [EntityProposal.question] and
 * [EntityLinkingUseCase][com.postsaimanager.core.domain.usecase.EntityLinkingUseCase]'s class
 * doc for why the app asks instead of deciding here.
 *
 * Answering either way is final: accepting creates and links the profile, dismissing tells
 * the app not to ask about this entity on this document again (see `EntityProposalService`).
 * There is no third "later" option, because [pendingProposals][
 * com.postsaimanager.core.domain.document.EntityProposalService.pendingProposals] already
 * keeps the question on screen until one of these two is pressed.
 */
@Composable
private fun EntityProposalCard(
    proposal: EntityProposal,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isRecipient = proposal.entityRole == EntityRole.RECIPIENT
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(proposal.question(), style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Text(
                        when {
                            isRecipient -> "Yes, that's me"
                            // A known profile is on file — accepting links to it, not a
                            // second row for the same person/organisation, so the button
                            // must not promise "profile" as if one will be made.
                            proposal.existingProfileId != null -> "Link profile"
                            else -> "Add profile"
                        },
                    )
                }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(if (isRecipient) "No, someone else" else "Not now")
                }
            }
        }
    }
}

/**
 * The plain-English question for one [EntityProposal].
 *
 * [EntityRole] decides the shape of the sentence, exactly as it decides the shape of the
 * underlying decision in `EntityLinkingUseCase` — a `RECIPIENT` proposal is never "add a
 * profile for this name", it is "is this you?", because that is the one thing the app is
 * actually unsure about (see that class's doc on `ProfileType.USER_SELF`). A `MENTIONED`
 * proposal states what the document said and mentions the relation whenever the model
 * supplied one, since "spouse of the recipient" is what makes the entity meaningful rather
 * than an unexplained name.
 *
 * [existingProfileId] shifts the closing question from creating to linking for the same
 * reason the button does (see [EntityProposalCard]) — a plausible match was already found,
 * so "add them as a contact?" would describe a profile the app is not actually about to
 * make. `RECIPIENT` is unaffected: "is this you?" already asks about identity, not creation,
 * so it reads correctly whether accepting links or creates.
 */
private fun EntityProposal.question(): String = when (entityRole) {
    EntityRole.RECIPIENT ->
        "This document is addressed to \"$entityName\". Is this you?"

    EntityRole.MENTIONED -> if (relation.isNotBlank()) {
        "\"$entityName\" is mentioned in this document, as $relation. " +
            if (existingProfileId != null) {
                "Link them to the existing profile?"
            } else {
                "Add them as a profile?"
            }
    } else {
        "\"$entityName\" is mentioned in this document. " +
            if (existingProfileId != null) {
                "Link them to the existing profile?"
            } else {
                "Add them as a profile?"
            }
    }

    EntityRole.SENDER_CONTACT -> if (organization != null) {
        "\"$entityName\" signed this document on behalf of $organization. " +
            if (existingProfileId != null) {
                "Link them to the existing contact?"
            } else {
                "Add them as a contact?"
            }
    } else {
        "\"$entityName\" signed this document, but it is not clear yet who sent it. " +
            if (existingProfileId != null) {
                "Link them to the existing contact anyway?"
            } else {
                "Add them as a contact anyway?"
            }
    }

    EntityRole.SENDER -> if (existingProfileId != null) {
        "\"$entityName\" appears to have sent this document. Link them to the existing profile?"
    } else {
        "\"$entityName\" appears to have sent this document. Add a profile for them?"
    }
}

// ═══════════════════════════════════════════════════════════
// Timeline Tab
// ═══════════════════════════════════════════════════════════

/** A field label key ([ExtractedData.labelKey]) in the user's language: a slot's string, else the name as it was stored. */
@Composable
private fun labelText(key: String): String {
    val res = SlotLabels.slot(key)
    if (res != null) return stringResource(res)
    SlotLabels.found(key)?.let { return stringResource(it.res, it.number) }
    return SlotLabels.extraKeyName(key) ?: key
}

/** A field's label: its slot's string, a found value's words, else the name as stored (a person's, or an extra's printed label). */
@Composable
private fun fieldLabelText(field: ExtractedData): String {
    SlotLabels.labelFor(field)?.let { return stringResource(it) }
    SlotLabels.found(field.slotKey)?.let { return stringResource(it.res, it.number) }
    return SlotLabels.extraKeyName(field.fieldName) ?: field.fieldName
}

/** The title and the optional second line of a timeline entry, worded from string resources. */
@Composable
private fun timelineLines(text: TimelineText): Pair<String, String?> {
    val resources = LocalContext.current.resources
    return when (text) {
        is TimelineText.OcrDone -> resources.getQuantityString(R.plurals.timeline_ocr_done, text.pages, text.pages) to
            text.confidencePercent?.let { stringResource(R.string.timeline_ocr_confidence, it) }
        is TimelineText.FieldsExtracted -> resources.getQuantityString(R.plurals.timeline_fields_extracted, text.count, text.count) to
            text.labelKeys.map { labelText(it) }.joinToString(", ").ifBlank { null }
        is TimelineText.ReviewFlagged -> resources.getQuantityString(R.plurals.timeline_review_flagged, text.count, text.count) to
            text.labelKeys.map { labelText(it) }.joinToString(", ").takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.timeline_review_flagged_detail, it) }
        is TimelineText.ProcessingFailed -> stringResource(R.string.timeline_processing_failed) to text.detail
        is TimelineText.Reprocessed -> stringResource(R.string.timeline_reprocessed) to null
        is TimelineText.ReprocessFailed -> stringResource(R.string.timeline_reprocess_failed) to null
        is TimelineText.Stored -> text.title to text.description
    }
}

@Composable
private fun TimelineTab(events: List<TimelineEvent>) {
    if (events.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No events yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(events, key = { it.id }) { event ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    val (title, description) = timelineLines(event.toText())
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(event.createdAt.toRelativeTime(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
