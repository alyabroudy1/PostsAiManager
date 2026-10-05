package com.postsaimanager.feature.documents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.ExtractedData

// The top of the Extracted tab: what to do, who it is from and for, the key information. Each part is a card with a heading; every
// line keeps its own ✓ (confirm), and the rest (edit, ignore) is in the line's overflow menu. A line the extraction was unsure of is
// marked in place, so nothing is listed twice.

/** A titled card of the essentials; the title is a heading for screen readers. */
@Composable
private fun EssentialCard(title: String, containerColor: Color, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
            content()
        }
    }
}

// ═══════════════════════════════════════════════════════════
// What you need to do
// ═══════════════════════════════════════════════════════════

/** "What you need to do": the action lines the AI wrote, each with the values it states as copyable sub-lines. */
@Composable
internal fun ActionsCard(lines: List<ActionLine>, actions: FieldActions) {
    EssentialCard(stringResource(R.string.essentials_actions_title), MaterialTheme.colorScheme.primaryContainer) {
        lines.forEachIndexed { index, line ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(end = 12.dp))
            ActionLineView(line, actions)
        }
    }
}

@Composable
private fun ActionLineView(line: ActionLine, actions: FieldActions) {
    val open = line.rows.filter { !it.isSettled }
    val uncertain = line.rows.any { it.isUncertain }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
            )
            if (line.rows.isNotEmpty()) {
                if (open.isNotEmpty()) ConfirmButton(line.text) { actions.confirm(open.map { it.id }) } else ConfirmedMark(line.text)
                OverflowMenu(line.text) { close ->
                    line.rows.forEach { row ->
                        val label = fieldLabelText(row)
                        MenuItem(R.string.action_menu_edit, R.string.action_edit_field, label) { close(); actions.edit(row) }
                    }
                    line.rows.forEach { row ->
                        val label = fieldLabelText(row)
                        MenuItem(R.string.action_menu_ignore, R.string.action_ignore_field, label) { close(); actions.ignore(listOf(row.id)) }
                    }
                }
            }
        }
        line.rows.forEach { row -> ValueSubLine(row) }
        line.rows.filter { it.hasUnreviewedMachineChange && it.machineValue != null }.forEach { MachineChangeNotice(it, actions) }
        if (uncertain) WorthChecking()
    }
}

/** "IBAN: DE02 …" under an action line, with its copy button. */
@Composable
private fun ValueSubLine(row: ExtractedData) {
    val label = fieldLabelText(row)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label: ${row.fieldValue}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
        )
        CopyButton(label, row.fieldValue)
    }
}

// ═══════════════════════════════════════════════════════════
// From / For / About
// ═══════════════════════════════════════════════════════════

/** "From / For / About": the sender, the addressee ("You" for the Me profile) and the person the letter is about, when someone else. */
@Composable
internal fun PartiesCard(parties: PartiesView, actions: FieldActions) {
    EssentialCard(stringResource(R.string.essentials_parties_title), MaterialTheme.colorScheme.surfaceContainerHigh) {
        parties.from?.let { PartyRow(stringResource(R.string.essentials_from), it, actions) }
        parties.forWhom?.let { PartyRow(stringResource(R.string.essentials_for), it, actions) }
        parties.about?.let { PartyRow(stringResource(R.string.essentials_about), it, actions) }
    }
}

@Composable
private fun PartyRow(label: String, party: PartyEntry, actions: FieldActions) {
    val value = when (val r = party.recipient) {
        PagesRecipient.You -> stringResource(R.string.pages_to_you)
        is PagesRecipient.Named -> r.name
        null -> party.row.fieldValue
    }
    EssentialLine(label = label, value = value, row = party.row, actions = actions, copyable = false) {
        if (party.addressLines.isNotEmpty()) AddressLine(party.row.id, party.addressLines)
    }
}

