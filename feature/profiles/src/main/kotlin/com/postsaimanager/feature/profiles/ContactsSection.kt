package com.postsaimanager.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.contacts.OrganisationContacts
import com.postsaimanager.core.model.ContactPerson
import com.postsaimanager.core.model.Profile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * What the user can do to a contact of an organisation. Each is one small use case behind the contact repository; the screen binds
 * them to the ViewModel, a test binds them to recorders.
 */
class ContactActions(
    val save: (ContactPerson) -> Unit = {},
    val setActive: (contactId: String, active: Boolean) -> Unit = { _, _ -> },
    val merge: (keepId: String, mergedId: String) -> Unit = { _, _ -> },
    val move: (contactId: String, organisationId: String) -> Unit = { _, _ -> },
    val delete: (contactId: String) -> Unit = {},
    val call: (phone: String) -> Unit = {},
    val email: (address: String) -> Unit = {},
)

/** The one dialog open at a time, kept as a plain value so a rotation keeps it. */
private enum class ContactDialog { EDIT, MERGE, MOVE, DELETE }

/**
 * The people at an organisation: "Your contact now" with Call and Email, then "Earlier contacts". Each contact has a menu: edit, mark
 * "No longer responsible" (or responsible again), merge into another contact, move to another organisation, delete (the letters stay).
 *
 * @param focusContactId the contact a letter's chip opened the page for; [onFocusPlaced] tells the screen where its row is (root y)
 * @param otherOrganisations the organisation profiles a contact can be moved to
 */
