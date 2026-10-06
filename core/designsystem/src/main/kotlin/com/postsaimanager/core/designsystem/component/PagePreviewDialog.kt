package com.postsaimanager.core.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.tappableElement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.postsaimanager.core.designsystem.R
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.model.DocumentPreview
import com.postsaimanager.core.model.PreviewPage

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Longest side a page is decoded at: sharp enough to read when zoomed, without holding a full camera frame. */
private const val PAGE_DECODE_PX = 2400

/** The marker colour: amber reads on white paper whatever the app theme is. */
private val HighlightColor = Color(0xFFFFC107)

/**
 * A full-screen, in-place preview of a document's pages, opened on one of them with the passage or field marked.
 * Shared by the chat (a cited passage) and the Extracted tab (the box a field was read from).
 *
 * A [Dialog] rather than a bottom sheet: back closes it natively, it draws edge to edge, and
 * — the deciding reason — a sheet's own vertical drag would fight panning a zoomed page.
 * It sits on top of the screen rather than replacing it, so what is underneath keeps its scroll position untouched.
 *
 * @param title shown while [preview] is not there (loading, or unavailable); the loaded document's own title replaces it
 * @param preview the pages, or null while [loading] or when the document can't be previewed
 * @param initialPageIndex index into [DocumentPreview.pages] to open on
 * @param onOpenDocument called with the page number being viewed when "Open document" is tapped; null hides the button
 * @param onPageShown called with the page number now in view (also on open), so the caller can land on it when the preview closes
 */
@Composable
fun PagePreviewDialog(
    title: String?,
    preview: DocumentPreview?,
    loading: Boolean,
    initialPageIndex: Int,
    onClose: () -> Unit,
    onOpenDocument: ((pageNumber: Int) -> Unit)?,
    onPageShown: ((pageNumber: Int) -> Unit)? = null,
) {
    // Transient: lives and dies with the dialog. Back first leaves select mode, then closes.
    var selecting by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { if (selecting) selecting = false else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // Top and sides here; the bottom is cleared where the pages are drawn (see [dialogBottomInsetPx]), because a Dialog window is
            // not always handed the navigation bar's insets.
            val topAndSides = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal).union(WindowInsets.displayCutout)
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(topAndSides)) {
                if (preview == null || preview.pages.isEmpty()) {
                    PreviewHeader(title = title ?: stringResource(R.string.page_preview_default_title), onClose = onClose)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (loading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                stringResource(R.string.page_preview_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    PreviewPager(preview, initialPageIndex, onClose, onOpenDocument, onPageShown, selecting, { selecting = it })
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PreviewPager(
    preview: DocumentPreview,
    initialPageIndex: Int,
    onClose: () -> Unit,
    onOpenDocument: ((pageNumber: Int) -> Unit)?,
    onPageShown: ((pageNumber: Int) -> Unit)?,
    selecting: Boolean,
    onSelectingChange: (Boolean) -> Unit,
) {
    val pages = preview.pages
    val pagerState = rememberPagerState(
        initialPage = initialPageIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    val current = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]
    LaunchedEffect(pagerState, onPageShown) {
        snapshotFlow { pagerState.currentPage }.collect { index ->
            onPageShown?.invoke(pages[index.coerceIn(0, pages.lastIndex)].pageNumber)
        }
    }

    var selection by remember { mutableStateOf(PageTextSelection()) }
    val canSelect = current.regions.isNotEmpty()
    // The page can't change while selecting (paging is off), but a page that loses its text leaves the mode.
    LaunchedEffect(canSelect) { if (!canSelect) onSelectingChange(false) }
    LaunchedEffect(selecting) { if (!selecting) selection = PageTextSelection() }

    PreviewHeader(
        title = stringResource(R.string.page_preview_title_page, preview.title, pagerState.currentPage + 1, pages.size),
        onClose = onClose,
        selectTextEnabled = canSelect,
        selecting = selecting,
        onToggleSelect = { onSelectingChange(!selecting) },
    )
    if (!canSelect) {
        Text(
            stringResource(R.string.page_preview_select_text_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    // Above the pager, not below it: a Dialog window does not always get navigation-bar
    // insets, and a bottom button would sit under the 3-button bar.
    if (onOpenDocument != null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            OutlinedButton(onClick = { onOpenDocument(current.pageNumber) }) {
                Text(stringResource(R.string.page_preview_open_document))
            }
        }
    }
    val density = LocalDensity.current
    val context = LocalContext.current
    val bottomPx = dialogBottomInsetPx(context)
    HorizontalPager(
        state = pagerState,
        // Paging is off while selecting, so taps and drags on the regions never fight the pager.
        userScrollEnabled = !selecting,
        modifier = Modifier.weight(1f).fillMaxWidth(),
    ) { index ->
        ZoomablePage(
            page = pages[index],
            description = stringResource(R.string.page_preview_page_description, index + 1, pages.size, preview.title),
            selecting = selecting && index == pagerState.currentPage,
            selection = selection,
            onSelectionChange = { selection = it },
            onLongPressText = { regionId ->
                selection = if (regionId != null) PageTextSelection(setOf(regionId)) else PageTextSelection()
                onSelectingChange(true)
            },
        )
    }
    if (selecting) {
        SelectionBar(
            selectedCount = selection.count,
            onSelectAll = { selection = PageTextSelection.all(current.regions.size) },
            onCopy = {
                copyScannedText(context, "OCR Text", selection.text(current.regions))
                // Android 13+ shows its own clipboard confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.page_preview_select_copied, Toast.LENGTH_SHORT).show()
                }
            },
            onDone = { onSelectingChange(false) },
        )
    }
    Spacer(Modifier.height(with(density) { bottomPx.toDp() }))
}

/** "n selected" with Select all, Copy and Done: the accessible path, since the regions themselves are not focusable. */
@Composable
private fun SelectionBar(selectedCount: Int, onSelectAll: () -> Unit, onCopy: () -> Unit, onDone: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                pluralStringResource(R.plurals.page_preview_selected_count, selectedCount, selectedCount),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.page_preview_select_all)) }
            TextButton(onClick = onCopy, enabled = selectedCount > 0) { Text(stringResource(R.string.page_preview_select_copy)) }
            TextButton(onClick = onDone) { Text(stringResource(R.string.page_preview_select_done)) }
        }
    }
}

