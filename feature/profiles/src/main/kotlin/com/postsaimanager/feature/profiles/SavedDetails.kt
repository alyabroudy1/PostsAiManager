package com.postsaimanager.feature.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.FormKeyLabels
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.FactSource
import com.postsaimanager.core.model.FormDataKey
import com.postsaimanager.core.model.ProfileFact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the "Saved details" section can do; the screen binds these to the ViewModel. */
class SavedDetailActions(
    val save: (keyId: String, value: String) -> Unit,
    val delete: (ProfileFact) -> Unit,
)

/** The mask shown instead of a sensitive value. */
internal const val MASK = "••••••••"

/** The label of a registry key; a key without a label (a registry/UI mismatch) shows its id rather than nothing. */
@Composable
internal fun keyLabel(keyId: String): String = FormKeyLabels.of(keyId)?.let { stringResource(it) } ?: keyId

/**
 * "Saved details": what the app remembers about this person, each with where it came from. Sensitive values are masked
 * until tapped. Adding picks a kind of detail from the registry first, then asks for the value.
 */
@Composable
fun SavedDetailsSection(
    facts: List<ProfileFact>,
    availableKeys: List<FormDataKey>,
    canAdd: Boolean,
    actions: SavedDetailActions,
    modifier: Modifier = Modifier,
) {
    var dialogFor by remember { mutableStateOf<DetailDialogTarget?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.saved_details_title), style = MaterialTheme.typography.titleMedium)
        when {
            !canAdd -> Text(
                stringResource(R.string.saved_details_save_first),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            facts.isEmpty() -> Text(
                stringResource(R.string.saved_details_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        facts.forEach { fact ->
            SavedDetailRow(
                fact = fact,
                onEdit = { dialogFor = DetailDialogTarget.Edit(fact) },
                onDelete = { actions.delete(fact) },
            )
        }
        if (canAdd) {
            OutlinedButton(
                onClick = { dialogFor = DetailDialogTarget.Add },
                enabled = availableKeys.isNotEmpty(),
                modifier = Modifier.testTag("add_detail"),
            ) {
                Icon(PamIcons.Add, contentDescription = null)
                Text(stringResource(R.string.saved_details_add), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    when (val target = dialogFor) {
        null -> Unit
        DetailDialogTarget.Add -> DetailDialog(
            fixedKey = null,
            availableKeys = availableKeys,
            initialValue = "",
            onSave = { key, value -> actions.save(key, value); dialogFor = null },
            onDismiss = { dialogFor = null },
        )
        is DetailDialogTarget.Edit -> DetailDialog(
            fixedKey = target.fact.key,
            availableKeys = emptyList(),
            initialValue = target.fact.value,
            onSave = { key, value -> actions.save(key, value); dialogFor = null },
            onDismiss = { dialogFor = null },
        )
    }
}

private sealed interface DetailDialogTarget {
    data object Add : DetailDialogTarget
    data class Edit(val fact: ProfileFact) : DetailDialogTarget
}

@Composable
private fun SavedDetailRow(
    fact: ProfileFact,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var revealed by remember(fact.id, fact.updatedAt) { mutableStateOf(false) }
    val masked = fact.sensitive && !revealed
    val hiddenText = stringResource(R.string.saved_details_hidden)
    Card(
        modifier = Modifier.fillMaxWidth().testTag("saved_detail_${fact.key}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(keyLabel(fact.key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = if (masked) MASK else fact.value,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .then(if (fact.sensitive) Modifier.clickable { revealed = !revealed } else Modifier)
                        .semantics { if (masked) contentDescription = hiddenText },
                )
                Text(sourceLine(fact), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(PamIcons.Edit, contentDescription = stringResource(R.string.saved_details_edit)) }
            IconButton(onClick = onDelete) { Icon(PamIcons.Delete, contentDescription = stringResource(R.string.saved_details_delete)) }
        }
    }
}

@Composable
private fun sourceLine(fact: ProfileFact): String {
    val date = remember(fact.updatedAt) {
        Instant.ofEpochMilli(fact.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    return when (fact.source) {
        FactSource.USER -> stringResource(R.string.saved_details_source_user, date)
        FactSource.FORM_ANSWER -> stringResource(R.string.saved_details_source_form, date)
        FactSource.CONFIRMED_DOC -> stringResource(R.string.saved_details_source_document, date)
    }
}

/** Adds a detail (pick a kind, then the value) or edits one (the kind is fixed). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailDialog(
    fixedKey: String?,
    availableKeys: List<FormDataKey>,
    initialValue: String,
    onSave: (keyId: String, value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(fixedKey) }
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (fixedKey == null) R.string.saved_details_dialog_add else R.string.saved_details_dialog_edit))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (fixedKey != null) {
                    Text(keyLabel(fixedKey), style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(stringResource(R.string.saved_details_dialog_key), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableKeys.forEach { option ->
                            FilterChip(
                                selected = key == option.id,
                                onClick = { key = option.id },
                                label = { Text(keyLabel(option.id)) },
                                modifier = Modifier.testTag("key_${option.id}"),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.saved_details_dialog_value)) },
                    enabled = key != null,
                    modifier = Modifier.fillMaxWidth().testTag("detail_value"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { key?.let { onSave(it, value) } },
                enabled = key != null && value.isNotBlank(),
                modifier = Modifier.testTag("detail_save"),
            ) { Text(stringResource(R.string.saved_details_dialog_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.saved_details_dialog_cancel)) } },
    )
}