@Composable
internal fun ContactsSection(
    contacts: OrganisationContacts,
    modifier: Modifier = Modifier,
    actions: ContactActions = ContactActions(),
    focusContactId: String? = null,
    otherOrganisations: List<Profile> = emptyList(),
    onFocusPlaced: (rootY: Float) -> Unit = {},
) {
    val everyone = listOfNotNull(contacts.current) + contacts.earlier
    Column(
        modifier = modifier.fillMaxWidth().testTag("contacts_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.contacts_title), style = MaterialTheme.typography.titleMedium)
        if (contacts.isEmpty) {
            Text(
                stringResource(R.string.contacts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("contacts_empty"),
            )
        }
        contacts.current?.let { current ->
            Text(stringResource(R.string.contacts_current), style = MaterialTheme.typography.labelLarge)
            ContactRow(current, everyone, otherOrganisations, actions, focusContactId, onFocusPlaced, isCurrent = true)
        }
        if (contacts.earlier.isNotEmpty()) {
            Text(stringResource(R.string.contacts_earlier), style = MaterialTheme.typography.labelLarge)
            contacts.earlier.forEach {
                ContactRow(it, everyone, otherOrganisations, actions, focusContactId, onFocusPlaced, isCurrent = false)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactRow(
    contact: ContactPerson,
    everyone: List<ContactPerson>,
    otherOrganisations: List<Profile>,
    actions: ContactActions,
    focusContactId: String?,
    onFocusPlaced: (rootY: Float) -> Unit,
    isCurrent: Boolean,
    modifier: Modifier = Modifier,
) {
    var dialog by rememberSaveable(contact.id) { mutableStateOf<ContactDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth()
            // The letter's chip opened this page for this contact: the screen is told where its row is, and scrolls to it.
            .onGloballyPositioned { if (contact.id == focusContactId) onFocusPlaced(it.positionInRoot().y) }
            .testTag("contact_${contact.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val moreDescription = stringResource(R.string.contact_more, contact.name)
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.testTag("contact_menu_${contact.id}").semantics { contentDescription = moreDescription },
            ) { Icon(PamIcons.More, contentDescription = null) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contact_menu_edit)) },
                    onClick = { menuOpen = false; dialog = ContactDialog.EDIT },
                    modifier = Modifier.testTag("menu_edit"),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (contact.active) R.string.contacts_inactive else R.string.contact_menu_active)) },
                    onClick = { menuOpen = false; actions.setActive(contact.id, !contact.active) },
                    modifier = Modifier.testTag("menu_active"),
                )
                if (everyone.size > 1) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contact_menu_merge)) },
                        onClick = { menuOpen = false; dialog = ContactDialog.MERGE },
                        modifier = Modifier.testTag("menu_merge"),
                    )
                }
                if (otherOrganisations.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.contact_menu_move)) },
                        onClick = { menuOpen = false; dialog = ContactDialog.MOVE },
                        modifier = Modifier.testTag("menu_move"),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.contact_menu_delete)) },
                    onClick = { menuOpen = false; dialog = ContactDialog.DELETE },
                    modifier = Modifier.testTag("menu_delete"),
                )
            }
        }
        listOfNotNull(contact.title, contact.department, contact.room).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listOfNotNull(contact.phone, contact.email).joinToString(" · ").takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            stringResource(R.string.contacts_last_seen, formatDate(contact.lastSeen)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!contact.active) {
            Text(
                stringResource(R.string.contacts_inactive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isCurrent && (!contact.phone.isNullOrBlank() || !contact.email.isNullOrBlank())) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                contact.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    OutlinedButton(onClick = { actions.call(phone) }, modifier = Modifier.testTag("contact_call")) {
                        Text(stringResource(R.string.contact_call))
                    }
                }
                contact.email?.takeIf { it.isNotBlank() }?.let { address ->
                    OutlinedButton(onClick = { actions.email(address) }, modifier = Modifier.testTag("contact_email")) {
                        Text(stringResource(R.string.contact_email))
                    }
                }
            }
        }
    }

    when (dialog) {
        ContactDialog.EDIT -> EditContactDialog(contact, onDismiss = { dialog = null }, onSave = { actions.save(it); dialog = null })
        ContactDialog.MERGE -> PickDialog(
            title = stringResource(R.string.contact_merge_title, contact.name),
            hint = stringResource(R.string.contact_merge_hint, contact.name),
            choices = everyone.filter { it.id != contact.id }.map { Choice(it.id, it.name, it.title) },
            tag = "merge_target_",
            onDismiss = { dialog = null },
            // The contact picked is the one that stays: this one goes into it.
            onPick = { actions.merge(it, contact.id); dialog = null },
        )
        ContactDialog.MOVE -> PickDialog(
            title = stringResource(R.string.contact_move_title, contact.name),
            hint = null,
            choices = otherOrganisations.map { Choice(it.id, it.name, it.city) },
            tag = "move_target_",
            onDismiss = { dialog = null },
            onPick = { actions.move(contact.id, it); dialog = null },
        )
        ContactDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.contact_delete_title, contact.name)) },
            text = { Text(stringResource(R.string.contact_delete_body)) },
            confirmButton = {
                TextButton(onClick = { actions.delete(contact.id); dialog = null }, modifier = Modifier.testTag("contact_delete_confirm")) {
                    Text(stringResource(R.string.contact_menu_delete))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.contact_cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun EditContactDialog(contact: ContactPerson, onDismiss: () -> Unit, onSave: (ContactPerson) -> Unit) {
    var draft by remember { mutableStateOf(contact) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactField(draft.name, R.string.contact_field_name, "contact_field_name") { v -> draft = draft.copy(name = v) }
                ContactField(draft.title, R.string.contact_field_title, "contact_field_title") { v -> draft = draft.copy(title = v) }
                ContactField(draft.department, R.string.contact_field_department, "contact_field_department") { v -> draft = draft.copy(department = v) }
                ContactField(draft.phone, R.string.contact_field_phone, "contact_field_phone", KeyboardType.Phone) { v -> draft = draft.copy(phone = v) }
                ContactField(draft.email, R.string.contact_field_email, "contact_field_email", KeyboardType.Email) { v -> draft = draft.copy(email = v) }
                ContactField(draft.room, R.string.contact_field_room, "contact_field_room") { v -> draft = draft.copy(room = v) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = draft.name.isNotBlank(), modifier = Modifier.testTag("contact_save")) {
                Text(stringResource(R.string.contact_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.contact_cancel)) } },
    )
}

@Composable
private fun ContactField(value: String?, label: Int, tag: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

private class Choice(val id: String, val label: String, val detail: String?)

@Composable
private fun PickDialog(
    title: String,
    hint: String?,
    choices: List<Choice>,
    tag: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                choices.forEach { choice ->
                    TextButton(onClick = { onPick(choice.id) }, modifier = Modifier.fillMaxWidth().testTag(tag + choice.id)) {
                        Text(listOfNotNull(choice.label, choice.detail?.takeIf { it.isNotBlank() }).joinToString(" · "), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.contact_cancel)) } },
    )
}

private fun formatDate(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
