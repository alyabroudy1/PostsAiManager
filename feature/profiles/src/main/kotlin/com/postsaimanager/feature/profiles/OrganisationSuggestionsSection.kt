package com.postsaimanager.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.postsaimanager.core.domain.organisation.SuggestionView
import com.postsaimanager.core.model.PostalValue
import com.postsaimanager.core.model.SuggestionField

/** What the user can do to a suggestion; the screen binds these to the ViewModel, a test binds them to recorders. */
class SuggestionActions(
    /** Accepts suggestion [id]; [edited] is the text the user changed it to, or null to take it as it is. */
    val accept: (id: String, edited: String?) -> Unit = { _, _ -> },
    val dismiss: (id: String) -> Unit = {},
    val acceptAll: () -> Unit = {},
)

/**
 * What the organisation's letters showed for its empty fields: "From <letter>: Address …", each with Accept, Edit and Dismiss, and
 * Accept all. Nothing is saved until the user accepts it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrganisationSuggestionsSection(
    suggestions: List<SuggestionView>,
    actions: SuggestionActions,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth().testTag("suggestions_section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.suggestions_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.suggestions_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        suggestions.forEach { SuggestionCard(it, actions) }
        if (suggestions.size > 1) {
            OutlinedButton(onClick = actions.acceptAll, modifier = Modifier.testTag("suggestions_accept_all")) {
                Text(stringResource(R.string.suggestion_accept_all))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionCard(view: SuggestionView, actions: SuggestionActions) {
    val s = view.suggestion
    var editing by rememberSaveable(s.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("suggestion_${s.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (view.letterTitle.isBlank()) stringResource(R.string.suggestion_from_letter) else stringResource(R.string.suggestion_from, view.letterTitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(fieldLabel(s.field)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(shown(s.field, s.value), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("suggestion_value_${s.id}"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.accept(s.id, null) }, modifier = Modifier.testTag("suggestion_accept_${s.id}")) {
                    Text(stringResource(R.string.suggestion_accept))
                }
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.testTag("suggestion_edit_${s.id}")) {
                    Text(stringResource(R.string.suggestion_edit))
                }
                TextButton(onClick = { actions.dismiss(s.id) }, modifier = Modifier.testTag("suggestion_dismiss_${s.id}")) {
                    Text(stringResource(R.string.suggestion_dismiss))
                }
            }
        }
    }
    if (editing) {
        EditSuggestionDialog(s.field, s.value, onDismiss = { editing = false }, onSave = { actions.accept(s.id, it); editing = false })
    }
}

private fun fieldLabel(field: SuggestionField): Int = when (field) {
    SuggestionField.ADDRESS -> R.string.suggestion_field_address
    SuggestionField.PHONE -> R.string.suggestion_field_phone
    SuggestionField.EMAIL -> R.string.suggestion_field_email
    SuggestionField.WEBSITE -> R.string.suggestion_field_website
    SuggestionField.IBAN -> R.string.suggestion_field_iban
}

/** The value as the card shows it: an address on one line, everything else as printed. */
private fun shown(field: SuggestionField, value: String): String =
    if (field == SuggestionField.ADDRESS) PostalValue.decode(value).oneLine() else value

/** Edits a suggestion's value before it is accepted: street, postcode and city for an address, one text for the rest. */
@Composable
private fun EditSuggestionDialog(field: SuggestionField, value: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var address by remember { mutableStateOf(PostalValue.decode(value)) }
    var text by remember { mutableStateOf(value) }
    val isAddress = field == SuggestionField.ADDRESS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suggestion_edit_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isAddress) {
                    SuggestionTextField(address.street, R.string.profile_field_street, "suggestion_field_street") { address = address.copy(street = it) }
                    SuggestionTextField(address.postalCode, R.string.profile_field_postal_code, "suggestion_field_postal_code") { address = address.copy(postalCode = it) }
                    SuggestionTextField(address.city, R.string.profile_field_city, "suggestion_field_city") { address = address.copy(city = it) }
                } else {
                    SuggestionTextField(
                        text, R.string.suggestion_value, "suggestion_field_value",
                        if (field == SuggestionField.PHONE) KeyboardType.Phone else if (field == SuggestionField.EMAIL) KeyboardType.Email else KeyboardType.Text,
                    ) { text = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(if (isAddress) address.encode() else text.trim()) },
                enabled = if (isAddress) !address.isEmpty else text.isNotBlank(),
                modifier = Modifier.testTag("suggestion_edit_save"),
            ) { Text(stringResource(R.string.suggestion_accept)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.contact_cancel)) } },
    )
}

@Composable
private fun SuggestionTextField(
    value: String?,
    label: Int,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
