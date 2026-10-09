package com.postsaimanager.feature.documents

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.FriendlyDate
import com.postsaimanager.core.domain.document.actions.ActionEdit
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.v2.ValueMeaning
import com.postsaimanager.core.domain.timeline.CaseChoices
import com.postsaimanager.core.domain.timeline.CaseTarget
import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.Profile
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// The dialogs and chips with which the user changes what the AI filled in on a letter: an action, who the letter is for, the meaning of a
// date or an amount, the contact person, the matter. Each only collects the person's choice and hands it to a callback; what the choice
// does (a USER source, never overwritten by a re-read) is the domain's.

/** What the user can do to the actions of a letter: edit one, delete one, add one. A recorder in a test. */
internal class ActionEditActions(
    val edit: (original: ActionItem, edit: ActionEdit) -> Unit = { _, _ -> },
    val delete: (original: ActionItem) -> Unit = {},
    val add: (edit: ActionEdit) -> Unit = {},
)

@StringRes
internal fun actionKindLabel(kindId: String): Int = when (kindId) {
    ActionKinds.PAY.id -> R.string.action_kind_pay
    ActionKinds.REPLY.id -> R.string.action_kind_reply
    ActionKinds.OBJECT_CANCEL.id -> R.string.action_kind_object_cancel
    ActionKinds.ATTEND.id -> R.string.action_kind_attend
    ActionKinds.SEND_DOCUMENTS.id -> R.string.action_kind_send_documents
    ActionKinds.SIGN_RETURN.id -> R.string.action_kind_sign_return
    ActionKinds.CONFIRM_RENEW.id -> R.string.action_kind_confirm_renew
    ActionKinds.CONTACT.id -> R.string.action_kind_contact
    else -> R.string.action_kind_other_action
}

/**
 * Edits an action ([initial] given) or adds one: the kind as chips, the wording and the due date.
 *
 * @param shownText the sentence the action is worded as now (for a model action, rendered from its kind and values): the text field starts
 *   with it, and saving it unchanged stores no wording, so the sentence keeps following the values it is rendered from
 * @param shownDate the date the action states now: the same for the date
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ActionEditDialog(
    initial: ActionItem?,
    shownText: String?,
    shownDate: LocalDate?,
    onDismiss: () -> Unit,
    onSave: (ActionEdit) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: ActionKinds.OTHER.id) }
    var text by rememberSaveable { mutableStateOf(initial?.text ?: shownText.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(initial?.dueDate ?: shownDate?.toString()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val parsed = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.action_dialog_add_title else R.string.action_dialog_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.action_dialog_kind_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (ActionKinds.ALL + ActionKinds.OTHER).forEach { k ->
                        FilterChip(
                            selected = kind == k.id,
                            onClick = { kind = k.id },
                            label = { Text(stringResource(actionKindLabel(k.id))) },
                            modifier = Modifier.testTag("action_kind_${k.id}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.action_dialog_text_label)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag("action_text_field"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { picking = true }, modifier = Modifier.testTag("action_date_button")) {
                        Text(
                            parsed?.let { stringResource(R.string.action_dialog_date_label) + ": " + FriendlyDate.text(it) }
                                ?: stringResource(R.string.action_dialog_date_pick),
                        )
                    }
                    if (parsed != null) TextButton(onClick = { date = null }) { Text(stringResource(R.string.action_dialog_date_clear)) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // What is shown unchanged is not stored as the person's own wording or date: it keeps following the values.
                    val unchangedText = initial?.text == null && text.trim() == shownText?.trim()
                    val unchangedDate = initial?.dueDate == null && date == shownDate?.toString()
                    onSave(ActionEdit(kind = kind, text = if (unchangedText) null else text, dueDate = if (unchangedDate) null else date))
                },
                enabled = kind != ActionKinds.OTHER.id || text.isNotBlank(),
                modifier = Modifier.testTag("action_save"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )

    if (picking) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = parsed?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis -> date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                        picking = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}

/**
 * "Who this is for": the household people as chips, the letter's people selected. A tap adds or removes a person and stores the whole
 * list as the user's own, which the people check never changes afterwards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PeopleCard(
    people: List<Profile>,
    selectedIds: List<String>,
    setByUser: Boolean,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("people_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.people_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (setByUser) Text(stringResource(R.string.people_set_by_you), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                people.forEach { person ->
                    val selected = person.id in selectedIds
                    FilterChip(
                        selected = selected,
                        onClick = { onChange(if (selected) selectedIds - person.id else selectedIds + person.id) },
                        label = { Text(person.name) },
                        modifier = Modifier.testTag("people_chip_${person.id}"),
                    )
                }
            }
            Text(stringResource(R.string.people_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Edits the contact person named on the letter: name, role, phone and e-mail, the same fields as on the organisation's page. */
