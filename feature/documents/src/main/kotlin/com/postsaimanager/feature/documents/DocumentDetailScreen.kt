package com.postsaimanager.feature.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
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
import com.postsaimanager.core.model.ExtractedFieldType
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
                is DocumentDetailUiState.Error -> PamErrorState(message = state.message, icon = PamIcons.Error)
                is DocumentDetailUiState.Success -> DocumentDetailContent(
                    state = state,
                    selectedTab = selectedTab,
                    processingState = processingState,
                    onTabSelected = viewModel::selectTab,
                    onProcess = viewModel::startProcessing,
                    onConfirmField = viewModel::confirmField,
                    onAddField = viewModel::addField,
                    onUpdateField = viewModel::updateField,
                    onDeleteField = viewModel::deleteField,
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
    onAddField: (String, String, ExtractedFieldType) -> Unit,
    onUpdateField: (String, String, String) -> Unit,
    onDeleteField: (String) -> Unit,
    onChatClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (processingState is ProcessingState.Running) {
            ProcessingBanner(processingState)
        }

        // Action row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.document.status == DocumentStatus.NEW -> {
                    Button(onClick = onProcess, modifier = Modifier.weight(1f)) {
                        Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Process Document")
                    }
                }
                else -> {
                    FilledTonalButton(onClick = onChatClick, modifier = Modifier.weight(1f)) {
                        Icon(PamIcons.AiChat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ask AI")
                    }
                    OutlinedButton(onClick = onProcess) {
                        Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Re-extract")
                    }
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
            DetailTab.PAGES -> PagesTab(state.pages)
            DetailTab.EXTRACTED -> ExtractedTemplateTab(
                data = state.extractedData,
                language = state.document.language,
                documentId = state.document.id,
                onConfirm = onConfirmField,
                onAdd = onAddField,
                onUpdate = onUpdateField,
                onDelete = onDeleteField,
                onReprocess = onProcess,
            )
            DetailTab.TIMELINE -> TimelineTab(state.timeline)
        }
    }
}

@Composable
private fun ProcessingBanner(state: ProcessingState.Running) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
    ) {
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    }
}

// ═══════════════════════════════════════════════════════════
// Pages Tab
// ═══════════════════════════════════════════════════════════

