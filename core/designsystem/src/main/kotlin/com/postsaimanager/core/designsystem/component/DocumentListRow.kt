package com.postsaimanager.core.designsystem.component

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Scale
import coil3.size.Size
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.designsystem.theme.PamAmber
import com.postsaimanager.core.designsystem.theme.PamGreen
import com.postsaimanager.core.designsystem.theme.PamRed
import com.postsaimanager.core.designsystem.theme.PamTheme
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.DocumentDateChip
import com.postsaimanager.core.model.DocumentListItem
import com.postsaimanager.core.model.DocumentListStatus
import com.postsaimanager.core.model.DocumentStatus
import com.postsaimanager.core.model.PersonTag
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.SourceType
import java.io.File
import java.time.LocalDate

private val ThumbnailWidth = 56.dp
private val ThumbnailHeight = 72.dp

/**
 * One row of a document list, shared by Home's "Recent documents" and the Documents screen so the two
 * cannot drift apart. It renders a [DocumentListItem] and decides nothing: the use case that built the
 * item chose the status, the date and the badge.
 *
 * @param runningState non-null only when this is the document the pipeline is reading now; it turns the
 *   status icon into a progress ring.
 * @param onFavoriteClick when set, the row shows a toggle; when null it shows a plain heart for a favorite.
 */
@Composable
fun DocumentListRow(
    item: DocumentListItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    runningState: ProcessingState.Running? = null,
    onFavoriteClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PageThumbnail(path = item.firstPagePath)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = documentDisplayTitle(item.document),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusIndicator(item.status, runningState)
                }
                partiesText(item.sender, item.addressee)?.let { parties ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = parties,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                MetaChips(item)
            }
            if (onFavoriteClick != null) {
                IconButton(onClick = onFavoriteClick) { FavoriteIcon(item.document.isFavorite, stringResource(R.string.doc_row_toggle_favorite)) }
            } else if (item.document.isFavorite) {
                Spacer(Modifier.width(8.dp))
                FavoriteIcon(true, stringResource(R.string.doc_row_favorite))
            }
        }
    }
}