@Composable
internal fun EditContactDialog(contact: ContactPerson, onDismiss: () -> Unit, onSave: (ContactPerson) -> Unit) {
    var name by rememberSaveable(contact.id) { mutableStateOf(contact.name) }
    var role by rememberSaveable(contact.id) { mutableStateOf(contact.title.orEmpty()) }
    var phone by rememberSaveable(contact.id) { mutableStateOf(contact.phone.orEmpty()) }
    var email by rememberSaveable(contact.id) { mutableStateOf(contact.email.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.contact_edit_name)) }, modifier = Modifier.fillMaxWidth().testTag("contact_name_field"))
                OutlinedTextField(role, { role = it }, singleLine = true, label = { Text(stringResource(R.string.contact_edit_role)) }, modifier = Modifier.fillMaxWidth().testTag("contact_role_field"))
                OutlinedTextField(phone, { phone = it }, singleLine = true, label = { Text(stringResource(R.string.contact_edit_phone)) }, modifier = Modifier.fillMaxWidth().testTag("contact_phone_field"))
                OutlinedTextField(email, { email = it }, singleLine = true, label = { Text(stringResource(R.string.contact_edit_email)) }, modifier = Modifier.fillMaxWidth().testTag("contact_email_field"))
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(contact.copy(name = name, title = role, phone = phone, email = email)) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("contact_save"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Where to put the letter: no matter, a new one (named or not), or one of its sender's matters (the current one marked). One choice, then
 * Save.
 */
@Composable
internal fun MoveToCaseDialog(choices: CaseChoices, onDismiss: () -> Unit, onPick: (CaseTarget) -> Unit) {
    // The choice is "none", "new" or the id of a matter; it starts on the one the letter is in.
    var picked by rememberSaveable { mutableStateOf(choices.currentCaseId ?: CHOICE_NONE) }
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.case_move_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChoiceRow(stringResource(R.string.case_move_none), picked == CHOICE_NONE, "case_choice_none") { picked = CHOICE_NONE }
                choices.cases.forEach { case ->
                    val label = if (case.id == choices.currentCaseId) case.title + " (" + stringResource(R.string.case_move_here) + ")" else case.title
                    ChoiceRow(label, picked == case.id, "case_choice_${case.id}") { picked = case.id }
                }
                ChoiceRow(stringResource(R.string.case_move_new), picked == CHOICE_NEW, "case_choice_new") { picked = CHOICE_NEW }
                if (picked == CHOICE_NEW) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.case_move_new_label)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("case_new_title"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onPick(
                        when (picked) {
                            CHOICE_NONE -> CaseTarget.None
                            CHOICE_NEW -> CaseTarget.New(title)
                            else -> CaseTarget.Existing(picked)
                        },
                    )
                },
                modifier = Modifier.testTag("case_move_save"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val CHOICE_NONE = "\u0000none"
private const val CHOICE_NEW = "\u0000new"

/**
 * The meanings a date or an amount can be given, as chips ("None of these" first), one selected. [selectedId] is the current meaning's id
 * (null: none); [onSelect] gets the chosen id, null for "None of these".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeaningChips(choices: List<ValueMeaning>, selectedId: String?, onSelect: (String?) -> Unit) {
    Text(stringResource(R.string.edit_meaning_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.edit_meaning_none)) },
            modifier = Modifier.testTag("meaning_none"),
        )
        choices.forEach { meaning ->
            FilterChip(
                selected = selectedId == meaning.id,
                onClick = { onSelect(meaning.id) },
                label = { Text(SlotLabels.meaningLabel(meaning.id)?.let { stringResource(it) } ?: meaning.id) },
                modifier = Modifier.testTag("meaning_${meaning.id}"),
            )
        }
    }
}
