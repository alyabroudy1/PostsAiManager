package com.postsaimanager.feature.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import java.time.LocalDate
import java.time.LocalDateTime
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.byContentDirection
import com.postsaimanager.core.domain.skills.ActionDateTime
import com.postsaimanager.core.domain.skills.ActionField
import com.postsaimanager.core.domain.skills.ActionForm
import com.postsaimanager.core.domain.skills.ReminderOffset
import com.postsaimanager.core.domain.skills.AgentAction
import com.postsaimanager.core.domain.skills.FieldCheck
import com.postsaimanager.core.domain.skills.FieldStatus
import com.postsaimanager.core.domain.skills.InvalidReason
import com.postsaimanager.core.domain.skills.OffsetUnit
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
 * Open. Edit turns the fields into text fields; Open and Cancel end the card, which then offers Do again (an opened card: a fresh
 * pending copy) or Restore (a cancelled one: pending again with its last values).
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
    onRestore: () -> Unit = {},
    onDoAgain: () -> Unit = {},
) {
    val pending = state.status == ActionCardStatus.PENDING
    // A finished card is one line; tapping it shows the details (read-only), and its status change (Restore) starts it over.
    var expanded by rememberSaveable(state.id, state.status) { mutableStateOf(false) }
    if (!pending && !expanded) {
        CompactActionLine(state, onExpand = { expanded = true }, onRestore = onRestore, onDoAgain = onDoAgain, modifier = modifier)
        return
    }
    Card(
        modifier = modifier.fillMaxWidth().testTag("actionCard"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(ActionCardTexts.title(state.action)),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!pending) {
                    IconButton(onClick = { expanded = false }, modifier = Modifier.testTag("actionCollapse")) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.action_hide_details))
                    }
                }
            }

            ActionForm.entries(state.action).forEach { (field, _) ->
                val value = state.values[field].orEmpty()
                // An empty optional field (no end time) has nothing to show, unless the user is filling it in.
                if (value.isEmpty() && !(pending && state.editing)) return@forEach
                if (pending && state.editing) {
                    ActionFieldEditor(field, value, state, onChange)
                } else {
                    ActionFieldRow(field, value, state.checkOf(field), understood = field.takeIf { it == ActionField.AT }?.let { state.understoodTime(value) })
                }
            }

            when {
                state.status == ActionCardStatus.OPENED -> {
                    Text(
                        stringResource(ActionCardTexts.openedStatus(state.action)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("actionStatus"),
                    )
                    state.doneAt?.let { at ->
                        Text(
                            stringResource(R.string.action_done_at, remember(at) { at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) }),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDoAgain, modifier = Modifier.testTag("actionDoAgain")) { Text(stringResource(R.string.action_do_again)) }
                }
                state.status == ActionCardStatus.CANCELLED -> {
                    Text(
                        stringResource(R.string.action_status_cancelled),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("actionStatus"),
                    )
                    TextButton(onClick = onRestore, modifier = Modifier.testTag("actionRestore")) { Text(stringResource(R.string.action_restore)) }
                }
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

/**
 * A finished card in one line: "✓ Reminder set · Tomorrow 09:00 · Do again" or "Cancelled · Restore". The line expands to the
 * details; Do again and Restore work from the line itself.
 */
@Composable
internal fun CompactActionLine(
    state: ActionCardState,
    onExpand: () -> Unit,
    onRestore: () -> Unit,
    onDoAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val opened = state.status == ActionCardStatus.OPENED
    val summary = if (opened) actionSummary(state) else null
    val statusText = stringResource(if (opened) ActionCardTexts.openedStatus(state.action) else R.string.action_status_cancelled)
    val line = when {
        !opened -> statusText
        summary != null -> stringResource(R.string.action_compact_opened, statusText, summary)
        else -> stringResource(R.string.action_compact_opened_plain, statusText)
    }
    Card(
        modifier = modifier.fillMaxWidth().testTag("actionCompact"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.action_show_details), onClick = onExpand)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                line,
                style = MaterialTheme.typography.labelLarge.byContentDirection(),
                color = if (opened) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("actionStatus"),
            )
            if (opened) {
                TextButton(onClick = onDoAgain, modifier = Modifier.testTag("actionDoAgain")) { Text(stringResource(R.string.action_do_again)) }
            } else {
                TextButton(onClick = onRestore, modifier = Modifier.testTag("actionRestore")) { Text(stringResource(R.string.action_restore)) }
            }
        }
    }
}

