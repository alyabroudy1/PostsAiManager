package com.postsaimanager.feature.documents

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.component.ReportAnswerButton
import com.postsaimanager.core.designsystem.component.ReportAnswerDialog
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.contacts.LetterContacts
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.FamilyPresentation
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.SectionKind
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentNote
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.FieldAlternative
import com.postsaimanager.core.model.SummarySource
import com.postsaimanager.core.model.TextBounds
import com.postsaimanager.core.model.ValueSource

/**
 * What a person can do to a row, from its ✓ ✎ ✕ buttons and the Ignored footer. Block-level actions pass every row of the
 * block. The screen binds these to the ViewModel; a test binds them to recorders.
 */
internal class FieldActions(
    val confirm: (List<String>) -> Unit,
    val ignore: (List<String>) -> Unit,
    val restore: (String) -> Unit,
    val edit: (ExtractedData) -> Unit,
)

/** The room a floating action button takes at the bottom of a list: its height (56dp), its margin (16dp) and a gap (16dp). */
private val FAB_CLEARANCE = 88.dp

/** "This is a form to fill in · Help me fill it", shown on the Extracted tab of a form. */
@Composable
internal fun FillFormCard(onFillForm: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.fill_form_card_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onFillForm) { Text(stringResource(R.string.fill_form_card_action)) }
        }
    }
}

/** Which question the family picker answers: "Change type" (no re-read) or "Read again as …" (a fresh read). */
private enum class PickerMode { CHANGE_TYPE, READ_AGAIN }

// ═══════════════════════════════════════════════════════════
// The tab
// ═══════════════════════════════════════════════════════════

/**
 * The Extracted tab, the essentials first: the header (family chip, topics, confidence), the summary card, what the reader has to
 * do, who it is from and for, the key information, then "All details" (collapsed) with every other field and the "Ignored" footer.
 * "Check these" and the review button count the essential lines only.
 *
 * @param selfName the name on the "Me" profile: an addressee with that name reads "You"
 */
