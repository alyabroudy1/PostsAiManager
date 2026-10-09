package com.postsaimanager.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.ai.catalog.CatalogEntry
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.InstallStatus
import com.postsaimanager.core.designsystem.component.ChatModelFitBadge
import com.postsaimanager.core.designsystem.component.deviceTierLabel
import com.postsaimanager.core.designsystem.component.ModelRuntimeNote
import com.postsaimanager.core.designsystem.component.ModelSpeedHint
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.model.ChatModelFit
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.ModelFit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onNavigateBack: () -> Unit,
    /** False when form filling is switched off: the "Used for form filling" note is hidden. */
    showFormFillingNote: Boolean = false,
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // GGUF has no registered MIME type, so the picker cannot filter by it — the header
    // check in ModelImporter is what actually rejects a wrong file, in milliseconds.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::import) }

    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            val text = when (it) {
                is ModelsMessage.Res -> context.getString(it.id, *it.args.toTypedArray())
                is ModelsMessage.Raw -> it.text
            }
            snackbarHostState.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = stringResource(R.string.models_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text(stringResource(R.string.models_import))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val state = uiState) {
            is ModelsUiState.Loading -> PamLoadingState(modifier = Modifier.padding(padding))

            is ModelsUiState.Error -> Text(
                text = state.message ?: stringResource(R.string.models_error_catalog),
                modifier = Modifier.padding(padding).padding(16.dp),
            )

            is ModelsUiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { DeviceCard(state.capability) }

                if (state.usingBundledCatalog) {
                    item { OfflineCatalogNotice() }
                }

                item { SectionHeader(stringResource(R.string.models_section_search)) }
                item {
                    val embeddingStatus by viewModel.embeddingStatus
                        .collectAsStateWithLifecycle()
                    EmbeddingModelCard(
                        status = embeddingStatus,
                        downloadBytes = viewModel.embeddingDownloadBytes,
                        onInstall = { viewModel.installEmbeddingModel(allowMetered = it) },
                        onCancel = { viewModel.cancelEmbeddingInstall() },
                        onRemove = { viewModel.uninstallEmbeddingModel() },
                    )
                }

                if (state.installed.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.models_section_installed)) }
                    items(state.installed, key = { it.descriptor.id }) { entry ->
                        InstalledCard(
                            entry = entry,
                            chatFit = state.fits[entry.descriptor.id],
                            onSetActive = { viewModel.setActive(it) },
                            onSetExtraction = { viewModel.setExtractionModel(it) },
                            onUninstall = { viewModel.uninstall(it) },
                            showFormFillingNote = showFormFillingNote,
                        )
                    }
                }

                item { SectionHeader(stringResource(R.string.models_section_available)) }
                items(state.available, key = { it.descriptor.id }) { entry ->
                    AvailableCard(entry = entry, chatFit = state.fits[entry.descriptor.id], viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Shows **available** memory alongside total.
 *
 * Total alone is misleading: the project's test device reports 11.3 GB total but only
 * 2.5 GB free, which is what actually decides whether a model loads.
 */
@Composable
private fun DeviceCard(capability: DeviceCapability) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.models_device_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.models_device_memory,
                    capability.availableRamBytes.gb(),
                    capability.totalRamBytes.gb(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.models_device_storage, capability.freeStorageBytes.gb(), deviceTierLabel(capability.tier)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (capability.isLowMemory) {
                Text(
                    stringResource(R.string.models_device_low_memory),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun OfflineCatalogNotice() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.models_offline_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.models_offline_text),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InstalledCard(
    entry: CatalogEntry,
    chatFit: ChatModelFit?,
    onSetActive: (String) -> Unit,
    onSetExtraction: (String) -> Unit,
    onUninstall: (String) -> Unit,
    showFormFillingNote: Boolean,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.descriptor.name, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Two jobs, two chips. A model can hold one, both or neither, and
                    // "Active" alone could not say which.
                    if (entry.isActive) {
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.models_chip_chat)) })
                    }
                    if (entry.isExtractionModel) {
                        AssistChip(onClick = {}, label = { Text(stringResource(R.string.models_chip_reads_documents)) })
                    }
                }
            }
            Text(
                "${entry.descriptor.parameterCount} · ${entry.descriptor.quantization} · " +
                    entry.descriptor.sizeBytes.gb(),

                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelRuntimeNote(entry.descriptor.runtime)
            ModelSpeedHint(entry.descriptor.speedHint)
            chatFit?.let { ChatModelFitBadge(it) }
            if (entry.isFormModel && showFormFillingNote) {
                // The form agent prefers this model over the chat model while it is installed (ModelProfiles.FORM_AGENT_MODELS).
                Text(
                    stringResource(R.string.models_note_form_filling),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.descriptor.recommendedForExtraction && !entry.isExtractionModel) {
                // Said plainly, because the difference is not obvious from a model name and
                // the gain is concrete: on a real letter this is the difference between
                // capturing a deadline and missing it.
                Text(
                    stringResource(R.string.models_note_better_reading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!entry.isActive && entry.descriptor.supportsChat) {
                    Button(onClick = { entry.installed?.let { onSetActive(it.id) } }) {
                        Text(stringResource(R.string.models_action_use_chat))
                    }
                }
                // Reading letters needs llama.cpp (token scoring, prefix reuse): a model of another runtime only chats.
                if (!entry.isExtractionModel && entry.descriptor.runtime.canReadDocuments) {
                    OutlinedButton(onClick = { entry.installed?.let { onSetExtraction(it.id) } }) {
                        Text(stringResource(R.string.models_action_use_reading))
                    }
                }
                TextButton(onClick = { entry.installed?.let { onUninstall(it.id) } }) {
                    Text(stringResource(R.string.models_action_remove))
                }
            }
        }
    }
}