/** What an opened card was about, in a few words: the subject of an email, the time of a reminder or an event; null when it has none. */
@Composable
private fun actionSummary(state: ActionCardState): String? = when (state.action) {
    is AgentAction.SendEmail -> state.values[ActionField.SUBJECT]?.takeIf { it.isNotBlank() }
    is AgentAction.ScheduleReminder -> whenText(state.values[ActionField.AT])
    is AgentAction.CreateCalendarEvent -> whenText(state.values[ActionField.START])
    AgentAction.GetDateTime -> null
}

/** "Today 09:00", "Tomorrow 09:00", or the short date and time of [text] (the card's own date and time field); null when unreadable. */
@Composable
private fun whenText(text: String?): String? {
    val at = text?.let(ActionDateTime::parse) ?: return null
    val today = remember { LocalDate.now() }
    val time = remember(at) { at.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }
    return when (at.toLocalDate()) {
        today -> stringResource(R.string.action_when_today, time)
        today.plusDays(1) -> stringResource(R.string.action_when_tomorrow, time)
        else -> remember(at) { at.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) }
    }
}

@Composable
private fun ActionFieldRow(field: ActionField, value: String, check: FieldCheck?, understood: UnderstoodTime? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("actionRow_${field.name}")) {
        Text(stringResource(ActionCardTexts.label(field)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // What the model's relative time was understood as, so a misheard "in 2 minutes" is seen at once.
        understood?.let {
            Text(
                understoodText(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("actionUnderstood"),
            )
        }
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

/** A reminder's relative time as the model stated it, with the time of day it came to (`HH:mm`). */
internal data class UnderstoodTime(val offset: ReminderOffset, val timeOfDay: String)

/** The relative time the card's reminder was made from, while its time is still the model's own: none once the user edited it. */
internal fun ActionCardState.understoodTime(shownValue: String): UnderstoodTime? {
    val reminder = action as? AgentAction.ScheduleReminder ?: return null
    val offset = reminder.offset ?: return null
    if (ActionField.AT in edited || shownValue != ActionDateTime.format(reminder.at)) return null
    return UnderstoodTime(offset, String.format(Locale.ROOT, "%02d:%02d", reminder.at.hour, reminder.at.minute))
}

/** "In 2 hours · 18:01", "Tomorrow · 09:00", "In 3 days · 09:00". */
@Composable
private fun understoodText(understood: UnderstoodTime): String {
    val (offset, time) = understood.offset to understood.timeOfDay
    return when {
        offset.atTime && offset.days == 0 -> stringResource(R.string.action_understood_today, time)
        offset.atTime && offset.days == 1 -> stringResource(R.string.action_understood_tomorrow, time)
        else -> {
            val parts = buildList {
                if (offset.days > 0) add(pluralStringResource(R.plurals.action_understood_days, offset.days, offset.days))
                if (offset.hours > 0) add(pluralStringResource(R.plurals.action_understood_hours, offset.hours, offset.hours))
                if (offset.minutes > 0) add(pluralStringResource(R.plurals.action_understood_minutes, offset.minutes, offset.minutes))
            }
            stringResource(R.string.action_understood_in, parts.joinToString(" "), time)
        }
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
            if (check.unsaid.isNotEmpty()) {
                // The offset the model gave is not a number the user said ("in 2 minutes" read as 120).
                Column(modifier = Modifier.testTag("actionFlag")) {
                    check.unsaid.forEach { part ->
                        val plural = when (part.unit) {
                            OffsetUnit.DAYS -> R.plurals.action_flag_unsaid_days
                            OffsetUnit.HOURS -> R.plurals.action_flag_unsaid_hours
                            OffsetUnit.MINUTES -> R.plurals.action_flag_unsaid_minutes
                        }
                        Text(
                            pluralStringResource(plural, part.amount, part.amount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else {
                Text(
                    if (check.unfound.isEmpty()) stringResource(R.string.action_flag_not_found)
                    else stringResource(R.string.action_flag_not_found_values, check.unfound.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("actionFlag"),
                )
            }
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