@Composable
private fun FavoriteIcon(isFavorite: Boolean, description: String) {
    Icon(
        imageVector = if (isFavorite) PamIcons.Favorite else PamIcons.FavoriteOutlined,
        contentDescription = description,
        tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * Page 1, decoded at the thumbnail's own size (never the full-resolution scan) and cached by Coil at
 * that size. The placeholder icon stays behind the image, so it shows while loading and when the file
 * is missing.
 */
@Composable
private fun PageThumbnail(path: String?) {
    val context = LocalContext.current
    val widthPx = with(LocalDensity.current) { ThumbnailWidth.roundToPx() }
    val heightPx = with(LocalDensity.current) { ThumbnailHeight.roundToPx() }
    Box(
        modifier = Modifier
            .size(ThumbnailWidth, ThumbnailHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PamIcons.Documents,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(28.dp),
        )
        if (path != null) {
            val request = remember(path, widthPx, heightPx) {
                ImageRequest.Builder(context)
                    .data(File(path))
                    .size(Size(widthPx, heightPx))
                    .scale(Scale.FILL)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.size(ThumbnailWidth, ThumbnailHeight),
            )
        }
    }
}

@Composable
private fun StatusIndicator(status: DocumentListStatus, runningState: ProcessingState.Running?) {
    val size = Modifier.size(18.dp)
    when (status) {
        DocumentListStatus.Waiting -> Icon(
            PamIcons.Waiting, stringResource(R.string.doc_row_status_waiting), size,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DocumentListStatus.Processing -> {
            val description = stringResource(R.string.doc_row_status_processing)
            val modifier = size.semantics { contentDescription = description }
            if (runningState != null) {
                CircularProgressIndicator(progress = { runningState.progress.coerceIn(0f, 1f) }, modifier = modifier, strokeWidth = 2.dp)
            } else {
                CircularProgressIndicator(modifier = modifier, strokeWidth = 2.dp)
            }
        }
        is DocumentListStatus.NeedsReview -> {
            val description = pluralStringResource(R.plurals.doc_row_to_check, status.count, status.count)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(PamAmber, CircleShape)
                    .semantics { contentDescription = description },
            )
        }
        DocumentListStatus.Ready -> Icon(PamIcons.Done, stringResource(R.string.doc_row_status_ready), size, tint = PamGreen)
        DocumentListStatus.Failed -> Icon(PamIcons.Error, stringResource(R.string.doc_row_status_failed), size, tint = PamRed)
    }
}

/**
 * The action badge, the one date chip, the "n to check" count, the person chips and the document type, in that order (what the person
 * has to do first, who it concerns, then what kind of document it is), wrapping rather than overflowing a narrow row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(item: DocumentListItem) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (item.openActionCount > 0) ActionBadge()
        DateChip(item.dateChip)
        (item.status as? DocumentListStatus.NeedsReview)?.let { review ->
            Text(
                text = pluralStringResource(R.plurals.doc_row_to_check, review.count, review.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        PersonChips(item.people)
        DocumentTypeLabels.of(item.typeId)?.let { TypeChip(stringResource(it)) }
    }
}

/**
 * The document's type as a small neutral tag, the shape and size of the other chips and in the colour of a plain date, so it never reads
 * as "action needed".
 */
@Composable
private fun TypeChip(label: String) {
    ChipSurface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Up to two person chips, then "+n" for the rest. */
@Composable
private fun PersonChips(people: List<PersonTag>) {
    val split = PersonChipSplit.of(people)
    split.shown.forEach { PersonChip(it) }
    if (split.hidden > 0) {
        val description = pluralStringResource(R.plurals.doc_row_people_more_description, split.hidden, split.hidden)
        ChipSurface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Text(
                text = stringResource(R.string.doc_row_people_more, split.hidden),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * A person icon and "You" or the person's short name: the model decided the document is for or about them. The same shape and size as
 * [ActionBadge], in the secondary colour so it never reads as "action needed".
 */
@Composable
private fun PersonChip(person: PersonTag) {
    val text = if (person.isMe) stringResource(R.string.doc_row_concerns_you_chip) else person.displayName
    val description = if (person.isMe) {
        stringResource(R.string.doc_row_concerns_you)
    } else {
        stringResource(R.string.doc_row_concerns_person, person.displayName)
    }
    ChipSurface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = PamIcons.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** The shape, size and padding every badge of the meta line shares. */
@Composable
private fun ChipSurface(color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(6.dp), color = color, modifier = modifier) {
        Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) { content() }
    }
}

@Composable
private fun DateChip(chip: DocumentDateChip) {
    val urgent = chip.kind == DocumentDateChip.Kind.DUE && chip.urgency != DocumentDateChip.Urgency.NORMAL
    val today = LocalDate.now()
    val friendly = FriendlyDate.of(chip.date, today)
    val dated = FriendlyDate.text(chip.date, today)
    val text = when {
        chip.kind != DocumentDateChip.Kind.DUE -> dated
        chip.urgency == DocumentDateChip.Urgency.OVERDUE -> stringResource(R.string.doc_row_overdue_since, dated)
        friendly.day == FriendlyDate.Day.TODAY -> stringResource(R.string.doc_row_due_today)
        friendly.day == FriendlyDate.Day.TOMORROW -> stringResource(R.string.doc_row_due_tomorrow)
        else -> stringResource(R.string.doc_row_due, dated)
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (urgent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ActionBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text = stringResource(R.string.doc_row_action_needed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "From X · For Y", or whichever side exists; null when neither does. */
@Composable
private fun partiesText(sender: String?, addressee: String?): String? = when {
    sender != null && addressee != null -> stringResource(R.string.doc_row_from_for, sender, addressee)
    sender != null -> stringResource(R.string.doc_row_from, sender)
    addressee != null -> stringResource(R.string.doc_row_for, addressee)
    else -> null
}

// ── Previews: one per status, plus the date and badge variants ──

private fun previewItem(
    status: DocumentListStatus,
    documentStatus: DocumentStatus = DocumentStatus.EXTRACTED,
    sender: String? = "Stadtwerke Musterstadt GmbH",
    addressee: String? = "Familie Beispiel",
    chip: DocumentDateChip = DocumentDateChip(DocumentDateChip.Kind.LETTER, LocalDate.of(2026, 9, 10)),
    actions: Int = 0,
    title: String = "Jahresabrechnung Strom 2025",
    people: List<PersonTag> = emptyList(),
    typeId: String? = "official_letter",
) = DocumentListItem(
    document = Document(
        id = "preview", title = title, status = documentStatus, sourceType = SourceType.CAMERA,
        createdAt = 0L, modifiedAt = 0L,
    ),
    firstPagePath = null,
    sender = sender,
    addressee = addressee,
    status = status,
    dateChip = chip,
    openActionCount = actions,
    people = people,
    typeId = typeId,
)

@Composable
private fun PreviewColumn(content: @Composable () -> Unit) {
    PamTheme {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewWaiting() = PreviewColumn {
    DocumentListRow(previewItem(DocumentListStatus.Waiting, DocumentStatus.QUEUED, null, null), onClick = {})
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewProcessing() = PreviewColumn {
    DocumentListRow(
        previewItem(DocumentListStatus.Processing, DocumentStatus.PROCESSING, null, null),
        onClick = {},
        runningState = ProcessingState.Running("preview", ProcessingStage.READ, 0.4f, 1, 3),
    )
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewNeedsReview() = PreviewColumn {
    DocumentListRow(
        previewItem(
            DocumentListStatus.NeedsReview(2),
            chip = DocumentDateChip(DocumentDateChip.Kind.DUE, LocalDate.of(2026, 10, 15)),
            actions = 1,
        ),
        onClick = {},
    )
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewReady() = PreviewColumn {
    DocumentListRow(previewItem(DocumentListStatus.Ready, addressee = null), onClick = {}, onFavoriteClick = {})
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewFailed() = PreviewColumn {
    DocumentListRow(
        previewItem(DocumentListStatus.Failed, DocumentStatus.FAILED, null, null, title = "Scanned 3 pages"),
        onClick = {},
    )
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewUrgentAndOverdue() = PreviewColumn {
    DocumentListRow(
        previewItem(
            DocumentListStatus.Ready,
            chip = DocumentDateChip(DocumentDateChip.Kind.DUE, LocalDate.of(2026, 10, 2), DocumentDateChip.Urgency.SOON),
            actions = 1,
        ),
        onClick = {},
    )
    DocumentListRow(
        previewItem(
            DocumentListStatus.NeedsReview(1),
            sender = "A very long sender name that cannot possibly fit on a single line of a phone",
            chip = DocumentDateChip(DocumentDateChip.Kind.DUE, LocalDate.of(2026, 9, 20), DocumentDateChip.Urgency.OVERDUE),
            actions = 1,
        ),
        onClick = {},
    )
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewPeople() = PreviewColumn {
    val me = PersonTag("me", "Erika", isMe = true)
    val maria = PersonTag("maria", "Maria", isMe = false)
    DocumentListRow(previewItem(DocumentListStatus.Ready, people = listOf(me), actions = 1), onClick = {})
    DocumentListRow(previewItem(DocumentListStatus.Ready, people = listOf(maria)), onClick = {})
    DocumentListRow(
        previewItem(
            DocumentListStatus.Ready,
            people = listOf(me, maria, PersonTag("amir", "Amir", isMe = false)),
            actions = 1,
        ),
        onClick = {},
    )
}

@Preview(showBackground = true, widthDp = 380)
@Composable
private fun PreviewFriendlyDates() = PreviewColumn {
    val today = LocalDate.now()
    DocumentListRow(
        previewItem(
            DocumentListStatus.Ready,
            chip = DocumentDateChip(DocumentDateChip.Kind.DUE, today.plusDays(1), DocumentDateChip.Urgency.SOON),
            actions = 1,
        ),
        onClick = {},
    )
    DocumentListRow(
        previewItem(DocumentListStatus.Ready, chip = DocumentDateChip(DocumentDateChip.Kind.LETTER, today.minusYears(1))),
        onClick = {},
    )
}