@Composable
internal fun ExtractedTab(
    document: Document,
    data: List<ExtractedData>,
    summaryComing: Boolean,
    actions: FieldActions,
    onAddClick: () -> Unit,
    onReprocess: () -> Unit,
    onChangeFamily: (String) -> Unit,
    onReadAgainAs: (String) -> Unit,
    /** "Confirm n confident" / "Confirm all": given the ids of the essential rows they work on, so "All details" is never confirmed in bulk. */
    onConfirmConfident: (List<String>) -> Unit,
    onConfirmAll: (List<String>) -> Unit,
    onUpdateField: (fieldId: String, name: String, value: String) -> Unit,
    onUpdateSummary: (String) -> Unit,
    onShowOnPage: (page: Int?, bbox: TextBounds?) -> Unit,
    /** "Help me fill it": opens the document chat with the form fill started. Offered as a card on a form only; null hides it. */
    onFillForm: (() -> Unit)? = null,
    /** "What the assistant remembers": the document's durable notes and what the user may do with them. */
    notes: List<DocumentNote> = emptyList(),
    noteActions: NoteActions = NoteActions(),
    selfName: String? = null,
    /** The letter's contact and the organisation's current one: the "From" chip and what a "contact" action offers. */
    letterContacts: LetterContacts = LetterContacts(),
    /** The chip: opens the organisation page at the contact (organisation id, contact id). */
    onContactClick: (organisationId: String, contactId: String) -> Unit = { _, _ -> },
    onCall: (phone: String) -> Unit = {},
    onEmail: (address: String) -> Unit = {},
) {
    // Kept across a rotation: the row being edited is stored as its id and resolved from the data, so the sheet shows the latest row.
    var editingFieldId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingField = editingFieldId?.let { id -> data.firstOrNull { it.id == id } }
    var editingSummary by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<PickerMode?>(null) }
    var showAllExtras by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var ignoredExpanded by remember { mutableStateOf(false) }

    val offerContact = letterContacts.current ?: letterContacts.letterContact
    val presentation = remember(document, data, showAllExtras, summaryComing, selfName, offerContact) {
        ExtractedPresenter.present(document, data, showAllExtras, summaryComing, selfName, offerContact)
    }
    // The ✎ of any row opens the same sheet.
    val rowActions = remember(actions) {
        FieldActions(actions.confirm, actions.ignore, actions.restore) { editingFieldId = it.id }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (data.isEmpty()) {
            EmptyExtracted(onAddClick)
        } else {
            // The list state is created here, once; the rows keep stable keys, so a review-state change keeps the scroll position.
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                // The bottom clears the floating add button the screen puts over the tab (the screen's insets are already applied).
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + FAB_CLEARANCE),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "reread") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onReprocess, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_re_extract), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // A form is offered, never pushed: one card, and the AI chat does the rest.
                if (onFillForm != null && document.extractionType == ExtractionSchema.FORM_APPLICATION.id) {
                    item(key = "fill_form") { FillFormCard(onFillForm) }
                }

                // 5.4: the assistant did not see the whole document — a long letter's layout had to be cut to fit the
                // extraction budget. Subtle (a caption, not a warning colour) because it is informational.
                val pagesRead = document.extractionPagesRead
                val totalPages = document.extractionTotalPages
                if (pagesRead != null && totalPages != null && pagesRead < totalPages) {
                    item(key = "partial") {
                        Text(
                            LocalContext.current.resources.getQuantityString(R.plurals.extraction_partial_notice, totalPages, pagesRead, totalPages),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }

                item(key = "header") {
                    HeaderRow(
                        header = presentation.header,
                        onChangeType = { picker = PickerMode.CHANGE_TYPE },
                        onReadAgain = { picker = PickerMode.READ_AGAIN },
                    )
                }

                if (!presentation.summary.isEmpty || presentation.summary.summaryComing) {
                    item(key = "summary") {
                        SummaryCardView(presentation.summary, onEditSummary = { editingSummary = true })
                    }
                }

                item(key = "review") {
                    ReviewButton(presentation.review, onConfirmConfident, onConfirmAll)
                }

                // Only what the AI judged essential is counted here; an uncertain line is marked where it stands, so nothing is listed twice.
                val essentials = presentation.essentials
                if (presentation.checkCount > 0) {
                    item(key = "check-header") {
                        Column {
                            SectionHeader(stringResource(R.string.section_check, presentation.checkCount))
                            Text(
                                stringResource(R.string.essentials_check_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (essentials.actions.isNotEmpty()) item(key = "essentials-actions") { ActionsCard(essentials.actions, rowActions, onCall, onEmail) }
                if (!essentials.parties.isEmpty) item(key = "essentials-parties") { PartiesCard(essentials.parties, rowActions, letterContacts, onContactClick) }
                if (essentials.subject != null || essentials.keyInfo.isNotEmpty()) {
                    item(key = "essentials-key") { KeyInfoCard(essentials.subject, essentials.keyInfo, rowActions) }
                }

                item(key = "memory") { DocumentMemoryCard(notes, noteActions) }

                if (presentation.detailCount > 0) {
                    item(key = "all-details") {
                        CollapsibleHeader(
                            title = stringResource(R.string.section_all_details, presentation.detailCount),
                            expanded = detailsExpanded,
                            onToggle = { detailsExpanded = !detailsExpanded },
                        )
                    }
                    if (detailsExpanded) {
                        presentation.sections.forEach { section ->
                            item(key = "section-${section.kind}") { SectionTitle(stringResource(sectionTitle(section.kind))) }
                            items(section.items, key = { "${section.kind}-" + it.rows.first().id }) { ItemView(it, rowActions) }
                        }
                        if (presentation.extraCount > 0) {
                            item(key = "section-extras") { SectionTitle(stringResource(R.string.section_other_details)) }
                            items(presentation.extras, key = { "extra-" + it.id }) { FieldRow(it, rowActions) }
                            if (presentation.hiddenExtras > 0) {
                                item(key = "extras-show-all") {
                                    TextButton(onClick = { showAllExtras = true }) {
                                        Text(stringResource(R.string.other_details_show_all, presentation.hiddenExtras))
                                    }
                                }
                            } else if (showAllExtras) {
                                item(key = "extras-show-fewer") {
                                    TextButton(onClick = { showAllExtras = false }) { Text(stringResource(R.string.other_details_show_fewer)) }
                                }
                            }
                        }
                    }
                }

                if (presentation.ignored.isNotEmpty()) {
                    item(key = "ignored-header") {
                        CollapsibleHeader(
                            title = stringResource(R.string.section_ignored, presentation.ignored.size),
                            expanded = ignoredExpanded,
                            onToggle = { ignoredExpanded = !ignoredExpanded },
                        )
                    }
                    if (ignoredExpanded) {
                        items(presentation.ignored, key = { "ignored-" + it.id }) { IgnoredRow(it, actions.restore) }
                    }
                }
            }
        }
    }

    editingField?.let { field ->
        EditFieldSheet(
            field = field,
            onDismiss = { editingFieldId = null },
            onSave = { name, value ->
                onUpdateField(field.id, name, value)
                editingFieldId = null
            },
            onShowOnPage = onShowOnPage,
        )
    }

    if (editingSummary) {
        val context = LocalContext.current
        EditSummaryDialog(
            initial = presentation.summary.summaryText
                ?: presentation.summary.templateArgs?.let { templateSummaryText(context, it) }.orEmpty(),
            onDismiss = { editingSummary = false },
            onSave = {
                onUpdateSummary(it)
                editingSummary = false
            },
        )
    }

    picker?.let { mode ->
        FamilyPickerDialog(
            mode = mode,
            current = presentation.header.typeId,
            onDismiss = { picker = null },
            onPick = { familyId ->
                picker = null
                if (mode == PickerMode.CHANGE_TYPE) onChangeFamily(familyId) else onReadAgainAs(familyId)
            },
        )
    }
}

@Composable
private fun EmptyExtracted(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(PamIcons.AiModel, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(8.dp))
        Text(stringResource(R.string.extracted_empty_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text(stringResource(R.string.extracted_empty_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAddClick) {
            Icon(PamIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.extracted_add_manually))
        }
    }
}

private fun sectionTitle(kind: SectionKind): Int = when (kind) {
    SectionKind.RECIPIENT_BLOCK -> R.string.section_recipient
    SectionKind.SENDER_BLOCK -> R.string.section_sender
    SectionKind.MERCHANT -> R.string.section_merchant
    SectionKind.PARTIES -> R.string.group_parties
    SectionKind.TEXT -> R.string.group_text
    SectionKind.ACTION -> R.string.section_action
    SectionKind.PAYMENT -> R.string.section_payment
    SectionKind.DATES -> R.string.group_dates
    SectionKind.REFERENCES -> R.string.group_references
    SectionKind.EXTRAS -> R.string.section_other_details
}

// ═══════════════════════════════════════════════════════════
// Header: family chip, topics, confidence
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderRow(header: Header, onChangeType: () -> Unit, onReadAgain: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val typeLabel = SlotLabels.type(header.typeId)?.let { stringResource(it) } ?: stringResource(R.string.header_type_unknown)
    val confidence = header.confidence
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            AssistChip(
                onClick = { menu = true },
                label = { Text(stringResource(R.string.header_family_chip, typeLabel)) },
                leadingIcon = if (confidence != null) ({ ConfidenceDot(confidence) }) else null,
                modifier = Modifier.semantics { contentDescription = typeLabel },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.header_change_type)) },
                    onClick = { menu = false; onChangeType() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.header_read_again_as)) },
                    onClick = { menu = false; onReadAgain() },
                )
            }
        }
        header.topics.forEach { topic ->
            // Read-only: a topic is what the document is about, not something to edit here.
            val label = SlotLabels.topic(topic)?.let { stringResource(it) } ?: topic
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun ConfidenceDot(confidence: Confidence) {
    val (color, description) = when (confidence) {
        Confidence.HIGH -> MaterialTheme.colorScheme.primary to R.string.confidence_high
        Confidence.MEDIUM -> MaterialTheme.colorScheme.tertiary to R.string.confidence_medium
        Confidence.LOW -> MaterialTheme.colorScheme.error to R.string.confidence_low
    }
    val text = stringResource(description)
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color).semantics { contentDescription = text })
}

