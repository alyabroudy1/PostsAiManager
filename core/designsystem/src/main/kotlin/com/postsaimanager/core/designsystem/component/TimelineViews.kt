package com.postsaimanager.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.CaseStatus
import com.postsaimanager.core.model.EventSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How many matters (or events of one matter) show before "Show all". */
internal const val TIMELINE_VISIBLE = 5

private fun epochDay(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * A profile's timeline: the matter cards (each expands to its dated events), then "Other" with the plain events. Stateless but for
 * what is expanded; the screen owns what a tap does.
 *
 * @param kindLabel the words of an event kind id in the user's language (the registry's label).
 * @param focusCaseId a matter that starts expanded (a letter's "Part of" row opened it).
 * @param onOpenDocument the letter an event belongs to.
 * @param onRenameCase the user typed a new name for a matter (not blank).
 */
@Composable
fun TimelineSection(
    timeline: TimelineUi,
    kindLabel: (String) -> String,
    onOpenDocument: (documentId: String) -> Unit,
    onRenameCase: (caseId: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    focusCaseId: String? = null,
) {
    var showAllCases by rememberSaveable { mutableStateOf(false) }
    // A matter named by the page's argument is always among the visible ones.
    val focusIndex = timeline.cases.indexOfFirst { it.caseId == focusCaseId }
    val limit = if (showAllCases || focusIndex >= TIMELINE_VISIBLE) timeline.cases.size else TIMELINE_VISIBLE
    Column(modifier = modifier.fillMaxWidth().testTag("timeline_section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.timeline_title), style = MaterialTheme.typography.titleMedium)
        if (timeline.isEmpty) {
            Text(
                stringResource(R.string.timeline_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("timeline_empty"),
            )
        }
        timeline.cases.take(limit).forEach { case ->
            CaseCard(case, kindLabel, startExpanded = case.caseId == focusCaseId, onOpenDocument, onRenameCase)
        }
        if (timeline.cases.size > TIMELINE_VISIBLE) {
            TextButton(onClick = { showAllCases = !showAllCases }, modifier = Modifier.testTag("timeline_cases_toggle")) {
                Text(if (showAllCases) stringResource(R.string.timeline_show_less) else stringResource(R.string.timeline_show_all, timeline.cases.size))
            }
        }
        if (timeline.other.isNotEmpty()) {
            Text(
                stringResource(R.string.timeline_other),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp).testTag("timeline_other"),
            )
            timeline.other.forEach { EventRow(it, kindLabel, onClick = { onOpenDocument(it.documentId) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaseCard(
    case: TimelineCaseUi,
    kindLabel: (String) -> String,
    startExpanded: Boolean,
    onOpenDocument: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var expanded by rememberSaveable(case.caseId) { mutableStateOf(startExpanded) }
    var menuOpen by rememberSaveable(case.caseId) { mutableStateOf(false) }
    var renaming by rememberSaveable(case.caseId) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("timeline_case_${case.caseId}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClickLabel = stringResource(if (expanded) R.string.timeline_collapse else R.string.timeline_expand),
                        role = Role.Button,
                    ) { expanded = !expanded }
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(case.title, style = MaterialTheme.typography.titleSmall)
                val who = case.organisationName?.let { stringResource(R.string.timeline_case_from, it) }
                    ?: case.personNames.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.timeline_case_for, it.joinToString(", ")) }
                who?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusChip(case.status)
                    Text(
                        pluralStringResource(R.plurals.timeline_letters, case.letterCount, case.letterCount),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                Text(
                    stringResource(R.string.timeline_latest, kindLabel(case.latest.kindId), FriendlyDate.text(epochDay(case.latest.eventDate))),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("timeline_case_menu_${case.caseId}")) {
                    Icon(PamIcons.More, contentDescription = stringResource(R.string.timeline_case_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.timeline_rename)) },
                        onClick = {
                            menuOpen = false
                            renaming = true
                        },
                        modifier = Modifier.testTag("timeline_rename_item"),
                    )
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                case.events.forEach { EventRow(it, kindLabel, onClick = { onOpenDocument(it.documentId) }) }
            }
        }
    }
    if (renaming) RenameDialog(case.title, onDismiss = { renaming = false }) { title ->
        renaming = false
        onRename(case.caseId, title)
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timeline_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.timeline_rename_label)) },
                modifier = Modifier.fillMaxWidth().testTag("timeline_rename_field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("timeline_rename_save")) {
                Text(stringResource(R.string.timeline_rename_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.timeline_rename_cancel)) } },
    )
}

/**
 * One dated event: its kind, date and title with a source icon. Tapping opens its letter ([onClick]); a null [onClick] is the plain,
 * not-tappable line (the letter being looked at).
 */
