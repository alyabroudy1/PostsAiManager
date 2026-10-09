package com.postsaimanager.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.documentDisplayTitle
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.Document

/**
 * "Recently deleted" — trashed documents, with how many days remain before they're
 * auto-purged, and per-item Restore / Delete permanently. Reachable from Settings. See
 * documentation/07-document-pipeline.md, "Deleting documents".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onNavigateBack: () -> Unit,
    onDocumentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDeleteId by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = stringResource(R.string.documents_recently_deleted),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (uiState is TrashUiState.Success) {
                        TextButton(onClick = { confirmEmpty = true }) { Text(stringResource(R.string.trash_empty_action)) }
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        when (val state = uiState) {
            is TrashUiState.Loading -> PamLoadingState(modifier = Modifier.padding(innerPadding))
            is TrashUiState.Empty -> PamEmptyState(
                icon = PamIcons.Delete,
                title = stringResource(R.string.trash_nothing_title),
                subtitle = stringResource(R.string.trash_nothing_hint),
                modifier = Modifier.padding(innerPadding),
            )
            is TrashUiState.Success -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(innerPadding),
            ) {
                items(state.documents, key = { it.id }) { document ->
                    TrashListItem(
                        document = document,
                        onClick = { onDocumentClick(document.id) },
                        onRestore = { viewModel.restore(document.id) },
                        onDeletePermanently = { confirmDeleteId = document.id },
                    )
                }
            }
        }
    }

    confirmDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDeleteId = null },
            title = { Text(stringResource(R.string.trash_delete_title)) },
            text = { Text(stringResource(R.string.trash_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePermanently(id)
                    confirmDeleteId = null
                }) { Text(stringResource(R.string.detail_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteId = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.trash_empty_title)) },
            text = { Text(stringResource(R.string.trash_empty_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.emptyTrash()
                    confirmEmpty = false
                }) { Text(stringResource(R.string.trash_empty_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun TrashListItem(
    document: Document,
    onClick: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = PamIcons.Documents,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                val days = daysUntilPurge(document)
                Text(
                    text = if (days <= 0) {
                        stringResource(R.string.trash_deletes_today)
                    } else {
                        LocalContext.current.resources.getQuantityString(R.plurals.trash_deletes_in_days, days.toInt(), days.toInt())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRestore) {
                Icon(PamIcons.Restore, contentDescription = stringResource(R.string.action_restore))
            }
            IconButton(onClick = onDeletePermanently) {
                Icon(
                    PamIcons.Delete,
                    contentDescription = stringResource(R.string.trash_delete_permanently),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
