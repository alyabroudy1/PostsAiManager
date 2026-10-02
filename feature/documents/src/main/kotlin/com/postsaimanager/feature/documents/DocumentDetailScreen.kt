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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.postsaimanager.core.common.extensions.toRelativeTime
import com.postsaimanager.core.designsystem.component.PagePreviewDialog
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.component.documentDisplayTitle
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedFieldType
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.TimelineEvent
import com.postsaimanager.core.model.TimelineEventType
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    onNavigateBack: () -> Unit,
    onChatClick: (String) -> Unit,
    /**
     * "Help me fill it" / "Fill in this form": opens the document's chat with the form fill started. Null when form filling is
     * switched off: neither the menu item nor the Extracted-tab card is shown.
     */
    onFillForm: ((String) -> Unit)? = null,
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
    val summaryComing by viewModel.summaryComing.collectAsStateWithLifecycle()
    val pendingConfirmAllUndo by viewModel.pendingConfirmAllUndo.collectAsStateWithLifecycle()
    val fieldPreview by viewModel.fieldPreview.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    val undoLabel = stringResource(R.string.action_undo)
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    (uiState as? DocumentDetailUiState.Success)?.document?.takeIf { showRenameDialog }?.let { document ->
        RenameDocumentDialog(
            currentTitle = screenTitle(document),
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

    // "Confirm n confident" / "Confirm all" offer a cheap undo — the Snackbar itself both shows and resolves
    // it, so there is nothing to reconcile if the screen is left before it times out (the
    // ViewModel still holds the undo state; only its Snackbar is gone).
    LaunchedEffect(pendingConfirmAllUndo) {
        val confirmed = pendingConfirmAllUndo ?: return@LaunchedEffect
        val count = confirmed.size
        val result = snackbarHostState.showSnackbar(
            message = resources.getQuantityString(R.plurals.fields_confirmed, count, count),
            actionLabel = undoLabel,
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
                    is DocumentDetailUiState.Success -> screenTitle(state.document)
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
                            // Offered on any document: a letter can come with a form to fill in, and the user can always ask.
                            (uiState as? DocumentDetailUiState.Success)?.document?.takeUnless { it.isTrashed }?.let { document ->
                                if (onFillForm != null) DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_fill_form)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onFillForm(document.id)
                                    },
                                )
                            }
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
            // Animate between kinds of state only: every edit of the document is a new Success, and treating it as new content
            // would rebuild the tab and reset its scroll position.
            contentKey = { s -> if (s is DocumentDetailUiState.Success) "success-${s.document.isTrashed}" else s::class },
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
                        title = screenTitle(state.document),
                        onRestore = viewModel::restoreDocument,
                    )
                state is DocumentDetailUiState.Success -> DocumentDetailContent(
                    state = state,
                    selectedTab = selectedTab,
                    initialPage = initialPage,
                    processingState = processingState,
                    summaryComing = summaryComing,
                    onTabSelected = viewModel::selectTab,
                    onProcess = { force -> viewModel.startProcessing(force) },
                    fieldActions = FieldActions(
                        confirm = viewModel::confirmFields,
                        ignore = viewModel::ignoreFields,
                        restore = viewModel::restoreField,
                        edit = {},
                    ),
                    onConfirmConfident = viewModel::confirmConfidentFields,
                    onConfirmAll = viewModel::confirmAllFields,
                    onAddClick = { showAddDialog = true },
                    onUpdateField = viewModel::updateField,
                    onUpdateSummary = viewModel::updateSummary,
                    onChangeFamily = viewModel::changeFamily,
                    onReadAgainAs = viewModel::readAgainAs,
                    onShowOnPage = viewModel::showOnPage,
                    onChatClick = { onChatClick(state.document.id) },
                    onFillForm = onFillForm?.let { fill -> { fill(state.document.id) } },
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

    // "Show on page": layered over the screen, so closing returns to the open edit sheet.
    fieldPreview?.let { preview ->
        PagePreviewDialog(
            title = null,
            preview = preview.preview,
            loading = preview.loading,
            initialPageIndex = preview.initialPageIndex,
            onClose = viewModel::closeFieldPreview,
            onOpenDocument = null,
        )
    }
}

/** The title to show for [document]: a composed title is worded from the family's string resource, anything else as stored. */
@Composable
private fun screenTitle(document: Document): String {
    val context = LocalContext.current
    if (TitleComposer.isComposed(document.titleCode)) {
        composedTitleText(context, document.titleArgs)?.let { return it }
    }
    return documentDisplayTitle(document)
}

@Composable
private fun DocumentDetailContent(
    state: DocumentDetailUiState.Success,
    selectedTab: DetailTab,
    initialPage: Int?,
    processingState: ProcessingState,
    /** The reading's second stage (summary, extras) is still being written: the summary card says so. */
    summaryComing: Boolean,
    onTabSelected: (DetailTab) -> Unit,
    /** `force = true` restarts a document that is already `EXTRACTED`/`REVIEWED`, or retries
     * one that `FAILED`; `false` is used only for the auto-enqueue done by the ViewModel on
     * open, which this Composable never triggers directly. */
    onProcess: (force: Boolean) -> Unit,
    fieldActions: FieldActions,
    onConfirmConfident: () -> Unit,
    onConfirmAll: () -> Unit,
    onAddClick: () -> Unit,
    onUpdateField: (String, String, String) -> Unit,
    onUpdateSummary: (String) -> Unit,
    onChangeFamily: (String) -> Unit,
    onReadAgainAs: (String) -> Unit,
    onShowOnPage: (Int?, com.postsaimanager.core.model.TextBounds?) -> Unit,
    onSharePdf: () -> File?,
    externalLaunch: ExternalLaunch,
    onChatClick: () -> Unit,
    onFillForm: (() -> Unit)?,
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
            DetailTab.EXTRACTED -> ExtractedTab(
                document = state.document,
                data = state.extractedData,
                summaryComing = summaryComing,
                actions = fieldActions,
                onAddClick = onAddClick,
                onReprocess = { onProcess(true) },
                onChangeFamily = onChangeFamily,
                onReadAgainAs = onReadAgainAs,
                onConfirmConfident = onConfirmConfident,
                onConfirmAll = onConfirmAll,
                onUpdateField = onUpdateField,
                onUpdateSummary = onUpdateSummary,
                onShowOnPage = onShowOnPage,
                onFillForm = onFillForm,
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
// Dialogs
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

// ═══════════════════════════════════════════════════════════
// Timeline Tab
// ═══════════════════════════════════════════════════════════

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
