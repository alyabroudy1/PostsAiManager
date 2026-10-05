package com.postsaimanager.core.designsystem.component

import android.text.format.DateFormat
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import com.postsaimanager.core.model.ProcessingState
import com.postsaimanager.core.model.ProcessingStage
import com.postsaimanager.core.model.SourceType
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

/** The one date chip, the "n to check" count and the action badge, wrapping rather than overflowing a narrow row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(item: DocumentListItem) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (item.status as? DocumentListStatus.NeedsReview)?.let { review ->
            Text(
                text = pluralStringResource(R.plurals.doc_row_to_check, review.count, review.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        DateChip(item.dateChip)
        if (item.openActionCount > 0) ActionBadge()
    }
}

@Composable
private fun DateChip(chip: DocumentDateChip) {
    val urgent = chip.kind == DocumentDateChip.Kind.DUE && chip.urgency != DocumentDateChip.Urgency.NORMAL
    val text = when {
        chip.kind == DocumentDateChip.Kind.DUE && chip.urgency == DocumentDateChip.Urgency.OVERDUE ->
            stringResource(R.string.doc_row_overdue)
        chip.kind == DocumentDateChip.Kind.DUE -> stringResource(R.string.doc_row_due, formatChipDate(chip.date))
        else -> formatChipDate(chip.date)
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

/** Day and month in the user's own order ("15.10." or "10/15"), with the year only when it is not this year. */
@Composable
private fun formatChipDate(date: LocalDate): String {
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val skeleton = if (date.year == LocalDate.now().year) "dMM" else "dMMy"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).format(date)
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
