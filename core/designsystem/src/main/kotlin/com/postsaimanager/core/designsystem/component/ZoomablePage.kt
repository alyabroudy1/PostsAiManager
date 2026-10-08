package com.postsaimanager.core.designsystem.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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

/** Test tag of a page's box. */
internal const val PAGE_TEST_TAG = "preview_page"

/** Seeds a page's image size where no image is decoded (JVM tests); unspecified in the app, where the loaded image reports it. */
internal val LocalAssumedPageImageSize = staticCompositionLocalOf { Size.Zero }

/** The marker colour: amber reads on white paper whatever the app theme is. */
private val HighlightColor = Color(0xFFFFC107)

/** How a page is currently zoomed and panned inside its box, and how its image fits there. */
@Stable
internal class PageViewport {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var box by mutableStateOf(IntSize.Zero)
    var imageSize by mutableStateOf(Size.Zero)

    /** True while a pinch is changing [scale]; the text layer is not laid out then (see [ZoomablePage]). */
    var zooming by mutableStateOf(false)

    /**
     * The page layer's fit inside its zoomed box (the box times [scale]: the page is zoomed by layout, so this is real window
     * geometry), or null until the image's size is known.
     */
    fun fitted(): FittedPage? =
        if (imageSize.width > 0f && imageSize.height > 0f && box.width > 0 && box.height > 0) {
            FittedPage(box.width * scale, box.height * scale, imageSize.width, imageSize.height)
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
 * Double-tap zoom, and pan and pinch of [viewport]. Pan and pinch are only consumed while zoomed or with two fingers down, so a single
 * finger on an unzoomed page falls through to the pager, and a drag a child already consumed (a text selection's long-press drag)
 * never also pans.
 *
 * A single finger only pans once it has moved past the touch slop, as the platform's own scrolling and transforming do. Consuming from
 * the first pixel of movement cancelled the text layer's long-press (a finger always drifts a little while it is held), which is why
 * selecting text worked only on the unzoomed page, where nothing was panning.
 */
internal fun Modifier.pageGestures(viewport: PageViewport, onTap: () -> Unit = {}): Modifier = this
    .pointerInput(viewport) { detectTapGestures(onDoubleTap = viewport::toggleZoom, onTap = { onTap() }) }
    .pointerInput(viewport) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var slopPassed = false
            var drift = Offset.Zero
            try {
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
                    if (fingers > 1) slopPassed = true
                    if (!slopPassed) {
                        drift += pan
                        if (drift.getDistance() > viewConfiguration.touchSlop) slopPassed = true
                    }
                    if (slopPassed) {
                        val focus = centroid - Offset(viewport.box.width / 2f, viewport.box.height / 2f)
                        val newScale = (viewport.scale * zoom).coerceIn(1f, MAX_ZOOM)
                        val moved = focus + pan - (focus - viewport.offset) * (newScale / viewport.scale)
                        if (newScale != viewport.scale) viewport.zooming = true
                        viewport.scale = newScale
                        viewport.offset = if (newScale <= 1f) Offset.Zero else viewport.clamp(moved, newScale)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
            } finally {
                // The pinch is over: the text layer is laid out again, at the final scale.
                viewport.zooming = false
            }
        }
    }

/**
 * One page image with pinch-zoom, pan and double-tap zoom, the cited passage marked, and its text selectable by the platform's own
 * text selection through an invisible [OcrTextLayer].
 *
 * The page is zoomed by LAYOUT, not by a `graphicsLayer`: [zoomedLayout] measures the content at the box's size times the scale and
 * places it at the pan, so the image, its marker and the text layer are all laid out at their real window coordinates. A
 * `graphicsLayer` only transforms drawing; the selection's handles, magnifier, toolbar and hit testing are placed from layout
 * coordinates and would stay where the unzoomed text is.
 *
 * The text layer holds a lot of text, so it is not re-laid out on every pinch frame: only the image and marker follow the fingers
 * (cheap), the text layer is left out while a pinch changes the scale (so nothing can be selected mid-pinch, and a selection is
 * dropped) and comes back, laid out at the final scale, when the fingers lift. Panning only moves the placement, so the text
 * layer stays. The gestures are [pageGestures]. Back, or a tap on the page, clears a selection first.
 */
@Composable
internal fun ZoomablePage(
    page: PreviewPage,
    description: String,
    viewport: PageViewport = remember { PageViewport() },
    selection: PageSelection = remember { PageSelection() },
) {
    // Back first clears a selection; only with none does it fall through to closing the preview.
    BackHandler(enabled = selection.active) { selection.clear() }
    val assumedImageSize = LocalAssumedPageImageSize.current
    if (assumedImageSize.width > 0f && viewport.imageSize.width <= 0f) viewport.imageSize = assumedImageSize
    val lines = remember(page.textBlocks) { pageTextLines(page.textBlocks) }

    val context = LocalContext.current
    val request = remember(page.imagePath) {
        ImageRequest.Builder(context).data(page.imagePath).size(PAGE_DECODE_PX).build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewport.box = it }
            .testTag(PAGE_TEST_TAG)
            .pageGestures(viewport, onTap = { if (selection.active) selection.clear() }),
    ) {
        Box(modifier = Modifier.zoomedLayout(viewport)) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { viewport.imageSize = it.painter.intrinsicSize },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = description },
            )
            val imageSize = viewport.imageSize
            if (page.highlights.isNotEmpty() && imageSize.width > 0f && imageSize.height > 0f) {
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
                }
            }
            val fit = viewport.fitted()
            if (fit != null && lines.isNotEmpty() && !viewport.zooming) OcrTextLayer(lines, fit, selection)
        }
    }
    // A pinch drops the selection: its handles would be at the old scale.
    LaunchedEffect(viewport.zooming) { if (viewport.zooming) selection.clear() }
}

/**
 * Lays the content out at the incoming size times the viewport's scale, placed where the zoom about the box's centre followed by the
 * pan puts it (the parent clips). The scale is read in measure, the pan in placement only, so a pan never re-measures. Absolute
 * [place], not `placeRelative`: the pan is in screen pixels whatever the layout direction.
 */
private fun Modifier.zoomedLayout(viewport: PageViewport): Modifier = layout { measurable, constraints ->
    val scale = viewport.scale
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(
        Constraints.fixed((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1)),
    )
    layout(width, height) {
        val offset = viewport.offset
        placeable.place(
            IntOffset(
                (width * (1f - scale) / 2f + offset.x).roundToInt(),
                (height * (1f - scale) / 2f + offset.y).roundToInt(),
            ),
        )
    }
}

private fun PointerInputChange.positionChanged(): Boolean = position != previousPosition
