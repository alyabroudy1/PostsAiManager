package com.postsaimanager.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.ConfigSpecItem
import com.postsaimanager.core.designsystem.component.ReloadHint
import com.postsaimanager.core.model.ConfigSpec
import com.postsaimanager.core.model.InstalledModelSummary
import com.postsaimanager.core.model.ModelLoadState
import com.postsaimanager.core.model.effectiveValue
import com.postsaimanager.core.model.isGpuBlockedByDriver

/**
 * The chat header chip — shows which model is loaded and its state, and opens
 * [ModelConfigBottomSheet] on tap. Modelled on Google AI Edge Gallery's chat header: the
 * model and its runtime state are always one tap away, not buried in Settings.
 */
@Composable
fun ModelHeaderChip(state: ModelSheetUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val activeModel = state.installedModels.firstOrNull { it.id == state.activeModelId }
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoadStateDot(state.loadState)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = activeModel?.name ?: "No model",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = loadStateSubtitle(state.loadState),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Model settings",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadStateDot(loadState: ModelLoadState) {
    when (loadState) {
        is ModelLoadState.Loading -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )

        is ModelLoadState.Ready -> Surface(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.primary,
        ) {}

        is ModelLoadState.Failed -> Surface(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.error,
        ) {}

        ModelLoadState.Idle -> Surface(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.outline,
        ) {}
    }
}

private fun loadStateSubtitle(loadState: ModelLoadState): String = when (loadState) {
    ModelLoadState.Idle -> "Not loaded"
    is ModelLoadState.Loading -> "Loading…"
    is ModelLoadState.Ready ->
        "${loadState.config.accelerator.name} · ${loadState.config.contextTokens} ctx"
    is ModelLoadState.Failed -> "Failed to load"
}

/**
 * Model / accelerator / inference-settings sheet — a Google AI Edge Gallery-style panel
 * reachable from the chat header rather than only from Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigBottomSheet(
    state: ModelSheetUiState,
    onSelectModel: (String) -> Unit,
    onManageModelsClick: () -> Unit,
    onSetInferenceSetting: (String, Any) -> Unit,
    onResetInference: () -> Unit,
    onTryGpuAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val acceleratorSpec = state.schema.filterIsInstance<ConfigSpec.Choice>()
        .firstOrNull { it.key == "accelerator" }
    val restOfSchema = state.schema.filterNot { it.key == "accelerator" }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            SheetSectionHeader("Model")
            state.installedModels.forEach { model ->
                InstalledModelRow(
                    model = model,
                    isActive = model.id == state.activeModelId,
                    onClick = { onSelectModel(model.id) },
                )
            }
            if (state.installedModels.isEmpty()) {
                Text(
                    text = "No models installed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManageModelsClick)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Manage models",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SheetSectionHeader("Accelerator")
            if (acceleratorSpec != null) {
                AcceleratorSegmentedButton(
                    spec = acceleratorSpec,
                    value = acceleratorSpec.effectiveValue(state.overrides),
                    onValueChange = { onSetInferenceSetting("accelerator", it) },
                    onTryGpuAgain = onTryGpuAgain,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SheetSectionHeader("Inference settings")
            if (restOfSchema.isEmpty()) {
                Text(
                    text = "Install a model to configure it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                restOfSchema.forEach { spec ->
                    ConfigSpecItem(
                        spec = spec,
                        overrides = state.overrides,
                        onValueChange = { value -> onSetInferenceSetting(spec.key, value) },
                    )
                }
                TextButton(
                    onClick = onResetInference,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text("Reset to defaults")
                }
            }
        }
    }
}

@Composable
private fun SheetSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun InstalledModelRow(
    model: InstalledModelSummary,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = model.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = modelSubtitle(model),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isActive) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Active model",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun modelSubtitle(model: InstalledModelSummary): String {
    val size = "%.1f GB".format(model.sizeBytes / 1_073_741_824.0)
    return if (model.quantization != null) "$size · ${model.quantization}" else size
}

/** Always visible — see [ConfigSpec.Choice.disabledOptions] on the "accelerator" spec. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AcceleratorSegmentedButton(
    spec: ConfigSpec.Choice,
    value: String,
    onValueChange: (String) -> Unit,
    onTryGpuAgain: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            spec.options.forEachIndexed { index, option ->
                val disabled = option in spec.disabledOptions
                SegmentedButton(
                    selected = option == value,
                    onClick = { onValueChange(option) },
                    enabled = !disabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = spec.options.size),
                    label = { Text(option) },
                )
            }
        }
        val disabledSpecReason = spec.disabledReason.takeIf { spec.disabledOptions.isNotEmpty() }
        if (disabledSpecReason != null) {
            Text(
                text = disabledSpecReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Only offered for a persisted crash block (see ConfigSpec.isGpuBlockedByDriver) —
        // never for "Not available in this build", where retrying cannot help.
        if (spec.isGpuBlockedByDriver) {
            TextButton(onClick = onTryGpuAgain, modifier = Modifier.padding(top = 4.dp)) {
                Text("Try GPU again")
            }
        }
        ReloadHint(spec.reloadScope)
    }
}
