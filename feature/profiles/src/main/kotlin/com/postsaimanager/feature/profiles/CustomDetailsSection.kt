package com.postsaimanager.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.CustomDetail
import com.postsaimanager.core.model.CustomDetails

/**
 * The user's own named details (a label and a value each): listed in their order, each with edit, remove and move up or down, and an
 * "Add own detail" button. [onChange] gets the whole new list; the screen keeps it in the profile draft (saved with Save) or in the
 * contact dialog's draft. Nothing here knows which field names exist: the user names them.
 *
 * @param tagPrefix test tag prefix; rows are `<prefix>_row_<index>`
 */
@Composable
internal fun CustomDetailsSection(
    details: List<CustomDetail>,
    onChange: (List<CustomDetail>) -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    tagPrefix: String = "custom_detail",
) {
    var dialogFor by remember { mutableStateOf<CustomDetailTarget?>(null) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTitle) Text(stringResource(R.string.custom_details_title), style = MaterialTheme.typography.titleMedium)
        if (details.isEmpty()) {
            Text(
                stringResource(R.string.custom_details_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("${tagPrefix}_empty"),
            )
        }
        details.forEachIndexed { index, detail ->
            CustomDetailRow(
                detail = detail,
                canMoveUp = index > 0,
                canMoveDown = index < details.lastIndex,
                tag = "${tagPrefix}_row_$index",
                onEdit = { dialogFor = CustomDetailTarget.Edit(index, detail) },
                onRemove = { onChange(CustomDetails.remove(details, index)) },
                onUp = { onChange(CustomDetails.move(details, index, index - 1)) },
                onDown = { onChange(CustomDetails.move(details, index, index + 1)) },
            )
        }
        OutlinedButton(onClick = { dialogFor = CustomDetailTarget.Add }, modifier = Modifier.testTag("${tagPrefix}_add")) {
            Icon(PamIcons.Add, contentDescription = null)
            Text(stringResource(R.string.custom_detail_add), modifier = Modifier.padding(start = 8.dp))
        }
    }

    when (val target = dialogFor) {
        null -> Unit
        CustomDetailTarget.Add -> CustomDetailDialog(
            initial = CustomDetail("", ""),
            title = R.string.custom_detail_dialog_add,
            tagPrefix = tagPrefix,
            onSave = { onChange(CustomDetails.add(details, it)); dialogFor = null },
            onDismiss = { dialogFor = null },
        )
        is CustomDetailTarget.Edit -> CustomDetailDialog(
            initial = target.detail,
            title = R.string.custom_detail_dialog_edit,
            tagPrefix = tagPrefix,
            onSave = { onChange(CustomDetails.replace(details, target.index, it)); dialogFor = null },
            onDismiss = { dialogFor = null },
        )
    }
}

private sealed interface CustomDetailTarget {
    data object Add : CustomDetailTarget
    data class Edit(val index: Int, val detail: CustomDetail) : CustomDetailTarget
}

@Composable
private fun CustomDetailRow(
    detail: CustomDetail,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    tag: String,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(detail.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail.value, style = MaterialTheme.typography.bodyLarge)
        }
        IconButton(onClick = onUp, enabled = canMoveUp, modifier = Modifier.testTag("${tag}_up")) {
            Icon(PamIcons.ExpandLess, contentDescription = stringResource(R.string.custom_detail_move_up, detail.label))
        }
        IconButton(onClick = onDown, enabled = canMoveDown, modifier = Modifier.testTag("${tag}_down")) {
            Icon(PamIcons.ExpandMore, contentDescription = stringResource(R.string.custom_detail_move_down, detail.label))
        }
        IconButton(onClick = onEdit, modifier = Modifier.testTag("${tag}_edit")) {
            Icon(PamIcons.Edit, contentDescription = stringResource(R.string.custom_detail_edit, detail.label))
        }
        IconButton(onClick = onRemove, modifier = Modifier.testTag("${tag}_remove")) {
            Icon(PamIcons.Delete, contentDescription = stringResource(R.string.custom_detail_remove, detail.label))
        }
    }
}

/** Adds or edits one own detail: a name and a value, both needed. */
@Composable
private fun CustomDetailDialog(
    initial: CustomDetail,
    title: Int,
    tagPrefix: String,
    onSave: (CustomDetail) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var value by remember { mutableStateOf(initial.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.custom_detail_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_label"),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(stringResource(R.string.custom_detail_value)) },
                    modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_value"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(CustomDetail(label.trim(), value.trim())) },
                enabled = label.isNotBlank() && value.isNotBlank(),
                modifier = Modifier.testTag("${tagPrefix}_save"),
            ) { Text(stringResource(R.string.custom_detail_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.custom_detail_cancel)) } },
    )
}