@Composable
private fun FamilyPickerDialog(mode: PickerMode, current: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (mode == PickerMode.CHANGE_TYPE) R.string.picker_change_title else R.string.picker_read_again_title))
        },
        text = {
            LazyColumn {
                items(ExtractionSchema.DEFAULT.families, key = { it.id }) { family ->
                    val label = SlotLabels.type(family.id)?.let { stringResource(it) } ?: family.id
                    // The current family is marked even when the stored id is a legacy one that stands for it.
                    val selected = family.id == FamilyPresentation.familyId(current)
                    Row(
                        modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize().clickable { onPick(family.id) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) Icon(PamIcons.Done, contentDescription = stringResource(R.string.picker_current), modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ═══════════════════════════════════════════════════════════
// Summary card
// ═══════════════════════════════════════════════════════════

/**
 * The document at a glance: the composed title and the summary with a badge for where it came from; the pencil edits the summary.
 * Who it is from and for, what to do and the key facts are the cards below it.
 */
@Composable
internal fun SummaryCardView(card: SummaryCard, onEditSummary: () -> Unit) {
    val context = LocalContext.current
    var reporting by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.titleArgs?.let { args -> composedTitleText(context, args) }?.let { title ->
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            val summary = card.summaryText ?: card.templateArgs?.let { templateSummaryText(context, it) }
            if (summary != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(summaryBadge(card.summarySource)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    // Only a model-written summary is an AI answer; a template or the user's own text is not.
                    if (card.summarySource == SummarySource.MODEL || card.summarySource == null) {
                        ReportAnswerButton(onClick = { reporting = true }, iconSize = 18)
                    }
                    IconButton(onClick = onEditSummary) {
                        Icon(PamIcons.Edit, contentDescription = stringResource(R.string.summary_edit), modifier = Modifier.size(18.dp))
                    }
                }
                if (reporting) {
                    ReportAnswerDialog(answerText = summary, onDismiss = { reporting = false })
                }
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }
            // The summary is written after the result is shown (the reading's second stage): say so until it lands.
            if (summary == null && card.summaryComing) {
                Text(
                    stringResource(R.string.card_summary_coming),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun summaryBadge(source: SummarySource?): Int = when (source) {
    SummarySource.TEMPLATE -> R.string.card_summary_from_fields
    SummarySource.USER -> R.string.card_summary_yours
    SummarySource.MODEL, null -> R.string.card_ai_summary
}

@Composable
private fun EditSummaryDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.summary_edit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.summary_edit_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
            )
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The words of [args], a composed title (`TitleComposer`'s `[family, sender, subject]`), from the family's string resource. */
internal fun composedTitleText(context: Context, args: List<String>): String? =
    ComposedTitle.render(args) { id -> SlotLabels.type(id)?.let(context::getString) }

/** The sentence of [args], a template summary (`SummaryFacts.templateArgs`), put together from string resources. */
internal fun templateSummaryText(context: Context, args: List<String>): String? =
    TemplateSummary.render(
        args = args,
        familyLabel = { id -> SlotLabels.type(id)?.let(context::getString) },
        format = { piece, params -> context.getString(summaryPieceRes(piece), *params.toTypedArray()) },
    )

private fun summaryPieceRes(piece: SummaryPiece): Int = when (piece) {
    SummaryPiece.INTRO -> R.string.summary_intro
    SummaryPiece.INTRO_FROM -> R.string.summary_intro_from
    SummaryPiece.INTRO_FOR -> R.string.summary_intro_for
    SummaryPiece.INTRO_FROM_FOR -> R.string.summary_intro_from_for
    SummaryPiece.AMOUNT_DUE -> R.string.summary_amount_due
    SummaryPiece.AMOUNT -> R.string.summary_amount
    SummaryPiece.DUE -> R.string.summary_due
    SummaryPiece.SUBJECT -> R.string.summary_subject
}

// ═══════════════════════════════════════════════════════════
// Review button
// ═══════════════════════════════════════════════════════════

/**
 * "Confirm n confident" while uncertain essential lines remain (they stay for the person to check), "Confirm all" once none do.
 * It acts on the essential lines only, by their ids; the rows of "All details" are never confirmed in bulk. Hidden when nothing is open.
 */
@Composable
private fun ReviewButton(review: ReviewSummary, onConfirmConfident: (List<String>) -> Unit, onConfirmAll: (List<String>) -> Unit) {
    when (review.mode) {
        ConfirmMode.NONE -> Unit
        // At the start of the row, not the end: the screen's floating add button sits at the end and would cover the label at the top of the list.
        ConfirmMode.CONFIDENT -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Button(onClick = { onConfirmConfident(review.confidentIds) }) { Text(stringResource(R.string.review_confirm_confident, review.confidentOpen)) }
        }
        ConfirmMode.ALL -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Button(onClick = { onConfirmAll(review.openIds) }) { Text(stringResource(R.string.review_confirm_all)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Rows
// ═══════════════════════════════════════════════════════════

@Composable
private fun ItemView(item: DetailItem, actions: FieldActions) {
    when (item) {
        is FieldItem -> FieldRow(item.row, actions)
        is AddressBlock -> AddressBlockCard(item, actions)
    }
}

/**
 * One extracted field.
 *
 * Three states are visually distinct, because they mean different things to the person reading them:
 *  - **Worth checking** — the extractor was unsure, or now disagrees with the value here: outlined in the error colour.
 *  - **Confirmed or edited** — the person accepted or wrote it: collapsed to one line, and a re-read leaves it.
 *  - **Machine, confident** — plain.
 *
 * Every row ends in one overflow menu with Confirm, Edit and Ignore; a confirmed row shows the ✓ as a mark instead of a Confirm entry.
 */
@Composable
internal fun FieldRow(field: ExtractedData, actions: FieldActions) {
    val label = fieldLabelText(field)
    val settled = field.isSettled
    val uncertain = field.isUncertain
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = if (uncertain) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        colors = CardDefaults.cardColors(
            containerColor = when {
                uncertain -> MaterialTheme.colorScheme.errorContainer
                settled -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (settled) {
                    // One line: "Label: value", with the value taking what is left.
                    Text(
                        text = "$label: ${field.fieldValue}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(field.fieldValue, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            // Confidence is the extractor's opinion of its own reading; once a person has set the value it says nothing.
                            stringResource(R.string.field_confidence, (field.confidence * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RowMenu(
                    label = label,
                    confirmed = settled,
                    onConfirm = { actions.confirm(listOf(field.id)) },
                    onEdit = { actions.edit(field) },
                    onIgnore = { actions.ignore(listOf(field.id)) },
                )
            }

            // The disagreement, spelled out: the previous reading is what makes it actionable.
            if (field.hasUnreviewedMachineChange && field.machineValue != null) {
                MachineChangeNotice(field, actions)
            } else if (uncertain) {
                Text(
                    stringResource(R.string.field_worth_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

/**
 * The row's actions in one overflow menu (Confirm, Edit, Ignore), each named after the row for screen readers; a confirmed row shows
 * the ✓ as a mark and has no Confirm entry.
 */
@Composable
private fun RowMenu(label: String, confirmed: Boolean, onConfirm: () -> Unit, onEdit: () -> Unit, onIgnore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (confirmed) {
            Box(modifier = Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
                Icon(
                    PamIcons.Done,
                    contentDescription = stringResource(R.string.field_confirmed, label),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        OverflowMenu(label) { close ->
            if (!confirmed) MenuItem(R.string.action_menu_confirm, R.string.action_confirm_field, label) { close(); onConfirm() }
            MenuItem(R.string.action_menu_edit, R.string.action_edit_field, label) { close(); onEdit() }
            MenuItem(R.string.action_menu_ignore, R.string.action_ignore_field, label) { close(); onIgnore() }
        }
    }
}

/**
 * The recipient or the sender as one block: the name, then the address lines, with the parts the extraction was unsure
 * of underlined. ✓ and ✕ apply to the whole block; tapping a part edits that part. A block that is all confirmed collapses
 * to one line that opens on a tap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddressBlockCard(block: AddressBlock, actions: FieldActions) {
    val title = stringResource(if (block.role == PartyRole.SENDER) R.string.section_sender else R.string.section_recipient)
    val settled = block.isSettled
    var expanded by remember(settled) { mutableStateOf(!settled) }
    val uncertain = block.uncertainRows.isNotEmpty()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = if (uncertain) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        colors = CardDefaults.cardColors(
            containerColor = when {
                uncertain -> MaterialTheme.colorScheme.errorContainer
                settled -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!expanded) {
                    val oneLine = block.shownRows.joinToString(", ") { it.fieldValue.lines().joinToString(" ") }
                    Text(
                        text = "$title: $oneLine",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { expanded = true }.minimumInteractiveComponentSize(),
                    )
                } else {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).clickable(enabled = settled) { expanded = false },
                    )
                }
                // Block-level actions: the whole block is confirmed or ignored; a part is edited by tapping it.
                if (settled) {
                    Box(modifier = Modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            PamIcons.Done,
                            contentDescription = stringResource(R.string.field_confirmed, title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    IconButton(onClick = { actions.confirm(block.rows.filter { !it.isSettled }.map { it.id }) }) {
                        Icon(PamIcons.Done, contentDescription = stringResource(R.string.action_confirm_field, title), modifier = Modifier.size(22.dp))
                    }
                }
                IconButton(onClick = { actions.ignore(block.rows.map { it.id }) }) {
                    Icon(PamIcons.Close, contentDescription = stringResource(R.string.action_ignore_field, title), modifier = Modifier.size(20.dp))
                }
            }
            if (expanded) {
                block.nameRow?.let { name ->
                    PartText(name, actions, FontWeight.SemiBold)
                }
                block.lines.forEach { line ->
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.parts.forEach { part -> PartText(part, actions) }
                    }
                }
                if (block.lines.isEmpty()) {
                    // Nothing was told apart: the lines as printed are all there is, and they can still be edited.
                    block.rawRow?.let { raw -> PartText(raw, actions) }
                }
            }
        }
    }
}

/** One part of an address: tap to edit it. Underlined and tinted when the extraction was unsure of it. */
@Composable
private fun PartText(part: ExtractedData, actions: FieldActions, weight: FontWeight = FontWeight.Normal) {
    val label = fieldLabelText(part)
    val uncertain = part.isUncertain
    val editLabel = stringResource(R.string.action_edit_field, label)
    val description = if (uncertain) {
        stringResource(R.string.part_description_uncertain, label, part.fieldValue)
    } else {
        stringResource(R.string.part_description, label, part.fieldValue)
    }
    Text(
        text = part.fieldValue,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = weight,
        textDecoration = if (uncertain) TextDecoration.Underline else null,
        color = if (uncertain) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clickable(onClickLabel = editLabel) { actions.edit(part) }
            .minimumInteractiveComponentSize()
            .semantics {
                contentDescription = description
                onClick(label = editLabel) { actions.edit(part); true }
            },
    )
}

@Composable
private fun IgnoredRow(field: ExtractedData, onRestore: (String) -> Unit) {
    val label = fieldLabelText(field)
    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label: ${field.fieldValue}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val description = stringResource(R.string.action_restore_field, label)
        TextButton(
            onClick = { onRestore(field.id) },
            modifier = Modifier.semantics { contentDescription = description },
        ) { Text(stringResource(R.string.action_restore)) }
    }
}

// ═══════════════════════════════════════════════════════════
// Edit sheet
// ═══════════════════════════════════════════════════════════

/**
 * Edits a value: prefilled, with the readings the letter also offered as chips (value and page) and "Show on page", which
 * opens the page preview with the field's box marked. Saving goes through `updateExtractedField`, so the row becomes the
 * person's own.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EditFieldSheet(
    field: ExtractedData,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit,
    onShowOnPage: (page: Int?, bbox: TextBounds?) -> Unit,
) {
    // A name that is a key (a found value's, or an extra's bare slot key) is edited as the words the screen shows for it;
    // left unchanged, the stored key is kept. A slot-labelled row keeps its name: its label is rendered from the key.
    val keyed = SlotLabels.found(field.slotKey) != null || SlotLabels.extraKeyName(field.fieldName) != null
    val nameEditable = SlotLabels.labelFor(field) == null
    val shownName = fieldLabelText(field)
    var name by rememberSaveable(field.id) { mutableStateOf(if (keyed) shownName else field.fieldName) }
    var value by rememberSaveable(field.id) { mutableStateOf(field.fieldValue) }
    // The picked alternative as its position (an alternative itself is not saved): resolved against the field's own list.
    var chosenIndex by rememberSaveable(field.id) { mutableStateOf<Int?>(null) }
    val chosen: FieldAlternative? = chosenIndex?.let { field.alternatives.getOrNull(it) }
    val page = chosen?.page ?: field.pageNumber
    val bbox = chosen?.bbox ?: field.bbox

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.edit_title, shownName), style = MaterialTheme.typography.titleMedium)
            if (nameEditable) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.edit_name_label)) },
                )
            }
            OutlinedTextField(
                value = value, onValueChange = { value = it }, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.edit_value_label)) },
            )
            if (field.alternatives.isNotEmpty()) {
                Text(stringResource(R.string.edit_alternatives), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    field.alternatives.forEachIndexed { index, alt ->
                        AssistChip(
                            onClick = { chosenIndex = index; value = alt.value },
                            label = {
                                Text(
                                    alt.page?.let { stringResource(R.string.edit_alternative_on_page, alt.value, it) } ?: alt.value,
                                )
                            },
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onShowOnPage(page, bbox) }, enabled = page != null) {
                    Text(stringResource(R.string.edit_show_on_page))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Button(
                    onClick = {
                        val savedName = if (!nameEditable) field.fieldName else if (keyed && name.trim() == shownName) field.fieldName else name.trim()
                        onSave(savedName, value.trim())
                    },
                    enabled = value.isNotBlank() && name.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// Shared pieces
// ═══════════════════════════════════════════════════════════

@Composable
internal fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}

/** A section heading that opens and closes its content; reads as a heading with its state ("Collapsed") for screen readers. */
@Composable
private fun CollapsibleHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 12.dp)
            .semantics(mergeDescendants = true) { heading(); stateDescription = state },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A field label key ([ExtractedData.labelKey]) in the user's language: a slot's string, else the name as it was stored. */
@Composable
internal fun labelText(key: String): String {
    val res = SlotLabels.slot(key)
    if (res != null) return stringResource(res)
    SlotLabels.found(key)?.let { return stringResource(it.res, it.number) }
    SlotLabels.extraKeySlot(key)?.let { return stringResource(it) }
    return SlotLabels.extraKeyName(key) ?: key
}

/** A field's label: its slot's string, a found value's words, else the name as stored (a person's, or an extra's printed label). */
@Composable
internal fun fieldLabelText(field: ExtractedData): String {
    SlotLabels.labelFor(field)?.let { return stringResource(it) }
    SlotLabels.found(field.slotKey)?.let { return stringResource(it.res, it.number) }
    if (field.source == ValueSource.MACHINE) SlotLabels.extraKeySlot(field.fieldName)?.let { return stringResource(it) }
    return SlotLabels.extraKeyName(field.fieldName) ?: field.fieldName
}
