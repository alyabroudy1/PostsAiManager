package com.postsaimanager.feature.documents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.common.extensions.toFormattedDate
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.documentDisplayTitle
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.ProcessingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onDocumentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // The write already happened when the row was swiped away (optimistic, same shape as the
    // detail screen's delete) — the id just drives the confirmation snackbar's Undo target.
    var pendingUndoId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingUndoId) {
        val id = pendingUndoId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Document moved to Recently deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.onRestoreDocument(id)
        }
        pendingUndoId = null
    }

    Scaffold(
        topBar = {
            PamTopAppBar(title = "Documents")
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search documents...") },
                leadingIcon = {
                    Icon(PamIcons.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    AnimatedVisibility(visible = searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(PamIcons.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )

            // Content
            AnimatedContent(
                targetState = uiState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "documents_content",
            ) { state ->
                when (state) {
                    is DocumentsUiState.Loading -> PamLoadingState()
                    is DocumentsUiState.Empty -> PamEmptyState(
                        icon = PamIcons.Documents,
                        title = if (searchQuery.isNotEmpty()) "No Results" else "No Documents",
                        subtitle = if (searchQuery.isNotEmpty()) "Try a different search term."
                        else "Your scanned and imported documents will appear here.",
                    )
                    is DocumentsUiState.Error -> PamErrorState(
                        message = state.message,
                        icon = PamIcons.Error,
                    )
                    is DocumentsUiState.Success -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.documents, key = { it.id }) { document ->
                            SwipeToDeleteRow(
                                onDelete = {
                                    viewModel.onDeleteDocument(document.id)
                                    pendingUndoId = document.id
                                },
                            ) {
                                DocumentListItem(
                                    document = document,
                                    runningState = (processingState as? ProcessingState.Running)
                                        ?.takeIf { it.documentId == document.id },
                                    onClick = { onDocumentClick(document.id) },
                                    onFavoriteClick = { viewModel.onToggleFavorite(document.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Swipe-in-either-direction-to-trash for a single row. `SwipeToDismissBoxValue.StartToEnd`
 * and `.EndToStart` are both wired to delete — direction isn't semantically meaningful here,
 * only "the user swiped it away" — matching the platform's usual one-gesture-one-action swipe
 * pattern. The write is optimistic: dismissal fires [onDelete] immediately rather than waiting
 * for a confirmation, since the caller's Undo snackbar is the confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) onDelete()
            true
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Alignment.CenterEnd
                } else {
                    Alignment.CenterStart
                },
            ) {
                Icon(
                    PamIcons.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
    ) {
        content()
    }
}

@Composable
private fun DocumentListItem(
    document: Document,
    /** Non-null only when this document is the one currently being read/understood. */
    runningState: ProcessingState.Running?,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = PamIcons.Documents,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = documentDisplayTitle(document),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${document.statusLabel(runningState)} · ${document.createdAt.toFormattedDate()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (document.isFavorite) PamIcons.Favorite else PamIcons.FavoriteOutlined,
                    contentDescription = "Toggle favorite",
                    tint = if (document.isFavorite) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * The generic `document.status.name` reads fine for every status ([DocumentStatus.QUEUED]
 * included — "Queued" needs no special case), except `PROCESSING`: real progress is worth
 * more than a static word when it is cheaply available, which it is here since [Documents
 * ViewModel] already surfaces the pipeline's [ProcessingState].
 */
private fun Document.statusLabel(runningState: ProcessingState.Running?): String {
    if (status == DocumentStatus.PROCESSING && runningState != null) {
        return when (runningState.stage) {
            ProcessingStage.READ -> if (runningState.currentPage != null && runningState.totalPages != null) {
                "Reading page ${runningState.currentPage}/${runningState.totalPages}"
            } else {
                "Reading…"
            }
            ProcessingStage.UNDERSTAND -> "Analysing…"
            ProcessingStage.LINK -> "Matching profiles…"
            ProcessingStage.INDEX -> "Indexing…"
            ProcessingStage.CAPTURE -> "Preparing…"
        }
    }
    return status.name.lowercase().replaceFirstChar { it.uppercase() }
}
