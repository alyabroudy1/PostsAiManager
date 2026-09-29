package com.postsaimanager.feature.chat

import androidx.compose.foundation.Canvas
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
import com.postsaimanager.core.designsystem.icon.PamIcons
import com.postsaimanager.core.domain.usecase.DocumentPreview
import com.postsaimanager.core.domain.usecase.PreviewPage

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Longest side a page is decoded at: sharp enough to read when zoomed, without holding a full camera frame. */
private const val PAGE_DECODE_PX = 2400

/** The marker colour: amber reads on white paper whatever the app theme is. */
private val HighlightColor = Color(0xFFFFC107)

/**
 * A full-screen, in-place preview of a cited document, opened on the cited page.
 *
 * A [Dialog] rather than a bottom sheet: back closes it natively, it draws edge to edge, and
 * — the deciding reason — a sheet's own vertical drag would fight panning a zoomed page.
 * It sits on top of the chat rather than replacing it, so the transcript underneath keeps
 * its scroll position untouched.
 */
@Composable
internal fun CitationPreviewDialog(
    state: CitationPreviewState,
    onClose: () -> Unit,
    onOpenDocument: (ChatSource) -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val preview = state.preview
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                if (preview == null) {
                    PreviewHeader(title = state.source.title ?: "Document preview", onClose = onClose)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                "This document can't be previewed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    PreviewPager(state, preview, onClose, onOpenDocument)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.PreviewPager(
    state: CitationPreviewState,
    preview: DocumentPreview,
    onClose: () -> Unit,
    onOpenDocument: (ChatSource) -> Unit,
) {
    val pages = preview.pages
    val pagerState = rememberPagerState(
        initialPage = state.initialPageIndex.coerceIn(0, pages.lastIndex),
        pageCount = { pages.size },
    )
    val current = pages[pagerState.currentPage.coerceIn(0, pages.lastIndex)]

    PreviewHeader(
        title = "${preview.title} · Page ${pagerState.currentPage + 1} of ${pages.size}",
        onClose = onClose,
    )
    // Above the pager, not below it: a Dialog window does not always get navigation-bar
    // insets, and a bottom button would sit under the 3-button bar.
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        OutlinedButton(
            onClick = {
                onOpenDocument(
                    state.source.copy(
                        documentId = preview.documentId,
                        pageNumber = current.pageNumber,
                        title = preview.title,
                    ),
                )
            },
        ) { Text("Open document") }
    }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
    ) { index ->
        ZoomablePage(
            page = pages[index],
            description = "Page ${index + 1} of ${pages.size} of ${preview.title}",
        )
    }
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
            Icon(PamIcons.Close, contentDescription = "Close preview")
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
                        if (fingers > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val focus = event.calculateCentroid(useCurrent = true) -
                                Offset(box.width / 2f, box.height / 2f)
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
