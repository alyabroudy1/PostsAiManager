package com.postsaimanager.feature.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.byContentDirection
import com.postsaimanager.core.domain.skills.ActionField
import com.postsaimanager.core.domain.skills.ActionForm
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.FieldCheck
import com.postsaimanager.core.domain.skills.FieldStatus
import com.postsaimanager.core.domain.skills.InvalidReason

/** The words of an action card: one place that maps the domain's fields, verdicts and kinds to string resources. */
internal object ActionCardTexts {

    @StringRes
    fun title(action: AgentAction): Int = when (action) {
        is AgentAction.SendEmail -> R.string.action_card_email_title
        is AgentAction.CreateCalendarEvent -> R.string.action_card_event_title
        is AgentAction.ScheduleReminder -> R.string.action_card_reminder_title
        AgentAction.GetDateTime -> R.string.action_card_event_title
    }

    @StringRes
    fun label(field: ActionField): Int = when (field) {
        ActionField.TO -> R.string.action_field_to
        ActionField.SUBJECT -> R.string.action_field_subject
        ActionField.BODY -> R.string.action_field_body
        ActionField.TITLE -> R.string.action_field_title
        ActionField.START -> R.string.action_field_start
        ActionField.END -> R.string.action_field_end
        ActionField.DESCRIPTION -> R.string.action_field_description
        ActionField.AT -> R.string.action_field_at
        ActionField.TEXT -> R.string.action_field_text
    }

    @StringRes
    fun error(reason: InvalidReason): Int = when (reason) {
        InvalidReason.REQUIRED -> R.string.action_error_required
        InvalidReason.NOT_AN_EMAIL -> R.string.action_error_email
        InvalidReason.NOT_A_DATE_TIME -> R.string.action_error_date_time
        InvalidReason.END_BEFORE_START -> R.string.action_error_end_before_start
        InvalidReason.IN_THE_PAST -> R.string.action_error_past
    }

    @StringRes
    fun openedStatus(action: AgentAction): Int = when (action) {
        is AgentAction.SendEmail -> R.string.action_status_opened_email
        is AgentAction.CreateCalendarEvent -> R.string.action_status_opened_event
        is AgentAction.ScheduleReminder -> R.string.action_status_reminder_set
        AgentAction.GetDateTime -> R.string.action_status_opened_event
    }
}

/**
 * The card of a proposed action: every field with the grounding flag of its value, and Open / Edit / Cancel. Nothing runs until
 * Open. Edit turns the fields into text fields; Open and Cancel are final and leave the card showing its last state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionCard(
    state: ActionCardState,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onChange: (ActionField, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.status == ActionCardStatus.PENDING
    Card(
        modifier = modifier.fillMaxWidth().testTag("actionCard"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(ActionCardTexts.title(state.action)), style = MaterialTheme.typography.titleSmall)

            ActionForm.entries(state.action).forEach { (field, _) ->
                val value = state.values[field].orEmpty()
                // An empty optional field (no end time) has nothing to show, unless the user is filling it in.
                if (value.isEmpty() && !(pending && state.editing)) return@forEach
                if (pending && state.editing) {
                    ActionFieldEditor(field, value, state, onChange)
                } else {
                    ActionFieldRow(field, value, state.checkOf(field))
                }
            }

            when {
                state.status == ActionCardStatus.OPENED ->
                    Text(
                        stringResource(ActionCardTexts.openedStatus(state.action)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("actionStatus"),
                    )
                state.status == ActionCardStatus.CANCELLED ->
                    Text(
                        stringResource(R.string.action_status_cancelled),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("actionStatus"),
                    )
                else -> {
                    if (state.openFailed) {
                        Text(stringResource(R.string.action_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = onOpen, modifier = Modifier.testTag("actionOpen")) { Text(stringResource(R.string.action_open)) }
                        if (!state.editing) {
                            OutlinedButton(onClick = onEdit, modifier = Modifier.testTag("actionEdit")) { Text(stringResource(R.string.action_edit)) }
                        }
                        TextButton(onClick = onCancel, modifier = Modifier.testTag("actionCancel")) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionFieldRow(field: ActionField, value: String, check: FieldCheck?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("actionRow_${field.name}")) {
        Text(stringResource(ActionCardTexts.label(field)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The body can be long: a preview of it, the whole text is in Edit.
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.byContentDirection(),
            maxLines = if (field == ActionField.BODY) BODY_PREVIEW_LINES else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
        )
        CheckLine(check)
    }
}

@Composable
private fun ActionFieldEditor(
    field: ActionField,
    value: String,
    state: ActionCardState,
    onChange: (ActionField, String) -> Unit,
) {
    val error = state.errors[field]
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(field, it) },
            label = { Text(stringResource(ActionCardTexts.label(field))) },
            textStyle = MaterialTheme.typography.bodyLarge.byContentDirection(),
            isError = error != null,
            singleLine = field != ActionField.BODY && field != ActionField.DESCRIPTION && field != ActionField.TEXT,
            minLines = if (field == ActionField.BODY) BODY_EDIT_LINES else 1,
            modifier = Modifier.fillMaxWidth().testTag("actionField_${field.name}"),
        )
        if (error != null) {
            Text(stringResource(ActionCardTexts.error(error)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else {
            CheckLine(state.checkOf(field))
        }
    }
}

/** The one line under a value: flagged when it is not in the letter, invalid when unusable, "yours" when the user typed it. */
@Composable
private fun CheckLine(check: FieldCheck?) {
    if (check == null) return
    when (check.status) {
        FieldStatus.NOT_FOUND ->
            Text(
                if (check.unfound.isEmpty()) stringResource(R.string.action_flag_not_found)
                else stringResource(R.string.action_flag_not_found_values, check.unfound.joinToString(", ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("actionFlag"),
            )
        FieldStatus.INVALID ->
            check.reason?.let {
                Text(stringResource(ActionCardTexts.error(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("actionFlag"))
            }
        FieldStatus.USER_ENTERED ->
            Text(stringResource(R.string.action_flag_edited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FieldStatus.GROUNDED, FieldStatus.FREE -> Unit
    }
}

private const val BODY_PREVIEW_LINES = 6
private const val BODY_EDIT_LINES = 5
