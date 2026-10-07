package com.postsaimanager.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.postsaimanager.core.model.PreviewPage

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Longest side a page is decoded at: sharp enough to read when zoomed, without holding a full camera frame. */
private const val PAGE_DECODE_PX = 2400

/** The marker colour: amber reads on white paper whatever the app theme is. */
private val HighlightColor = Color(0xFFFFC107)

/** How a page is currently zoomed and panned inside its box, and how its image fits there. */
@Stable
internal class PageViewport {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var box by mutableStateOf(IntSize.Zero)
    var imageSize by mutableStateOf(Size.Zero)

    /** The page layer's fit inside the box, or null until the image's size is known. */
    fun fitted(): FittedPage? =
        if (imageSize.width > 0f && imageSize.height > 0f && box.width > 0 && box.height > 0) {
            FittedPage(box.width.toFloat(), box.height.toFloat(), imageSize.width, imageSize.height)
        } else null

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = box.width * (s - 1f) / 2f
        val maxY = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** Double-tap: back to fit when zoomed, else zoom in around the tap. */
    fun toggleZoom(tap: Offset) {
        if (scale > 1.01f) {
            scale = 1f
            offset = Offset.Zero
        } else {
            val center = Offset(box.width / 2f, box.height / 2f)
            scale = DOUBLE_TAP_ZOOM
            offset = clamp((tap - center) * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM)
        }
    }
}

/**
 * One page image with pinch-zoom, pan and double-tap zoom, the cited passage marked, and its text selectable by the platform's own
 * text selection through an invisible [OcrTextLayer].
 *
 * The image, its marker and the text layer share one `graphicsLayer`, so they stay glued together at any zoom. Pan and pinch are only
 * consumed while zoomed or with two fingers down, so a single finger on an unzoomed page falls through to the pager; and a drag the
 * text selection already consumed (a long-press drag) never also pans.
 */
@Composable
internal fun ZoomablePage(
    page: PreviewPage,
    description: String,
    showAllText: Boolean,
) {
    val viewport = remember { PageViewport() }
    val lines = remember(page.textBlocks) { pageTextLines(page.textBlocks) }
    val tint = MaterialTheme.colorScheme.primary

    val context = LocalContext.current
    val request = remember(page.imagePath) {
        ImageRequest.Builder(context).data(page.imagePath).size(PAGE_DECODE_PX).build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport.box = it }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = viewport::toggleZoom) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val fingers = event.changes.count { it.pressed }
                        // The last event of a gesture has no finger down: its centroid is Unspecified (NaN), which
                        // would send the page's offset to NaN and blank it. Nothing to apply then.
                        val centroid = event.calculateCentroid(useCurrent = true)
                        val consumedByChild = event.changes.any { it.isConsumed }
                        if (centroid.isSpecified && (fingers > 1 || (viewport.scale > 1.01f && !consumedByChild))) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val focus = centroid - Offset(viewport.box.width / 2f, viewport.box.height / 2f)
                            val newScale = (viewport.scale * zoom).coerceIn(1f, MAX_ZOOM)
                            val moved = focus + pan - (focus - viewport.offset) * (newScale / viewport.scale)
                            viewport.scale = newScale
                            viewport.offset = if (newScale <= 1f) Offset.Zero else viewport.clamp(moved, newScale)
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
                    scaleX = viewport.scale
                    scaleY = viewport.scale
                    translationX = viewport.offset.x
                    translationY = viewport.offset.y
                },
        ) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { viewport.imageSize = it.painter.intrinsicSize },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            )
            val imageSize = viewport.imageSize
            if ((page.highlights.isNotEmpty() || showAllText) && imageSize.width > 0f && imageSize.height > 0f) {
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
                    if (showAllText) {
                        lines.forEach { l ->
                            val b = l.bounds
                            drawRect(tint.copy(alpha = 0.2f), Offset(fit.left(b), fit.top(b)), Size(b.width * fit.shownWidth, b.height * fit.shownHeight))
                        }
                    }
                }
            }
            val fit = viewport.fitted()
            if (fit != null && lines.isNotEmpty()) OcrTextLayer(lines, fit)
        }
    }
}

private fun PointerInputChange.positionChanged(): Boolean = position != previousPosition
