package com.postsaimanager.feature.home

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.common.extensions.toRelativeTime
import com.postsaimanager.core.designsystem.component.PamEmptyState
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onDocumentClick: (String) -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            PamTopAppBar(title = "Posts AI Manager")
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
        AnimatedContent(
            targetState = uiState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "home_content",
            modifier = Modifier.padding(innerPadding),
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
                    onDocumentClick = onDocumentClick,
                )
            }
        }
    }
}

@Composable
private fun DocumentList(
    documents: List<Document>,
    onDocumentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
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
        items(documents, key = { it.id }) { document ->
            DocumentCard(
                document = document,
                onClick = { onDocumentClick(document.id) },
            )
        }
    }
}

@Composable
private fun DocumentCard(
    document: Document,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Document type icon
            Icon(
                imageVector = PamIcons.Documents,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(status = document.status)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = document.createdAt.toRelativeTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Favorite
            if (document.isFavorite) {
                Icon(
                    imageVector = PamIcons.Favorite,
                    contentDescription = "Favorited",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: DocumentStatus) {
    val (label, color) = when (status) {
        DocumentStatus.NEW -> "New" to MaterialTheme.colorScheme.primary
        DocumentStatus.PROCESSING -> "Processing" to MaterialTheme.colorScheme.tertiary
        DocumentStatus.EXTRACTED -> "Extracted" to MaterialTheme.colorScheme.secondary
        DocumentStatus.REVIEWED -> "Reviewed" to MaterialTheme.colorScheme.primary
        DocumentStatus.ARCHIVED -> "Archived" to MaterialTheme.colorScheme.outline
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}
