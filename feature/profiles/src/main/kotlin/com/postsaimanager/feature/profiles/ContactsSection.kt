package com.postsaimanager.feature.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.domain.contacts.OrganisationContacts
import com.postsaimanager.core.model.ContactPerson
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The people at an organisation, read-only: the current contact first, then the earlier ones. (Editing, merging and marking
 * "no longer responsible" come with the contact-linking phase.)
 */
@Composable
internal fun ContactsSection(contacts: OrganisationContacts, modifier: Modifier = Modifier) {
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
            ContactRow(current, Modifier.testTag("contact_current"))
        }
        if (contacts.earlier.isNotEmpty()) {
            Text(stringResource(R.string.contacts_earlier), style = MaterialTheme.typography.labelLarge)
            contacts.earlier.forEach { ContactRow(it, Modifier.testTag("contact_earlier")) }
        }
    }
}

@Composable
private fun ContactRow(contact: ContactPerson, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(contact.name, style = MaterialTheme.typography.bodyLarge)
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
    }
}

private fun formatDate(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
