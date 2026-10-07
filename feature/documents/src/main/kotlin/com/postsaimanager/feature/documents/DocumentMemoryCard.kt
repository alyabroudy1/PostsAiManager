package com.postsaimanager.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.FriendlyDate
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.memory.DocumentNoteText
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.NoteSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * "What the assistant remembers": the document's durable notes, as the assistant reads them at the start of a chat. Each note shows
 * where it came from (an action, a chat, you), its date, and lets the user pin, edit or delete it; "Add a note" writes one of the
 * user's own. Trust and privacy: nothing the assistant keeps is hidden from the person it is about.
 *
 * Lives in the Extracted tab, under what the reading found: both are what the app knows about this letter, and the user reviews
 * them in one place (the chat's header is for talking).
 */
@Composable
internal fun DocumentMemoryCard(notes: List<DocumentNote>, actions: NoteActions, today: LocalDate = LocalDate.now()) {
    // Kept across a rotation: the note being edited is its id, resolved from the list, so the dialog shows the latest text.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val editing = editingId?.let { id -> notes.firstOrNull { it.id == id } }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.memory_title))
        Text(
            stringResource(R.string.memory_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (notes.isEmpty()) {
            Text(
                stringResource(R.string.memory_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        notes.forEach { note ->
            NoteRow(note, today, actions, onEdit = { editingId = note.id })
        }
        TextButton(onClick = { adding = true }) {
            Icon(PamIcons.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.memory_add))
        }
    }

    if (adding) {
        NoteDialog(
            title = stringResource(R.string.memory_add),
            initial = "",
            onDismiss = { adding = false },
            onSave = {
                actions.add(it)
                adding = false
            },
        )
    }
    editing?.let { note ->
        NoteDialog(
            title = stringResource(R.string.memory_edit_title),
            initial = note.text,
            onDismiss = { editingId = null },
            onSave = {
                actions.edit(note.id, it)
                editingId = null
            },
        )
    }
}

@Composable
private fun NoteRow(note: DocumentNote, today: LocalDate, actions: NoteActions, onEdit: () -> Unit) {
    var menuOpen by rememberSaveable(note.id) { mutableStateOf(false) }
    val sourceLabel = stringResource(sourceLabelRes(note.source))
    val date = FriendlyDate.text(Instant.ofEpochMilli(note.updatedAt).atZone(ZoneId.systemDefault()).toLocalDate(), today)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            sourceIcon(note.source),
            contentDescription = sourceLabel,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(note.text, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.memory_note_meta, sourceLabel, date),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { actions.pin(note.id, !note.pinned) }) {
            Icon(
                if (note.pinned) PamIcons.Pin else PamIcons.PinOutlined,
                contentDescription = stringResource(if (note.pinned) R.string.memory_unpin else R.string.memory_pin),
                tint = if (note.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            IconButton(onClick = { menuOpen = true }) {
                Icon(PamIcons.More, contentDescription = stringResource(R.string.memory_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.memory_edit)) },
                    leadingIcon = { Icon(PamIcons.Edit, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.memory_delete)) },
                    leadingIcon = { Icon(PamIcons.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        actions.delete(note.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun NoteDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(DocumentNoteText.MAX_CHARS) },
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.memory_note_label)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.memory_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.memory_cancel)) } },
    )
}

private fun sourceIcon(source: NoteSource) = when (source) {
    NoteSource.ACTION -> PamIcons.Done
    NoteSource.AI -> PamIcons.AiModel
    NoteSource.USER -> PamIcons.Person
}

private fun sourceLabelRes(source: NoteSource) = when (source) {
    NoteSource.ACTION -> R.string.memory_source_action
    NoteSource.AI -> R.string.memory_source_ai
    NoteSource.USER -> R.string.memory_source_you
}
