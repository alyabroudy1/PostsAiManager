package com.postsaimanager.feature.documents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.DocumentListRow
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.component.SwipeToDeleteRow
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.ProcessingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    onDocumentClick: (String) -> Unit,
    /** The top-bar bin: the same "Recently deleted" list the Settings entry opens. */
    onRecentlyDeletedClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val processingState by viewModel.processingState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val deletedMessage = stringResource(R.string.documents_deleted_snackbar)
    val undoLabel = stringResource(R.string.action_undo)
    // The write already happened when the row was swiped away (optimistic, same shape as the
    // detail screen's delete) — the id just drives the confirmation snackbar's Undo target.
    var pendingUndoId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pendingUndoId) {
        val id = pendingUndoId ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.onRestoreDocument(id)
        }
        pendingUndoId = null
    }

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = stringResource(R.string.documents_title),
                actions = {
                    IconButton(onClick = onRecentlyDeletedClick) {
                        Icon(PamIcons.Delete, contentDescription = stringResource(R.string.documents_recently_deleted))
                    }
                },
            )
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
                placeholder = { Text(stringResource(R.string.documents_search_placeholder)) },
                leadingIcon = {
                    Icon(PamIcons.Search, contentDescription = stringResource(R.string.documents_search_description))
                },
                trailingIcon = {
                    AnimatedVisibility(visible = searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(PamIcons.Close, contentDescription = stringResource(R.string.documents_search_clear))
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
                        title = stringResource(
                            if (searchQuery.isNotEmpty()) R.string.documents_no_results_title else R.string.documents_empty_title,
                        ),
                        subtitle = stringResource(
                            if (searchQuery.isNotEmpty()) R.string.documents_no_results_hint else R.string.documents_empty_hint,
                        ),
                    )
                    is DocumentsUiState.Error -> PamErrorState(
                        message = state.message,
                        icon = PamIcons.Error,
                    )
                    is DocumentsUiState.Success -> LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.documents, key = { it.id }) { item ->
                            SwipeToDeleteRow(
                                onDelete = {
                                    viewModel.onDeleteDocument(item.id)
                                    pendingUndoId = item.id
                                },
                            ) {
                                DocumentListRow(
                                    item = item,
                                    runningState = (processingState as? ProcessingState.Running)
                                        ?.takeIf { it.documentId == item.id },
                                    onClick = { onDocumentClick(item.id) },
                                    onFavoriteClick = { viewModel.onToggleFavorite(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


