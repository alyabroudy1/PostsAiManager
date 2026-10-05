package com.postsaimanager.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.ProcessingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onDocumentClick: (String) -> Unit,
    onScanClick: () -> Unit,
    onAskAcrossDocumentsClick: () -> Unit,
    onInstallModelClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showModelBanner by viewModel.showModelBanner.collectAsStateWithLifecycle()
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()

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
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            if (showModelBanner) ModelBanner(onInstallModelClick)
            HomeContent(uiState, processingState, onDocumentClick, onScanClick, Modifier.weight(1f))
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
        // The bottom clears the scan button (56dp high, 16dp margin) the screen floats over the list.
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + 88.dp),
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