/** The party's address as one compact line that opens to the printed lines on a tap. */
@Composable
private fun AddressLine(key: String, lines: List<String>) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    val text = if (expanded) lines.joinToString("\n") else stringResource(R.string.essentials_address_line, lines.joinToString(", "))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .minimumInteractiveComponentSize()
            .semantics { stateDescription = state },
    )
}

// ═══════════════════════════════════════════════════════════
// Key information
// ═══════════════════════════════════════════════════════════

/** "Key information": the subject line, then the facts the AI picked for this kind of document, each with a copy button. */
@Composable
internal fun KeyInfoCard(subject: ExtractedData?, keyInfo: List<ExtractedData>, actions: FieldActions) {
    EssentialCard(stringResource(R.string.essentials_key_title), MaterialTheme.colorScheme.surfaceContainerHigh) {
        subject?.let { EssentialLine(fieldLabelText(it), it.fieldValue, it, actions, copyable = false) }
        keyInfo.forEach { EssentialLine(fieldLabelText(it), it.fieldValue, it, actions, copyable = true) }
    }
}

// ═══════════════════════════════════════════════════════════
// Shared pieces
// ═══════════════════════════════════════════════════════════

/**
 * One label and value of the essentials with its ✓ and an overflow menu (Edit, Ignore). The label and value read as one item for screen
 * readers. An uncertain row is marked, and a value that changed under a person's own is spelled out with "Keep mine".
 */
@Composable
private fun EssentialLine(
    label: String,
    value: String,
    row: ExtractedData,
    actions: FieldActions,
    copyable: Boolean,
    below: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
            if (copyable) CopyButton(label, value)
            if (row.isSettled) ConfirmedMark(label) else ConfirmButton(label) { actions.confirm(listOf(row.id)) }
            OverflowMenu(label) { close ->
                MenuItem(R.string.action_menu_edit, R.string.action_edit_field, label) { close(); actions.edit(row) }
                MenuItem(R.string.action_menu_ignore, R.string.action_ignore_field, label) { close(); actions.ignore(listOf(row.id)) }
            }
        }
        below()
        if (row.hasUnreviewedMachineChange && row.machineValue != null) MachineChangeNotice(row, actions)
        if (row.isUncertain) WorthChecking()
    }
}

@Composable
private fun WorthChecking() {
    Text(
        stringResource(R.string.field_worth_checking),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** The disagreement, spelled out: what the document now reads where a person's value stands, with the two ways out. */
@Composable
internal fun MachineChangeNotice(field: ExtractedData, actions: FieldActions) {
    Text(
        stringResource(R.string.field_machine_changed, field.machineValue.orEmpty()),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(end = 8.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { actions.confirm(listOf(field.id)) }) { Text(stringResource(R.string.field_keep_mine)) }
        TextButton(onClick = { actions.edit(field) }) { Text(stringResource(R.string.field_review)) }
    }
}

@Composable
private fun ConfirmButton(label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(PamIcons.Done, contentDescription = stringResource(R.string.action_confirm_field, label), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ConfirmedMark(label: String) {
    Box(modifier = Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Icon(
            PamIcons.Done,
            contentDescription = stringResource(R.string.field_confirmed, label),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CopyButton(label: String, text: String) {
    val context = LocalContext.current
    IconButton(onClick = { copyToClipboard(context, label, text) }) {
        Icon(PamIcons.Copy, contentDescription = stringResource(R.string.action_copy_field, label), modifier = Modifier.size(18.dp))
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
}

/** The ⋮ button of a line and its menu; [items] builds the entries and gets the function that closes the menu. */
@Composable
internal fun OverflowMenu(label: String, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(PamIcons.More, contentDescription = stringResource(R.string.action_more_field, label), modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

/** A menu entry shown as a short word ("Edit") that screen readers read in full, naming the row ("Edit Amount"). */
@Composable
internal fun MenuItem(
    @StringRes shortRes: Int,
    @StringRes fullRes: Int,
    label: String,
    onClick: () -> Unit,
) {
    val full = stringResource(fullRes, label)
    DropdownMenuItem(
        text = { Text(stringResource(shortRes)) },
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = full },
    )
}