@Composable
private fun AvailableCard(entry: CatalogEntry, chatFit: ChatModelFit?, viewModel: ModelsViewModel) {
    val status by viewModel.downloadStatus(entry.descriptor.id)
        .collectAsStateWithLifecycle(initialValue = ModelDownloadStatus.NotStarted)

    LaunchedEffect(status) {
        viewModel.onDownloadFinished(entry.descriptor, status)
    }

    val fitMessage = ModelsViewModel.fitMessage(entry.fit)

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.descriptor.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "${entry.descriptor.parameterCount} · ${entry.descriptor.quantization} · " +
                    "${entry.descriptor.sizeBytes.gb()} · ${entry.descriptor.license}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entry.descriptor.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            ModelRuntimeNote(entry.descriptor.runtime)
            ModelSpeedHint(entry.descriptor.speedHint)
            chatFit?.let { ChatModelFitBadge(it) }

            fitMessage?.let {
                Text(
                    text = stringResource(it.textRes, *it.bytes.map { bytes -> bytes.gb() }.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.isBlocking) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            when (val s = status) {
                is ModelDownloadStatus.Running -> {
                    Spacer(Modifier.height(4.dp))
                    val fraction = s.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.models_download_progress, (fraction * 100).toInt(), s.bytesDownloaded.gb()),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = { viewModel.cancel(entry.descriptor.id) }) {
                        Text(stringResource(R.string.models_action_cancel))
                    }
                }

                is ModelDownloadStatus.Queued -> Text(
                    stringResource(R.string.models_waiting_wifi_short),
                    style = MaterialTheme.typography.bodySmall,
                )

                is ModelDownloadStatus.Failed -> Text(
                    s.message ?: stringResource(R.string.models_download_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Button(
                    onClick = { viewModel.install(entry.descriptor) },
                    enabled = entry.fit.canDownload,
                ) {
                    Text(stringResource(R.string.models_action_install))
                }
            }
        }
    }
}

/** A byte count as "1.5 GB" or "800 MB": the number in the app language's digits, the unit from resources. */
@Composable
private fun Long.gb(): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (this >= 1_073_741_824L) {
        stringResource(R.string.models_size_gb, String.format(locale, "%.1f", this / 1_073_741_824.0))
    } else {
        stringResource(R.string.models_size_mb, String.format(locale, "%.0f", this / 1_048_576.0))
    }
}

/**
 * The embedding model — one fixed asset, not a choice.
 *
 * Presented apart from the chat catalog because the decision is different in kind. The
 * chat cards ask "which assistant?"; this one asks "do you want search to understand
 * meaning?", and the answer is yes or no. So it is described by what it does rather than by
 * what it is: nobody installs `distiluse-base-multilingual-cased-v2`, they install the
 * ability to find a letter without remembering its wording.
 *
 * Declining is a legitimate choice with a real consequence, so the card states the
 * consequence — search still works, by word — rather than pressing.
 */
@Composable
private fun EmbeddingModelCard(
    status: InstallStatus,
    downloadBytes: Long,
    onInstall: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.models_embed_title), style = MaterialTheme.typography.titleMedium)
                if (status is InstallStatus.Installed) {
                    AssistChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.models_embed_on)) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }

            Text(
                stringResource(R.string.models_embed_description),
                style = MaterialTheme.typography.bodyMedium,
            )

            when (status) {
                is InstallStatus.NotStarted -> {
                    Text(
                        stringResource(R.string.models_embed_size, downloadBytes.gb()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.models_embed_without),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onInstall(false) }) { Text(stringResource(R.string.models_embed_download_wifi)) }
                        // The default waits for unmetered, because a quarter-gigabyte on
                        // mobile data is not a cost to incur on the user's behalf.
                        TextButton(onClick = { onInstall(true) }) { Text(stringResource(R.string.models_embed_use_mobile)) }
                    }
                }

                is InstallStatus.Waiting -> {
                    Text(
                        stringResource(R.string.models_embed_waiting_wifi),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.models_action_cancel)) }
                }

                is InstallStatus.Running -> {
                    // Indeterminate until the first progress arrives; a bar pinned at 0%
                    // looks stalled.
                    if (status.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { status.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(
                                R.string.models_embed_progress,
                                (status.fraction * 100).toInt(),
                                status.bytesDownloaded.gb(),
                                status.totalBytes.gb(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        stringResource(R.string.models_embed_background),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.models_action_cancel)) }
                }

                is InstallStatus.Installed -> {
                    Text(
                        stringResource(R.string.models_embed_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRemove) {
                        Text(stringResource(R.string.models_embed_remove, downloadBytes.gb()))
                    }
                }

                is InstallStatus.Failed -> {
                    Text(
                        stringResource(R.string.models_embed_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { onInstall(false) }) { Text(stringResource(R.string.models_embed_try_again)) }
                }
            }
        }
    }
}