/**
 * How far up from the bottom of the dialog the pages must stop so they clear the navigation bar. A Dialog window on a 3-button bar
 * (Android 15 and later edge to edge) is not reliably handed the bar's insets, so the largest of what the dialog was handed (the bar, the
 * tappable area, the system bars) and what the hosting activity's own window reports (the bar's size whether or not it is shown)
 * is used: whichever source knows the bar, the pages clear it, and none is a hard-coded size.
 */
@Composable
private fun dialogBottomInsetPx(context: Context): Int {
    val density = LocalDensity.current
    val handed = maxOf(
        WindowInsets.navigationBars.getBottom(density),
        WindowInsets.tappableElement.getBottom(density),
        WindowInsets.systemBars.getBottom(density),
    )
    return maxOf(handed, activityNavigationBarPx(context))
}

/** The navigation bar's height in pixels as the hosting activity's window reports it (0 when there is no activity or no bar). */
private fun activityNavigationBarPx(context: Context): Int {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) {
            val root = c.window?.decorView?.let { ViewCompat.getRootWindowInsets(it) } ?: return 0
            return root.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.tappableElement()).bottom
        }
        c = c.baseContext
    }
    return 0
}

@Composable
private fun PreviewHeader(
    title: String,
    onClose: () -> Unit,
    selectTextEnabled: Boolean? = null,
    selecting: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Null while there are no pages to select on (loading, unavailable): no action at all.
        if (selectTextEnabled != null) {
            TextButton(onClick = onToggleSelect, enabled = selectTextEnabled || selecting) {
                Text(stringResource(if (selecting) R.string.page_preview_select_done else R.string.page_preview_select_text))
            }
        }
        IconButton(onClick = onClose) {
            Icon(PamIcons.Close, contentDescription = stringResource(R.string.page_preview_close))
        }
    }
}

/**
 * One page image with pinch-zoom, pan and double-tap zoom, and the cited passage marked.
 *
 * The image and its highlight layer share one `graphicsLayer`, so the markers stay glued to
 * the text at any zoom. Gestures are only consumed while zoomed or with two fingers down; a
 * single finger on an unzoomed page falls through to the pager, so swiping still turns pages.
 */
