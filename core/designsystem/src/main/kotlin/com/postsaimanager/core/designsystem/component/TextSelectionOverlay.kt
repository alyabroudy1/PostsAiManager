package com.postsaimanager.core.designsystem.component

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.postsaimanager.core.designsystem.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How far from a finger a long press still finds text: forgiving, so a press next to a letter's edge works. */
private val TouchRadius = 28.dp

private val HandleRadius = 11.dp
private val HandleTouchSize = 44.dp

/**
 * The touch half of text selection, as an editor does it:
 *  - a long press picks the nearest word (within [TouchRadius] of the finger, converted through the current zoom), and keeping the
 *    finger down extends the selection from that word to the word nearest the finger;
 *  - a tap clears the selection; a double tap is handed on for zoom.
 * Pinch and pan are left alone (they are handled by the page itself), and the handles are a separate overlay.
 */
@Composable
internal fun Modifier.textSelectionGestures(
    layout: PageTextLayout,
    viewport: PageViewport,
    selection: TextSelection?,
    onSelectionChange: (TextSelection?) -> Unit,
    onDragging: (Boolean) -> Unit,
    onDoubleTap: (Offset) -> Unit,
): Modifier {
    val currentSelection by rememberUpdatedState(selection)
    val currentOnChange by rememberUpdatedState(onSelectionChange)
    return this
        .pointerInput(layout) {
            detectTapGestures(
                // Handled by the drag detector below; present so a long press is not also counted as a tap.
                onLongPress = {},
                onTap = { if (currentSelection != null) currentOnChange(null) },
                onDoubleTap = onDoubleTap,
            )
        }
        .pointerInput(layout) {
            var anchor: TextSelection? = null
            detectDragGesturesAfterLongPress(
                onDragStart = { press ->
                    val page = viewport.toPage(press)
                    val fit = viewport.fitted()
                    val hit = if (page != null && fit != null) {
                        layout.hitTest(
                            page.x, page.y,
                            radiusX = TouchRadius.toPx() / (fit.shownWidth * viewport.scale),
                            radiusY = TouchRadius.toPx() / (fit.shownHeight * viewport.scale),
                        )
                    } else null
                    anchor = hit?.let(layout::wordAt)
                    anchor?.let {
                        currentOnChange(it)
                        onDragging(true)
                    }
                },
                onDrag = { change, _ ->
                    val from = anchor
                    val page = viewport.toPage(change.position)
                    if (from != null && page != null && !layout.isEmpty) {
                        val caret = layout.caretAt(page.x, page.y).coerceAtMost(layout.text.lastIndex)
                        layout.wordAt(caret)?.let { word ->
                            currentOnChange(TextSelection(min(from.start, word.start), max(from.end, word.end)))
                        }
                    }
                    change.consume()
                },
                onDragEnd = { onDragging(false) },
                onDragCancel = { onDragging(false) },
            )
        }
}

/**
 * The selection's two teardrop handles and its floating toolbar (Copy, Select all, Share), in screen space so they keep their size at
 * any zoom while following the text through [viewport]. Nothing when there is no selection.
 */
@Composable
internal fun TextSelectionOverlay(
    layout: PageTextLayout,
    viewport: PageViewport,
    selection: TextSelection?,
    onSelectionChange: (TextSelection?) -> Unit,
    dragging: Boolean,
    onDragging: (Boolean) -> Unit,
) {
    if (selection == null) return
    val anchors = layout.handleAnchors(selection) ?: return
    val (startAnchor, endAnchor) = anchors
    val startTip = viewport.toScreen(startAnchor.x, startAnchor.bottom) ?: return
    val endTip = viewport.toScreen(endAnchor.x, endAnchor.bottom) ?: return
    val startMid = viewport.toScreen(startAnchor.x, (startAnchor.top + startAnchor.bottom) / 2f) ?: return
    val endMid = viewport.toScreen(endAnchor.x, (endAnchor.top + endAnchor.bottom) / 2f) ?: return
    val currentSelection by rememberUpdatedState(selection)

    SelectionHandle(
        isStart = true,
        tip = startTip,
        lineMid = startMid,
        layout = layout,
        viewport = viewport,
        onCaret = { caret ->
            val s = currentSelection ?: return@SelectionHandle
            onSelectionChange(TextSelection(caret.coerceIn(0, s.end - 1), s.end))
        },
        onDragging = onDragging,
    )
    SelectionHandle(
        isStart = false,
        tip = endTip,
        lineMid = endMid,
        layout = layout,
        viewport = viewport,
        onCaret = { caret ->
            val s = currentSelection ?: return@SelectionHandle
            onSelectionChange(TextSelection(s.start, caret.coerceIn(s.start + 1, layout.text.length)))
        },
        onDragging = onDragging,
    )
    if (!dragging) {
        val startTop = viewport.toScreen(startAnchor.x, startAnchor.top) ?: return
        SelectionToolbar(
            above = startTop,
            below = endTip,
            centreX = (startTip.x + endTip.x) / 2f,
            boxSize = viewport.box,
            layout = layout,
            selection = selection,
            onSelectAll = { onSelectionChange(layout.all()) },
        )
    }
}

