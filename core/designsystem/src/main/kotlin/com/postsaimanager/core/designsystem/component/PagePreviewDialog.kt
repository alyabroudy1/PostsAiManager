package com.postsaimanager.core.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
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
                    PreviewPager(preview, initialPageIndex, onClose, onOpenDocument, onPageShown)
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

    PreviewHeader(
        title = stringResource(R.string.page_preview_title_page, preview.title, pagerState.currentPage + 1, pages.size),
        onClose = onClose,
    )
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
    // The dialog window is edge to edge and, on some devices (a 3-button bar), is not handed the navigation bar's insets: the bottom of a
    // tall page then sat under the bar. The bar's real height comes from the activity's window; whatever the dialog's own insets
    // already cleared is not added twice.
    val density = LocalDensity.current
    val clearedPx = WindowInsets.navigationBars.getBottom(density)
    val barPx = activityNavigationBarPx(LocalContext.current)
    val extra = with(density) { (barPx - clearedPx).coerceAtLeast(0).toDp() }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding().padding(bottom = extra),
    ) { index ->
        ZoomablePage(
            page = pages[index],
            description = stringResource(R.string.page_preview_page_description, index + 1, pages.size, preview.title),
        )
    }
}

/** The height in pixels of the navigation bar as the hosting activity's window sees it (0 when there is no activity or no bar). */
@Suppress("DEPRECATION")
private fun activityNavigationBarPx(context: Context): Int {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) return c.window?.decorView?.rootWindowInsets?.stableInsetBottom ?: 0
        c = c.baseContext
    }
    return 0
}

@Composable
private fun PreviewHeader(title: String, onClose: () -> Unit) {
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
private fun ZoomablePage(page: PreviewPage, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(Size.Zero) }

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
            .pointerInput(Unit) {
                detectTapGestures(
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
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val fingers = event.changes.count { it.pressed }
                        // The last event of a gesture has no finger down: its centroid is Unspecified (NaN), which
                        // would send the page's offset to NaN and blank it. Nothing to apply then.
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (centroid.isSpecified && (fingers > 1 || scale > 1.01f)) {
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
            if (page.highlights.isNotEmpty() && imageSize.width > 0f && imageSize.height > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val fit = minOf(size.width / imageSize.width, size.height / imageSize.height)
                    val shownW = imageSize.width * fit
                    val shownH = imageSize.height * fit
                    val originX = (size.width - shownW) / 2f
                    val originY = (size.height - shownH) / 2f
                    val pad = 2.dp.toPx()
                    val radius = CornerRadius(4.dp.toPx())
                    page.highlights.forEach { b ->
                        drawRoundRect(
                            color = HighlightColor.copy(alpha = 0.38f),
                            topLeft = Offset(originX + b.left * shownW - pad, originY + b.top * shownH - pad),
                            size = Size(b.width * shownW + 2 * pad, b.height * shownH + 2 * pad),
                            cornerRadius = radius,
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.input.pointer.PointerInputChange.positionChanged(): Boolean =
    position != previousPosition
