package com.postsaimanager.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.FormChip
import com.postsaimanager.core.model.FormChipAction
import com.postsaimanager.core.model.FormField
import com.postsaimanager.core.model.FormMessage
import com.postsaimanager.core.model.FormMessageKind
import com.postsaimanager.core.model.FormText

/**
 * One message of the form conversation in the transcript: a progress or status line, a question with answer chips, or the fill
 * card. [isLatestCard] says whether this is the newest card (older ones fold to their header); [chipsEnabled] whether this is
 * the open question (a tap sends the chip as the answer).
 */
@Composable
internal fun FormMessageItem(
    message: ChatMessage,
    form: FormMessage,
    fillCard: FillCardState?,
    isLatestCard: Boolean,
    chipsEnabled: Boolean,
    onChip: (FormChip, String) -> Unit,
    onShowOnPage: (FormField) -> Unit,
    onCopy: (String) -> Unit,
    onOpenModels: () -> Unit = {},
) {
    val resources = rememberResources()
    val line = FormChatTexts.line(resources, form, message.text)
    // A chip that opens a screen is not an answer: it never reaches the conversation.
    val chipTapped: (FormChip, String) -> Unit = { chip, shown ->
        if (chip.action == FormChipAction.OPEN_MODELS) onOpenModels() else onChip(chip, shown)
    }
    when (form.kind) {
        FormMessageKind.STATUS ->
            if (form.chips.isEmpty()) {
                FormStatusLine(line, working = form.text == FormText.UNDERSTANDING && form.args.firstOrNull() != form.args.getOrNull(1))
            } else {
                FormQuestion(line, form.chips, enabled = true, onChip = chipTapped)
            }
        FormMessageKind.QUESTION -> FormQuestion(line, form.chips, chipsEnabled, chipTapped)
        FormMessageKind.CARD -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (line.isNotBlank()) AssistantBubble(line)
            if (fillCard != null) {
                FillCard(fillCard, expanded = isLatestCard, onShowOnPage = onShowOnPage, onCopy = onCopy)
            }
        }
    }
}

/** A quiet line of what the assistant is doing, with a progress bar while the form is being read. */
@Composable
internal fun FormStatusLine(text: String, working: Boolean) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PamIcons.AiChat, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** The assistant's question and its answer chips; tapping a chip sends it as the user's answer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormQuestion(text: String, chips: List<FormChip>, enabled: Boolean, onChip: (FormChip, String) -> Unit) {
    val resources = rememberResources()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistantBubble(text)
        if (chips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                chips.forEach { chip ->
                    val label = FormChatTexts.chipLabel(resources, chip)
                    val description = resources.getString(R.string.form_chip_description, label)
                    AssistChip(
                        onClick = { onChip(chip, label) },
                        enabled = enabled,
                        label = { Text(label) },
                        modifier = Modifier.semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

/**
 * The fill card, rendered LIVE from the stored fields: label, value, source badge, copy per row, a page chip that opens the page
 * with the field marked, sensitive values masked until tapped, and "Copy all". An older card folds to its progress header.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FillCard(
    state: FillCardState,
    expanded: Boolean,
    onShowOnPage: (FormField) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = rememberResources()
    val progress = state.progress
    val rows = remember(state.fields) { FillCardRows.of(state.fields) }
    // Not saved across a rotation, on purpose: a revealed value is hidden again whenever the screen is recreated.
    var revealed by remember { mutableStateOf(emptySet<String>()) }

    Card(
        modifier = modifier.fillMaxWidth().testTag("fillCard"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(resources.getString(R.string.form_card_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                FormChatTexts.progress(resources, progress.ready, progress.total, progress.needYou, progress.signatures),
                style = MaterialTheme.typography.titleSmall,
            )
            if (!expanded) {
                Text(resources.getString(R.string.form_card_earlier), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            var page = -1
            rows.forEach { row ->
                if (row.field.page != page) {
                    page = row.field.page
                    Text(
                        resources.getString(R.string.form_card_page_heading, page),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    HorizontalDivider()
                }
                FillCardRow(
                    row = row,
                    open = row.field.id == state.openFieldId,
                    revealed = row.field.id in revealed,
                    onToggleReveal = { revealed = if (row.field.id in revealed) revealed - row.field.id else revealed + row.field.id },
                    onShowOnPage = { onShowOnPage(row.field) },
                    onCopy = { onCopy(FormChatTexts.valueText(resources, row.field)) },
                )
            }
            TextButton(
                onClick = { onCopy(FillCardRows.plainText(state.fields) { FormChatTexts.valueText(resources, it) }) },
                enabled = rows.any { it.field.value != null },
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(resources.getString(R.string.form_card_copy_all))
            }
        }
    }
}

@Composable
private fun FillCardRow(
    row: FillRow,
    open: Boolean,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onShowOnPage: () -> Unit,
    onCopy: () -> Unit,
) {
    val resources = rememberResources()
    val field = row.field
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag("row-${field.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                field.labelText,
                style = MaterialTheme.typography.labelMedium,
                color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                field.value != null -> {
                    val full = FormChatTexts.valueText(resources, field)
                    val hidden = row.sensitive && !revealed
                    val toggle = if (hidden) resources.getString(R.string.form_card_reveal) else resources.getString(R.string.form_card_hide)
                    Text(
                        text = if (hidden) row.masked.orEmpty() else full,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = if (row.sensitive) {
                            Modifier.clickable(onClickLabel = toggle, onClick = onToggleReveal).semantics { contentDescription = if (hidden) toggle else full }
                        } else {
                            Modifier
                        },
                    )
                }
                else -> Text(
                    text = when (row.status) {
                        FillRowStatus.SIGNATURE -> resources.getString(R.string.form_card_sign_here)
                        FillRowStatus.BY_HAND -> resources.getString(R.string.form_card_by_hand)
                        FillRowStatus.ALREADY_FILLED -> resources.getString(R.string.form_card_already_filled)
                        else -> resources.getString(R.string.form_card_needs_input)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRowBadges(field, row.status)
        }
        val show = resources.getString(R.string.form_card_show_on_page, field.labelText, field.page)
        SuggestionChip(
            onClick = onShowOnPage,
            label = { Text(resources.getString(R.string.form_card_page, field.page)) },
            modifier = Modifier.semantics { contentDescription = show },
        )
        if (field.value != null) {
            val copy = resources.getString(R.string.form_card_copy_field, field.labelText)
            IconButton(onClick = onCopy, modifier = Modifier.semantics { contentDescription = copy }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The small badges under a value: where it came from, and "to confirm" for a stored value that waits for "still right?". */
@Composable
private fun FlowRowBadges(field: FormField, status: FillRowStatus) {
    val resources = rememberResources()
    val source = FormChatTexts.sourceBadge(resources, field.valueSource)?.takeIf { field.value != null }
    if (source == null && status != FillRowStatus.TO_CONFIRM) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        source?.let { Badge(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) }
        if (status == FillRowStatus.TO_CONFIRM) {
            Badge(resources.getString(R.string.form_card_to_confirm), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun Badge(text: String, container: androidx.compose.ui.graphics.Color, content: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = container) {
        Text(text, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = content)
    }
}
