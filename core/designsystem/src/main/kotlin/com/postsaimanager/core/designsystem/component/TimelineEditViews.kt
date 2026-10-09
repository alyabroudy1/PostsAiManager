package com.postsaimanager.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.CaseStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What the user can change on a timeline: an event (its kind, date and text), delete one, add one to a letter, and a matter's status.
 * The screen binds these to use cases; a screen that offers none passes null and the timeline has no menus.
 *
 * @property kindIds the event kinds to choose from, in the order shown (the registry's); the words come from the screen's `kindLabel`
 * @property onEditEvent the user saved an event: its id, the kind, the day (epoch millis of its start) and the text (not blank)
 * @property onDeleteEvent the user deleted an event
 * @property onSetStatus the user set a matter's status, or null for "Automatic" (handed back to the events)
 * @property onAddEvent the user added an event to the letter being looked at (null where no single letter is in view)
 */
class TimelineEdits(
    val kindIds: List<String>,
    val onEditEvent: (eventId: String, kindId: String, eventDate: Long, title: String) -> Unit,
    val onDeleteEvent: (eventId: String) -> Unit,
    val onSetStatus: (caseId: String, status: CaseStatus?) -> Unit,
    val onAddEvent: ((kindId: String, eventDate: Long, title: String) -> Unit)? = null,
)

internal fun startOfDayMillis(date: LocalDate): Long = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** The ⋮ of an event row: Edit and Delete (with its edit dialog). */
@Composable
internal fun EventMenu(event: TimelineEventUi, kindLabel: (String) -> String, edits: TimelineEdits) {
    var menuOpen by rememberSaveable(event.id) { mutableStateOf(false) }
    var editing by rememberSaveable(event.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("timeline_event_menu_${event.id}")) {
            Icon(PamIcons.More, contentDescription = stringResource(R.string.timeline_event_menu))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.timeline_event_edit)) },
                onClick = {
                    menuOpen = false
                    editing = true
                },
                modifier = Modifier.testTag("timeline_event_edit_${event.id}"),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.timeline_event_delete)) },
                onClick = {
                    menuOpen = false
                    edits.onDeleteEvent(event.id)
                },
                modifier = Modifier.testTag("timeline_event_delete_${event.id}"),
            )
        }
    }
    if (editing) {
        EventEditDialog(
            initial = event,
            kindIds = edits.kindIds,
            kindLabel = kindLabel,
            onDismiss = { editing = false },
            onSave = { kind, date, title ->
                editing = false
                edits.onEditEvent(event.id, kind, date, title)
            },
        )
    }
}

/**
 * Edits an event ([initial] given) or adds one: the kind as chips, the day from a date picker, and the text.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EventEditDialog(
    initial: TimelineEventUi?,
    kindIds: List<String>,
    kindLabel: (String) -> String,
    onDismiss: () -> Unit,
    onSave: (kindId: String, eventDate: Long, title: String) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(initial?.kindId ?: kindIds.firstOrNull().orEmpty()) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var dayMillis by rememberSaveable { mutableStateOf(initial?.eventDate ?: startOfDayMillis(LocalDate.now())) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val day = Instant.ofEpochMilli(dayMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.timeline_event_add_title else R.string.timeline_event_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.timeline_event_kind_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kindIds.forEach { id ->
                        FilterChip(selected = kind == id, onClick = { kind = id }, label = { Text(kindLabel(id)) }, modifier = Modifier.testTag("event_kind_$id"))
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.timeline_event_text_label)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("event_text_field"),
                )
                TextButton(onClick = { picking = true }, modifier = Modifier.testTag("event_date_button")) {
                    Text(stringResource(R.string.timeline_event_date_label) + ": " + FriendlyDate.text(day))
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(kind, dayMillis, title.trim()) }, enabled = kind.isNotEmpty() && title.isNotBlank(), modifier = Modifier.testTag("event_save")) {
                Text(stringResource(R.string.timeline_rename_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_rename_cancel)) } },
    )

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { picked ->
                            dayMillis = startOfDayMillis(Instant.ofEpochMilli(picked).atZone(ZoneOffset.UTC).toLocalDate())
                        }
                        picking = false
                    },
                ) { Text(stringResource(R.string.timeline_rename_save)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.timeline_rename_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** The status of a matter: "Automatic" (from the letters' events) or one of the four, one choice then Save. */
@Composable
fun CaseStatusDialog(current: CaseStatus, byUser: Boolean, onDismiss: () -> Unit, onPick: (CaseStatus?) -> Unit) {
    // null is "Automatic".
    var picked by rememberSaveable { mutableStateOf<CaseStatus?>(if (byUser) current else null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timeline_status_dialog_title)) },
        text = {
            Column {
                StatusChoice(stringResource(R.string.timeline_status_automatic), picked == null, "status_automatic") { picked = null }
                CaseStatus.entries.forEach { status ->
                    StatusChoice(stringResource(statusWords(status)), picked == status, "status_${status.name}") { picked = status }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPick(picked) }, modifier = Modifier.testTag("status_save")) { Text(stringResource(R.string.timeline_rename_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_rename_cancel)) } },
    )
}

@Composable
private fun StatusChoice(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun statusWords(status: CaseStatus): Int = when (status) {
    CaseStatus.OPEN -> R.string.timeline_status_open
    CaseStatus.APPROVED -> R.string.timeline_status_approved
    CaseStatus.REJECTED -> R.string.timeline_status_rejected
    CaseStatus.CLOSED -> R.string.timeline_status_closed
}
