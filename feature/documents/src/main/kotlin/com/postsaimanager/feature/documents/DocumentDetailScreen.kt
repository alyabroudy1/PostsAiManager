package com.postsaimanager.feature.documents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.postsaimanager.core.common.extensions.toFormattedDate
import com.postsaimanager.core.common.extensions.toRelativeTime
import com.postsaimanager.core.data.repository.ProcessingState
import com.postsaimanager.core.designsystem.component.PamErrorState
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.document.DocumentDetailUiState
import com.postsaimanager.core.model.DocumentPage
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.TimelineEvent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    onNavigateBack: () -> Unit,
    onChatClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val processingState by viewModel.processingProgress.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = when (val state = uiState) {
                    is DocumentDetailUiState.Success -> state.document.title
                    else -> "Document"
                },
                onNavigateBack = onNavigateBack,
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        AnimatedContent(
            targetState = uiState,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "detail_content",
            modifier = Modifier.padding(innerPadding),
        ) { state ->
            when (state) {
                is DocumentDetailUiState.Loading -> PamLoadingState()
                is DocumentDetailUiState.Error -> PamErrorState(
                    message = state.message,
                    icon = PamIcons.Error,
                )
                is DocumentDetailUiState.Success -> DocumentDetailContent(
                    state = state,
                    selectedTab = selectedTab,
                    processingState = processingState,
                    onTabSelected = viewModel::selectTab,
                    onProcess = viewModel::startProcessing,
                    onConfirmField = viewModel::confirmField,
                    onChatClick = { onChatClick(state.document.id) },
                    onToggleFavorite = viewModel::toggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun DocumentDetailContent(
    state: DocumentDetailUiState.Success,
    selectedTab: DetailTab,
    processingState: ProcessingState,
    onTabSelected: (DetailTab) -> Unit,
    onProcess: () -> Unit,
    onConfirmField: (String) -> Unit,
    onChatClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Processing banner
        if (processingState is ProcessingState.Running) {
            ProcessingBanner(processingState)
        }

        // Action row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.document.status == DocumentStatus.NEW) {
                Button(
                    onClick = onProcess,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Process Document")
                }
            } else {
                FilledTonalButton(
                    onClick = onChatClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(PamIcons.AiChat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ask AI")
                }
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

        // Tab row
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
        ) {
            DetailTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            when (tab) {
                                DetailTab.PAGES -> "Pages (${state.pages.size})"
                                DetailTab.EXTRACTED -> "Extracted (${state.extractedData.size})"
                                DetailTab.TIMELINE -> "Timeline (${state.timeline.size})"
                            }
                        )
                    },
                )
            }
        }

        // Tab content
        when (selectedTab) {
            DetailTab.PAGES -> PagesTab(state.pages)
            DetailTab.EXTRACTED -> ExtractedTab(state.extractedData, onConfirmField)
            DetailTab.TIMELINE -> TimelineTab(state.timeline)
        }
    }
}

@Composable
private fun ProcessingBanner(state: ProcessingState.Running) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun PagesTab(pages: List<DocumentPage>) {
    if (pages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pages", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { pageIndex ->
            val page = pages[pageIndex]
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Page image
                AsyncImage(
                    model = page.imagePath,
                    contentDescription = "Page ${page.pageNumber}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
                // OCR text preview
                if (!page.ocrText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Text(
                            text = page.ocrText!!,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Page indicator
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pages.size) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(if (selected) 10.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                )
            }
        }
    }
}

@Composable
private fun ExtractedTab(
    fields: List<ExtractedData>,
    onConfirm: (String) -> Unit,
) {
    if (fields.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("No extracted data yet", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Process the document to extract fields", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(fields, key = { it.id }) { field ->
            ExtractedFieldCard(field = field, onConfirm = { onConfirm(field.id) })
        }
    }
}

@Composable
private fun ExtractedFieldCard(
    field: ExtractedData,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (field.isConfirmed) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.fieldName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = field.fieldValue,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${field.fieldType.name} · ${(field.confidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!field.isConfirmed) {
                IconButton(onClick = onConfirm) {
                    Icon(
                        PamIcons.Documents, // checkmark
                        contentDescription = "Confirm",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Icon(
                    PamIcons.Favorite,
                    contentDescription = "Confirmed",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun TimelineTab(events: List<TimelineEvent>) {
    if (events.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No events yet", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(events, key = { it.id }) { event ->
            TimelineEventCard(event)
        }
    }
}

@Composable
private fun TimelineEventCard(event: TimelineEvent) {
    Row(modifier = Modifier.fillMaxWidth()) {
        // Timeline dot + line
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // Content
        Column {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
            )
            event.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = event.createdAt.toRelativeTime(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
