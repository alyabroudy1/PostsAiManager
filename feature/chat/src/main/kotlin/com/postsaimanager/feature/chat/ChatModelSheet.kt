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
import androidx.annotation.StringRes
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Locale
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
import com.postsaimanager.core.domain.usecase.ChatTurn
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
fun ModelHeaderChip(
    state: ModelSheetUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimingConversation: Boolean = false,
    isWaitingForDocument: Boolean = false,
) {
    val activeModel = state.installedModels.firstOrNull { it.id == state.activeModelId }
    val context = LocalContext.current
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
            LoadStateDot(
                state.loadState,
                isPrimingConversation = isPrimingConversation,
                isWaitingForDocument = isWaitingForDocument,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    // The installed-model list can lag behind while the engine is busy reading a
                    // document; "No model" would then be a false claim.
                    text = activeModel?.name
                        ?: stringResource(if (isWaitingForDocument) R.string.chat_header_assistant else R.string.chat_header_no_model),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = modelHeaderSubtitle(state.loadState, isPrimingConversation, isWaitingForDocument, text = { context.getString(it.res) }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.chat_model_settings),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadStateDot(
    loadState: ModelLoadState,
    isPrimingConversation: Boolean = false,
    isWaitingForDocument: Boolean = false,
) {
    when {
        showsHeaderSpinner(loadState, isPrimingConversation, isWaitingForDocument) ->
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )

        loadState is ModelLoadState.Ready -> Surface(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.primary,
        ) {}

        loadState is ModelLoadState.Failed -> Surface(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.error,
        ) {}

        else -> Surface( // ModelLoadState.Idle
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50)),
            color = MaterialTheme.colorScheme.outline,
        ) {}
    }
}

/**
 * The chat header's subtitle. Pre-warm priming ([ChatViewModel.preWarmModel]) spans the model
 * load *and* the conversation prefill, so the flag alone is not enough: the load half must say
 * "Loading model…" and only the prefill half — the model is resident, or has not registered
 * a load yet — says "Preparing conversation…". A failed load is never masked by either.
 */
internal fun modelHeaderSubtitle(
    loadState: ModelLoadState,
    isPrimingConversation: Boolean,
    isWaitingForDocument: Boolean = false,
    text: (ChatStatusText) -> String = ChatStatusText::english,
): String = when {
    loadState is ModelLoadState.Failed -> loadStateSubtitle(loadState, text)
    // The engine is busy reading a document: the chat cannot start until that finishes, and
    // "Not loaded" / "Loading model…" would leave the wait unexplained.
    isWaitingForDocument -> text(ChatStatusText.WAITING_FOR_DOCUMENT)
    loadState is ModelLoadState.Loading -> text(ChatStatusText.LOADING_MODEL)
    isPrimingConversation -> text(ChatStatusText.PREPARING_CONVERSATION)
    else -> loadStateSubtitle(loadState, text)
}

/**
 * The chat's own status lines. [english] is what the logic and the tests compare against (the view model keeps the English line as the
 * transient status key); [res] is what the person reads, in the app language.
 */
internal enum class ChatStatusText(val english: String, @StringRes val res: Int) {
    LOADING_MODEL("Loading model…", R.string.chat_status_loading_model),
    PREPARING_CONVERSATION("Preparing conversation…", R.string.chat_status_preparing_conversation),
    WAITING_FOR_DOCUMENT(ChatTurn.PreparingModel.WAITING_FOR_DOCUMENT, R.string.chat_status_waiting_for_document),
    NOT_LOADED("Not loaded", R.string.chat_model_not_loaded),
    LOAD_FAILED("Failed to load", R.string.chat_model_load_failed),
    ;

    companion object {
        /** The localized line for a transient status the view model set, or the status itself when it is not one of ours. */
        fun localized(status: String, text: (ChatStatusText) -> String): String =
            entries.firstOrNull { it.english == status }?.let(text) ?: status
    }
}

/** The header shows a spinner exactly while the subtitle describes work in progress. */
internal fun showsHeaderSpinner(
    loadState: ModelLoadState,
    isPrimingConversation: Boolean,
    isWaitingForDocument: Boolean = false,
): Boolean =
    loadState !is ModelLoadState.Failed &&
        (loadState is ModelLoadState.Loading || isPrimingConversation || isWaitingForDocument)

private fun loadStateSubtitle(loadState: ModelLoadState, text: (ChatStatusText) -> String): String = when (loadState) {
    ModelLoadState.Idle -> text(ChatStatusText.NOT_LOADED)
    is ModelLoadState.Loading -> text(ChatStatusText.LOADING_MODEL)
    is ModelLoadState.Ready ->
        // The backend the engine reported running, not the one the settings asked for (a GPU request can fall back to the CPU).
        "${loadState.shownAccelerator.name} · ${loadState.config.contextTokens} ctx"
    is ModelLoadState.Failed -> text(ChatStatusText.LOAD_FAILED)
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
            SheetSectionHeader(stringResource(R.string.chat_sheet_model))
            state.installedModels.forEach { model ->
                InstalledModelRow(
                    model = model,
                    isActive = model.id == state.activeModelId,
                    onClick = { onSelectModel(model.id) },
                )
            }
            if (state.installedModels.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_sheet_no_models),
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
                    text = stringResource(R.string.chat_sheet_manage_models),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SheetSectionHeader(stringResource(R.string.chat_sheet_accelerator))
            if (acceleratorSpec != null) {
                AcceleratorSegmentedButton(
                    spec = acceleratorSpec,
                    value = acceleratorSpec.effectiveValue(state.overrides),
                    onValueChange = { onSetInferenceSetting("accelerator", it) },
                    onTryGpuAgain = onTryGpuAgain,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SheetSectionHeader(stringResource(R.string.chat_sheet_inference_settings))
            if (restOfSchema.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_sheet_install_to_configure),
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
                    Text(stringResource(R.string.chat_sheet_reset_defaults))
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
                contentDescription = stringResource(R.string.chat_sheet_active_model),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun modelSubtitle(model: InstalledModelSummary): String {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val gigabytes = String.format(locale, "%.1f", model.sizeBytes / 1_073_741_824.0)
    val size = stringResource(R.string.chat_model_size_gb, gigabytes)
    return model.quantization?.let { stringResource(R.string.chat_model_size_quantization, size, it) } ?: size
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
                Text(stringResource(R.string.chat_sheet_try_gpu_again))
            }
        }
        ReloadHint(spec.reloadScope)
    }
}
