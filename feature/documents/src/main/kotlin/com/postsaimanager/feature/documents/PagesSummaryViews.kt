package com.postsaimanager.feature.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.icon.PamIcons

/**
 * The one compact card under the page images: the document's title, a short summary with its source badge, who it is from and
 * who it is to. Or, while there is nothing to say, one honest line: reading, AI not installed (with Install), or failed.
 */
@Composable
internal fun PagesSummaryCard(
    title: String,
    summary: PagesSummary,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
    /** The pencil beside the title (and a tap on it): renames the letter. Null: the title is plain. */
    onEditTitle: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.pages_about_heading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            when (summary.state) {
                PagesSummaryState.READY -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f).let { if (onEditTitle != null) it.clickable(onClick = onEditTitle) else it },
                        )
                        if (onEditTitle != null) {
                            IconButton(onClick = onEditTitle, modifier = Modifier.testTag("title_edit")) {
                                Icon(PamIcons.Edit, contentDescription = stringResource(R.string.title_edit), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    val text = summary.summaryText ?: summary.templateArgs?.let { templateSummaryText(context, it) }
                    if (text != null) {
                        Text(
                            stringResource(summaryBadge(summary.summarySource)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                    }
                    summary.from?.let { PartyLine(stringResource(R.string.pages_from, it)) }
                    summary.to?.let { to ->
                        val name = when (to) {
                            PagesRecipient.You -> stringResource(R.string.pages_to_you)
                            is PagesRecipient.Named -> to.name
                        }
                        PartyLine(stringResource(R.string.pages_to, name))
                    }
                }
                PagesSummaryState.READING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {},
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.pages_reading), style = MaterialTheme.typography.bodyMedium)
                }
                PagesSummaryState.AI_NOT_INSTALLED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.pages_ai_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onInstall) { Text(stringResource(R.string.pages_ai_install)) }
                }
                PagesSummaryState.FAILED -> Text(
                    stringResource(R.string.pages_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PartyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics(mergeDescendants = true) {})
}

/** One page's recognized text. */
internal data class PageText(val pageNumber: Int, val text: String)

/**
 * "Show recognized text ▾": the raw text of each page, collapsed until asked for, selectable so it can be copied. A page without
 * text is left out; with no text at all the section is not drawn.
 */
@Composable
internal fun RecognizedTextSection(
    pages: List<PageText>,
    modifier: Modifier = Modifier,
    /** "Correct the text" under each page: the user fixes what the recognizer got wrong. Null: the text is read-only. */
    onEdit: ((pageNumber: Int) -> Unit)? = null,
) {
    if (pages.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val label = stringResource(if (expanded) R.string.pages_hide_text else R.string.pages_show_text)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    pages.forEach { page ->
                        Column {
                            if (pages.size > 1) {
                                Text(
                                    stringResource(R.string.pages_text_page, page.pageNumber),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(page.text, style = MaterialTheme.typography.bodySmall)
                            if (onEdit != null) {
                                TextButton(onClick = { onEdit(page.pageNumber) }, modifier = Modifier.testTag("page_text_edit_${page.pageNumber}")) {
                                    Icon(PamIcons.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Text(stringResource(R.string.page_text_edit), modifier = Modifier.padding(start = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
