package com.postsaimanager.feature.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.DocumentListRow
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.importing.ImportStatus
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.DownloadSummary
import com.postsaimanager.core.model.ModelBannerState
import com.postsaimanager.core.model.ProcessingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onDocumentClick: (String) -> Unit,
    onScanClick: () -> Unit,
    onAskAcrossDocumentsClick: () -> Unit,
    onInstallModelClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    /** The person picked PDFs or images to import (read grants included); the caller opens the confirm sheet. */
    onImportPicked: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val modelBanner by viewModel.modelBanner.collectAsStateWithLifecycle()
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onImportPickerResult()
        if (uris.isNotEmpty()) onImportPicked(uris)
    }

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = "Posts AI Manager",
                actions = {
                    IconButton(onClick = onAskAcrossDocumentsClick) {
                        Icon(
                            imageVector = PamIcons.AiChat,
                            contentDescription = "Ask about your documents",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // "+" next to Scan: the system file picker for PDFs and images.
                SmallFloatingActionButton(
                    onClick = {
                        viewModel.onImportPickerLaunching()
                        importPicker.launch(arrayOf("application/pdf", "image/*"))
                    },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = PamIcons.Add,
                        contentDescription = stringResource(R.string.home_import_action),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                FloatingActionButton(
                    onClick = onScanClick,
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = PamIcons.Camera,
                        contentDescription = "Scan document",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            when (val banner = modelBanner) {
                ModelBannerState.Hidden -> Unit
                ModelBannerState.Install -> ModelBanner(onInstallModelClick)
                is ModelBannerState.Downloading -> DownloadBanner(banner.summary, failed = false, onClick = onDownloadsClick)
                is ModelBannerState.Failed -> DownloadBanner(banner.summary, failed = true, onClick = onDownloadsClick)
            }
            ImportBanner(importStatus, onDismissFailure = viewModel::dismissImportFailures)
            HomeContent(uiState, processingState, onDocumentClick, onScanClick, Modifier.weight(1f))
        }
    }
}

/**
 * "Importing…" while PDFs and images are turned into pages (the document appears in the list when its pages exist), or the failure,
 * which a tap dismisses. Nothing when no import is running.
 */
@Composable
private fun ImportBanner(status: ImportStatus, onDismissFailure: () -> Unit, modifier: Modifier = Modifier) {
    if (status.isIdle) return
    val running = status.running > 0
    Surface(
        color = if (running) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth().then(if (running) Modifier else Modifier.clickable(onClick = onDismissFailure)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(if (running) R.string.home_importing else R.string.home_import_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (running) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            )
            if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Shown after "Skip for now" on the first-run setup, until a chat model is installed. */
@Composable
private fun ModelBanner(onInstallClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_model_banner_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onInstallClick) {
                Text(stringResource(R.string.home_model_banner_action))
            }
        }
    }
}

/**
 * "Setting up AI · 1 of 3 · 45%" with a bar while the models download in the background, or the failure; a tap opens the models
 * screen, which lists each download with its own progress and a Retry.
 */
@Composable
private fun DownloadBanner(summary: DownloadSummary, failed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = when {
        failed -> stringResource(R.string.home_download_banner_failed)
        summary.percent != null -> stringResource(R.string.home_download_banner_progress, summary.position, summary.count, summary.percent!!)
        else -> stringResource(R.string.home_download_banner_progress_unknown, summary.position, summary.count)
    }
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (!failed) {
                val percent = summary.percent
                if (percent != null) {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    processingState: ProcessingState,
    onDocumentClick: (String) -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        AnimatedContent(
            targetState = uiState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "home_content",
        ) { state ->
            when (state) {
                is HomeUiState.Loading -> PamLoadingState()
                is HomeUiState.Empty -> PamEmptyState(
                    icon = PamIcons.Documents,
                    title = "No Documents Yet",
                    subtitle = "Scan or import your first document to get started.",
                    actionLabel = "Scan Document",
                    onAction = onScanClick,
                )
                is HomeUiState.Error -> PamErrorState(
                    message = state.message,
                    icon = PamIcons.Error,
                )
                is HomeUiState.Success -> DocumentList(
                    documents = state.recentDocuments,
                    processingState = processingState,
                    onDocumentClick = onDocumentClick,
                )
            }
        }
    }
}

@Composable
private fun DocumentList(
    documents: List<DocumentListItem>,
    processingState: ProcessingState,
    onDocumentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // The bottom clears the scan button (56dp high) and the import button above it (40dp, 12dp gap), 16dp margin: the buttons
        // float over the list.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + 88.dp + 52.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Recent Documents",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(documents, key = { it.id }) { item ->
            DocumentListRow(
                item = item,
                runningState = (processingState as? ProcessingState.Running)
                    ?.takeIf { it.documentId == item.id },
                onClick = { onDocumentClick(item.id) },
            )
        }
    }
}