@Composable
private fun PagesTab(pages: List<DocumentPage>) {
    if (pages.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pages", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
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
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Text(page.ocrText!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    FilledTonalIconButton(onClick = { sharePage(context, page) }) {
                        Icon(PamIcons.Send, contentDescription = "Share", modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(onClick = { openPage(context, page) }) {
                        Icon(PamIcons.Upload, contentDescription = "Open", modifier = Modifier.size(18.dp))
                    }
                    FilledTonalIconButton(onClick = { /* TODO: Re-scan */ }) {
                        Icon(PamIcons.Camera, contentDescription = "Re-scan", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { index ->
                val selected = pagerState.currentPage == index
                Box(modifier = Modifier.padding(2.dp).size(if (selected) 10.dp else 6.dp).clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

private fun sharePage(context: Context, page: DocumentPage) {
    try {
        val uri = Uri.parse(page.imagePath)
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share page"))
    } catch (_: Exception) {}
}

private fun openPage(context: Context, page: DocumentPage) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(page.imagePath), "image/jpeg"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    } catch (_: Exception) {}
}

// ═══════════════════════════════════════════════════════════
// Extracted Tab — Template-based with add/edit/delete
// ═══════════════════════════════════════════════════════════

@Composable
private fun ExtractedTemplateTab(
    data: List<ExtractedData>,
    language: String?,
    documentId: String,
    onConfirm: (String) -> Unit,
    onAdd: (String, String, ExtractedFieldType) -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onReprocess: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<ExtractedData?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (data.isEmpty()) {
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
                Button(onClick = { showAddDialog = true }) {
                    Icon(PamIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Manually")
                }
            }
        } else {
            val senderFields = data.filter { it.fieldName.startsWith("Sender") }
            val receiverFields = data.filter { it.fieldName.startsWith("Receiver") }
            val metadataFields = data.filter { it.fieldType in listOf(ExtractedFieldType.DATE, ExtractedFieldType.SUBJECT, ExtractedFieldType.REFERENCE_NUMBER, ExtractedFieldType.DEADLINE) }
            val financialFields = data.filter { it.fieldType == ExtractedFieldType.IBAN || it.fieldName == "Amount" }
            val contactFields = data.filter { it.fieldType in listOf(ExtractedFieldType.EMAIL, ExtractedFieldType.PHONE) && !it.fieldName.startsWith("Sender") && !it.fieldName.startsWith("Receiver") }
            val tagFields = data.filter { it.fieldType == ExtractedFieldType.TAG_SUGGESTION }
            val contentFields = data.filter { it.fieldType == ExtractedFieldType.TEXT }

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

                if (senderFields.isNotEmpty()) {
                    item { SectionHeader("📤 Sender") }
                    items(senderFields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }
                if (receiverFields.isNotEmpty()) {
                    item { SectionHeader("📥 Receiver") }
                    items(receiverFields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }
                if (metadataFields.isNotEmpty()) {
                    item { SectionHeader("📋 Document Info") }
                    items(metadataFields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }
                if (financialFields.isNotEmpty()) {
                    item { SectionHeader("💰 Financial") }
                    items(financialFields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }
                if (contactFields.isNotEmpty()) {
                    item { SectionHeader("📞 Contact") }
                    items(contactFields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }
                if (tagFields.isNotEmpty()) {
                    item { SectionHeader("🏷️ Tags") }
                    items(tagFields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }
                if (contentFields.isNotEmpty()) {
                    item { SectionHeader("📝 Content") }
                    items(contentFields, key = { it.id }) { field -> FieldCard(field, onConfirm, { editingField = it }, onDelete) }
                }

                item { Spacer(modifier = Modifier.height(72.dp)) } // FAB spacing
            }
        }

        // FAB to add field
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Icon(PamIcons.Add, contentDescription = "Add field")
        }
    }

    // Add dialog
    if (showAddDialog) {
        AddFieldDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, value, type ->
                onAdd(name, value, type)
                showAddDialog = false
            },
        )
    }

    // Edit dialog
    editingField?.let { field ->
        EditFieldDialog(
            field = field,
            onDismiss = { editingField = null },
            onSave = { name, value ->
                onUpdate(field.id, name, value)
                editingField = null
            },
        )
    }
}

// ─── Add Field Dialog ──────────────────────────────────────

@Composable
private fun AddFieldDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, ExtractedFieldType) -> Unit,
) {
    var fieldName by remember { mutableStateOf("") }
    var fieldValue by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ExtractedFieldType.TEXT) }
    var showTypeMenu by remember { mutableStateOf(false) }

    val presetTemplates = listOf(
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
        title = { Text("Add Extracted Field") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Quick templates:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                // Template chips (scrollable row)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    presetTemplates.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { (name, type) ->
                                OutlinedButton(
                                    onClick = { fieldName = name; selectedType = type },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = fieldName,
                    onValueChange = { fieldName = it },
                    label = { Text("Field Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    label = { Text("Value") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(fieldName.trim(), fieldValue.trim(), selectedType) },
                enabled = fieldName.isNotBlank() && fieldValue.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ─── Edit Field Dialog ─────────────────────────────────────

@Composable
private fun EditFieldDialog(
    field: ExtractedData,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var editName by remember { mutableStateOf(field.fieldName) }
    var editValue by remember { mutableStateOf(field.fieldValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Field") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Field Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { editValue = it },
                    label = { Text("Value") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                )
                Text(
                    text = "Type: ${field.fieldType.name} · ${(field.confidence * 100).toInt()}% confidence",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(editName.trim(), editValue.trim()) },
                enabled = editName.isNotBlank() && editValue.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ─── Shared Components ─────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun FieldCard(
    field: ExtractedData,
    onConfirm: (String) -> Unit,
    onEdit: (ExtractedData) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (field.isConfirmed) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp).clickable { onEdit(field) }, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(field.fieldName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(4.dp))
                Text(field.fieldValue, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(2.dp))
                Text("${(field.confidence * 100).toInt()}% confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(PamIcons.More, contentDescription = "More", modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { showMenu = false; onEdit(field) },
                        leadingIcon = { Icon(PamIcons.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) })
                    if (!field.isConfirmed) {
                        DropdownMenuItem(text = { Text("Confirm") }, onClick = { showMenu = false; onConfirm(field.id) },
                            leadingIcon = { Icon(PamIcons.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) })
                    }
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete(field.id) },
                        leadingIcon = { Icon(PamIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) })
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Timeline Tab
// ═══════════════════════════════════════════════════════════

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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(event.title, style = MaterialTheme.typography.titleSmall)
                    event.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(event.createdAt.toRelativeTime(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}