/**
 * One teardrop. Its pointed corner touches the caret at [tip]; dragging moves the caret to the character nearest the finger, which is
 * tracked from the line's middle ([lineMid]) by the finger's own movement, so the thumb under the handle does not cover the target.
 */
@Composable
private fun SelectionHandle(
    isStart: Boolean,
    tip: Offset,
    lineMid: Offset,
    layout: PageTextLayout,
    viewport: PageViewport,
    onCaret: (Int) -> Unit,
    onDragging: (Boolean) -> Unit,
) {
    val color = MaterialTheme.colorScheme.primary
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { HandleRadius.toPx() }
    val touchPx = with(androidx.compose.ui.platform.LocalDensity.current) { HandleTouchSize.toPx() }
    val currentMid by rememberUpdatedState(lineMid)
    val currentOnCaret by rememberUpdatedState(onCaret)
    // The circle hangs below the tip, to the side its pointed corner is on the other side of.
    val centre = Offset(tip.x + if (isStart) -radiusPx else radiusPx, tip.y + radiusPx)
    Canvas(
        modifier = Modifier
            .offset { IntOffset((centre.x - touchPx / 2f).roundToInt(), (centre.y - touchPx / 2f).roundToInt()) }
            .size(HandleTouchSize)
            .pointerInput(isStart, layout) {
                var target = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        target = currentMid
                        onDragging(true)
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        target += delta
                        viewport.toPage(target)?.let { currentOnCaret(layout.caretAt(it.x, it.y)) }
                    },
                    onDragEnd = { onDragging(false) },
                    onDragCancel = { onDragging(false) },
                )
            },
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color, radius = radiusPx, center = c)
        // The square corner that points at the text: top right for the start handle, top left for the end handle.
        val cornerLeft = if (isStart) c.x else c.x - radiusPx
        drawRect(color, Offset(cornerLeft, c.y - radiusPx), Size(radiusPx, radiusPx))
    }
}

@Composable
private fun SelectionToolbar(
    above: Offset,
    below: Offset,
    centreX: Float,
    boxSize: IntSize,
    layout: PageTextLayout,
    selection: TextSelection,
    onSelectAll: () -> Unit,
) {
    val context = LocalContext.current
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    val handleRoom = with(androidx.compose.ui.platform.LocalDensity.current) { (HandleRadius * 2 + 8.dp).toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Above the selection when there is room, else under the end handle.
    val y = if (above.y - size.height - gap >= 0f) above.y - size.height - gap else below.y + handleRoom + gap
    val x = (centreX - size.width / 2f).coerceIn(0f, max(0f, boxSize.width - size.width.toFloat()))
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .onSizeChanged { size = it },
    ) {
        Row(modifier = Modifier.padding(horizontal = 4.dp)) {
            TextButton(onClick = { copySelection(context, layout.textOf(selection)) }) { Text(stringResource(R.string.page_preview_select_copy)) }
            TextButton(onClick = onSelectAll) { Text(stringResource(R.string.page_preview_select_all)) }
            TextButton(onClick = { shareText(context, layout.textOf(selection)) }) { Text(stringResource(R.string.page_preview_select_share)) }
        }
    }
}

private fun copySelection(context: Context, text: String) {
    copyScannedText(context, "OCR Text", text)
    // Android 13+ shows its own clipboard confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.page_preview_select_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