@Composable
internal fun EventRow(event: TimelineEventUi, kindLabel: (String) -> String, onClick: (() -> Unit)?, marked: Boolean = false) {
    val source = stringResource(sourceLabel(event.source))
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("timeline_event_${event.id}")
    val tappable = if (onClick != null) {
        base.clickable(onClickLabel = stringResource(R.string.timeline_open_letter), role = Role.Button, onClick = onClick)
    } else {
        base
    }
    Row(
        modifier = tappable.padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(sourceIcon(event.source), contentDescription = source, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.timeline_latest, kindLabel(event.kindId), FriendlyDate.text(epochDay(event.eventDate))),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(event.title, style = MaterialTheme.typography.bodyMedium.byContentDirection())
            event.context?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (marked) {
                Text(
                    stringResource(R.string.timeline_this_letter),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun sourceLabel(source: EventSource): Int = when (source) {
    EventSource.DOCUMENT -> R.string.timeline_source_letter
    EventSource.ACTION -> R.string.timeline_source_action
    EventSource.SYSTEM -> R.string.timeline_source_system
    EventSource.USER -> R.string.timeline_source_user
}

private fun sourceIcon(source: EventSource): ImageVector = when (source) {
    EventSource.DOCUMENT -> PamIcons.Documents
    EventSource.ACTION -> PamIcons.Done
    EventSource.SYSTEM -> PamIcons.Waiting
    EventSource.USER -> PamIcons.Person
}

/**
 * Where a matter stands. The status is told by its word and its icon as well as by its colour, so it reads without telling the
 * colours apart.
 */
@Composable
fun StatusChip(status: CaseStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (background, content) = when (status) {
        CaseStatus.OPEN -> scheme.secondaryContainer to scheme.onSecondaryContainer
        CaseStatus.APPROVED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        CaseStatus.REJECTED -> scheme.errorContainer to scheme.onErrorContainer
        CaseStatus.CLOSED -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    val label = stringResource(statusLabel(status))
    val description = stringResource(R.string.timeline_status_description, label)
    Surface(
        modifier = modifier.testTag("timeline_status_${status.name}"),
        shape = RoundedCornerShape(8.dp),
        color = background,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(statusIcon(status), contentDescription = description, modifier = Modifier.size(14.dp), tint = content)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun statusLabel(status: CaseStatus): Int = when (status) {
    CaseStatus.OPEN -> R.string.timeline_status_open
    CaseStatus.APPROVED -> R.string.timeline_status_approved
    CaseStatus.REJECTED -> R.string.timeline_status_rejected
    CaseStatus.CLOSED -> R.string.timeline_status_closed
}

private fun statusIcon(status: CaseStatus): ImageVector = when (status) {
    CaseStatus.OPEN -> PamIcons.Waiting
    CaseStatus.APPROVED -> PamIcons.Done
    CaseStatus.REJECTED -> PamIcons.Error
    CaseStatus.CLOSED -> PamIcons.Close
}

/**
 * "Part of: <matter>" on a letter: the matter's title and status, with the earlier and later letters of the matter behind a toggle
 * (five, then "Show all"). Tapping the title opens the matter on the timeline ([onOpenCase], given the profile to show it on, or
 * null when none is known: then the title is plain).
 *
 * The user can rename the matter from here ([onRename]) and move the letter to another matter, a new one or none ([onMove]); a row
 * given neither has no menu.
 */
@Composable
fun DocumentCaseRow(
    ui: DocumentCaseUi,
    kindLabel: (String) -> String,
    onOpenCase: (profileId: String, caseId: String) -> Unit,
    onOpenDocument: (documentId: String) -> Unit,
    modifier: Modifier = Modifier,
    onRename: ((caseId: String, title: String) -> Unit)? = null,
    onMove: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(ui.caseId) { mutableStateOf(false) }
    var showAll by rememberSaveable(ui.caseId) { mutableStateOf(false) }
    var menuOpen by rememberSaveable(ui.caseId) { mutableStateOf(false) }
    var renaming by rememberSaveable(ui.caseId) { mutableStateOf(false) }
    val target = ui.openProfileId
    Card(
        modifier = modifier.fillMaxWidth().testTag("document_case_row"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val head = Modifier.weight(1f).let {
                if (target != null) {
                    it.clickable(onClickLabel = stringResource(R.string.timeline_part_of_open), role = Role.Button) { onOpenCase(target, ui.caseId) }
                } else {
                    it
                }
            }
            Row(
                modifier = head.heightIn(min = 48.dp).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.timeline_part_of, ui.title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                StatusChip(ui.status)
            }
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("document_case_toggle")) {
                Icon(
                    if (expanded) PamIcons.ExpandLess else PamIcons.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.timeline_history_hide else R.string.timeline_history_show),
                )
            }
            if (onRename != null || onMove != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("document_case_menu")) {
                        Icon(PamIcons.More, contentDescription = stringResource(R.string.timeline_case_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timeline_rename)) },
                                onClick = {
                                    menuOpen = false
                                    renaming = true
                                },
                                modifier = Modifier.testTag("document_case_rename_item"),
                            )
                        }
                        if (onMove != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.timeline_move_case)) },
                                onClick = {
                                    menuOpen = false
                                    onMove()
                                },
                                modifier = Modifier.testTag("document_case_move_item"),
                            )
                        }
                    }
                }
            }
        }
        if (expanded) {
            val shown = if (showAll) ui.events else ui.events.take(TIMELINE_VISIBLE)
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.forEach { event ->
                    val current = event.documentId == ui.currentDocumentId
                    EventRow(event, kindLabel, onClick = if (current) null else ({ onOpenDocument(event.documentId) }), marked = current)
                }
                if (ui.events.size > TIMELINE_VISIBLE) {
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.testTag("document_case_show_all")) {
                        Text(if (showAll) stringResource(R.string.timeline_show_less) else stringResource(R.string.timeline_show_all, ui.events.size))
                    }
                }
            }
        }
    }
    if (renaming && onRename != null) RenameDialog(ui.title, onDismiss = { renaming = false }) { title ->
        renaming = false
        onRename(ui.caseId, title)
    }
}
