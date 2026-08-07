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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.postsaimanager.core.ai.catalog.CatalogEntry
import com.postsaimanager.core.ai.catalog.download.ModelDownloadStatus
import com.postsaimanager.core.ai.embed.install.InstallStatus
import com.postsaimanager.core.designsystem.component.PamLoadingState
import com.postsaimanager.core.designsystem.component.PamTopAppBar
import com.postsaimanager.core.model.DeviceCapability
import com.postsaimanager.core.model.ModelFit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onNavigateBack: () -> Unit,
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

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            PamTopAppBar(
                title = "AI Models",
                onNavigateBack = onNavigateBack,
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (val state = uiState) {
            is ModelsUiState.Loading -> PamLoadingState(modifier = Modifier.padding(padding))

            is ModelsUiState.Error -> Text(
                text = state.message,
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

                item { SectionHeader("Document search") }
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
                    item { SectionHeader("Installed") }
                    items(state.installed, key = { it.descriptor.id }) { entry ->
                        InstalledCard(
                            entry = entry,
                            onSetActive = { viewModel.setActive(it) },
                            onUninstall = { viewModel.uninstall(it) },
                        )
                    }
                }

                item { SectionHeader("Available") }
                items(state.available, key = { it.descriptor.id }) { entry ->
                    AvailableCard(entry = entry, viewModel = viewModel)
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
            Text("This device", style = MaterialTheme.typography.titleSmall)
            Text(
                "${capability.availableRamBytes.gb()} of ${capability.totalRamBytes.gb()} " +
                    "memory available right now",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${capability.freeStorageBytes.gb()} storage free · ${capability.tier.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (capability.isLowMemory) {
                Text(
                    "The device is low on memory — close some apps before loading a model.",
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
            Text("Offline catalog", style = MaterialTheme.typography.titleSmall)
            Text(
                "Showing the models bundled with the app. Downloads require a signed " +
                    "catalog, which this build does not yet have — so nothing here can be " +
                    "installed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InstalledCard(
    entry: CatalogEntry,
    onSetActive: (String) -> Unit,
    onUninstall: (String) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(entry.descriptor.name, style = MaterialTheme.typography.titleMedium)
                if (entry.isActive) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Active") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
            Text(
                "${entry.descriptor.parameterCount} · ${entry.descriptor.quantization} · " +
                    entry.descriptor.sizeBytes.gb(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!entry.isActive) {
                    Button(onClick = { entry.installed?.let { onSetActive(it.id) } }) {
                        Text("Use this model")
                    }
                }
                TextButton(onClick = { entry.installed?.let { onUninstall(it.id) } }) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun AvailableCard(entry: CatalogEntry, viewModel: ModelsViewModel) {
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

            fitMessage?.let {
                Text(
                    text = it.text,
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
                            "${(fraction * 100).toInt()}% · ${s.bytesDownloaded.gb()} downloaded",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = { viewModel.cancel(entry.descriptor.id) }) {
                        Text("Cancel")
                    }
                }

                is ModelDownloadStatus.Queued -> Text(
                    "Waiting for Wi-Fi…",
                    style = MaterialTheme.typography.bodySmall,
                )

                is ModelDownloadStatus.Failed -> Text(
                    s.message ?: "Download failed. It will retry automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                else -> Button(
                    onClick = { viewModel.install(entry.descriptor) },
                    enabled = entry.fit.canDownload,
                ) {
                    Text("Install")
                }
            }
        }
    }
}

private fun Long.gb(): String = when {
    this >= 1_073_741_824L -> "%.1f GB".format(this / 1_073_741_824.0)
    else -> "%.0f MB".format(this / 1_048_576.0)
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
                Text("Search by meaning", style = MaterialTheme.typography.titleMedium)
                if (status is InstallStatus.Installed) {
                    AssistChip(
                        onClick = {},
                        label = { Text("On") },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }

            Text(
                "Find a letter by what it was about, not the words it used. Ask " +
                    "\"when is my deadline?\" and get the paragraph that answers it — even " +
                    "in a different language.",
                style = MaterialTheme.typography.bodyMedium,
            )

            when (status) {
                is InstallStatus.NotStarted -> {
                    Text(
                        "${downloadBytes.gb()} download · runs entirely on your device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Without it, search still finds documents by word.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onInstall(false) }) { Text("Download on Wi-Fi") }
                        // The default waits for unmetered, because a quarter-gigabyte on
                        // mobile data is not a cost to incur on the user's behalf.
                        TextButton(onClick = { onInstall(true) }) { Text("Use mobile data") }
                    }
                }

                is InstallStatus.Waiting -> {
                    Text(
                        "Waiting for Wi-Fi. The download will start on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
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
                            "${(status.fraction * 100).toInt()}% · " +
                                "${status.bytesDownloaded.gb()} of ${status.totalBytes.gb()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        "You can leave this screen — it continues in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }

                is InstallStatus.Installed -> {
                    Text(
                        "Ready. New documents are indexed as you scan them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onRemove) {
                        Text("Remove (${downloadBytes.gb()})")
                    }
                }

                is InstallStatus.Failed -> {
                    Text(
                        "The download did not finish. Retrying continues from where it " +
                            "stopped rather than starting over.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = { onInstall(false) }) { Text("Try again") }
                }
            }
        }
    }
}