@Composable
private fun ZoomablePage(
    page: PreviewPage,
    description: String,
    selecting: Boolean,
    selection: PageTextSelection,
    onSelectionChange: (PageTextSelection) -> Unit,
    onLongPressText: (regionId: Int?) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(Size.Zero) }
    // True from the long press of a drag-select until the finger lifts: the page must not pan underneath it.
    var dragSelecting by remember { mutableStateOf(false) }
    val currentSelection by rememberUpdatedState(selection)
    val currentOnSelectionChange by rememberUpdatedState(onSelectionChange)
    val regionTint = MaterialTheme.colorScheme.primary

    /** The page layer's fit inside the box, or null until the image's size is known. */
    fun fitted(): FittedPage? =
        if (imageSize.width > 0f && imageSize.height > 0f && box.width > 0) {
            FittedPage(box.width.toFloat(), box.height.toFloat(), imageSize.width, imageSize.height)
        } else null

    /** The region under a touch point given in box pixels, undoing the current zoom and pan first; a fingertip's slop is 8dp. */
    fun Density.regionAt(point: Offset): Int? {
        val fit = fitted() ?: return null
        val x = fit.normalisedX(unzoom(point.x, box.width.toFloat(), scale, offset.x))
        val y = fit.normalisedY(unzoom(point.y, box.height.toFloat(), scale, offset.y))
        return RegionHitTest.regionAt(page.regions, x, y, slop = 8.dp.toPx() / (fit.shownWidth * scale))
    }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    val context = LocalContext.current
    val request = remember(page.imagePath) {
        ImageRequest.Builder(context).data(page.imagePath).size(PAGE_DECODE_PX).build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(selecting, page.regions) {
                detectTapGestures(
                    // A tap toggles a region only in select mode; a long press anywhere on a page with text enters it.
                    onTap = if (selecting) { tap ->
                        regionAt(tap)?.let { currentOnSelectionChange(currentSelection.toggle(it)) }
                    } else null,
                    onLongPress = { press -> if (!selecting && page.regions.isNotEmpty()) onLongPressText(regionAt(press)) },
                    onDoubleTap = { tap ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(box.width / 2f, box.height / 2f)
                            scale = DOUBLE_TAP_ZOOM
                            offset = clamp((tap - center) * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM)
                        }
                    },
                )
            }
            .pointerInput(selecting, page.regions) {
                if (!selecting) return@pointerInput
                // A long press then drag selects every region from where it started to where it is, in reading order.
                var anchor: Int? = null
                var base = PageTextSelection()
                detectDragGesturesAfterLongPress(
                    onDragStart = { start ->
                        anchor = regionAt(start)
                        base = currentSelection
                        dragSelecting = true
                        anchor?.let { currentOnSelectionChange(base.withRange(it, it)) }
                    },
                    onDrag = { change, _ ->
                        val from = anchor
                        val to = regionAt(change.position)
                        if (from != null && to != null) currentOnSelectionChange(base.withRange(from, to))
                        change.consume()
                    },
                    onDragEnd = { dragSelecting = false },
                    onDragCancel = { dragSelecting = false },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val fingers = event.changes.count { it.pressed }
                        // The last event of a gesture has no finger down: its centroid is Unspecified (NaN), which
                        // would send the page's offset to NaN and blank it. Nothing to apply then.
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (centroid.isSpecified && (fingers > 1 || (scale > 1.01f && !dragSelecting))) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val focus = centroid - Offset(box.width / 2f, box.height / 2f)
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val moved = focus + pan - (focus - offset) * (newScale / scale)
                            scale = newScale
                            offset = if (newScale <= 1f) Offset.Zero else clamp(moved, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { imageSize = it.painter.intrinsicSize },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            )
            if ((page.highlights.isNotEmpty() || selecting) && imageSize.width > 0f && imageSize.height > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val fit = FittedPage(size.width, size.height, imageSize.width, imageSize.height)
                    val pad = 2.dp.toPx()
                    val radius = CornerRadius(4.dp.toPx())
                    // The marker underneath, so a selection never hides the passage it sits on.
                    page.highlights.forEach { b ->
                        drawRoundRect(
                            color = HighlightColor.copy(alpha = 0.38f),
                            topLeft = Offset(fit.left(b) - pad, fit.top(b) - pad),
                            size = Size(b.width * fit.shownWidth + 2 * pad, b.height * fit.shownHeight + 2 * pad),
                            cornerRadius = radius,
                        )
                    }
                    if (selecting) {
                        // Hairline stays one dp on screen whatever the zoom, since this layer is scaled.
                        val stroke = Stroke(width = 1.dp.toPx() / scale)
                        page.regions.forEachIndexed { i, region ->
                            val b = region.bounds
                            val topLeft = Offset(fit.left(b), fit.top(b))
                            val regionSize = Size(b.width * fit.shownWidth, b.height * fit.shownHeight)
                            if (i in selection.ids) {
                                drawRoundRect(regionTint.copy(alpha = 0.32f), topLeft, regionSize, radius)
                            }
                            drawRoundRect(regionTint.copy(alpha = 0.55f), topLeft, regionSize, radius, style = stroke)
                        }
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChanged(): Boolean =
    position != previousPosition
