package com.postsaimanager.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import kotlin.math.roundToInt

/** Glyph height as a share of the line box: a line's recognised box spans ascender to descender, the glyphs a little less. */
private const val GLYPH_SHARE = 0.8f

/**
 * The page's recognised text as an invisible layer exactly over the page image, selectable with the platform's own text selection
 * (long-press, teardrop handles, magnifier, floating Copy / Select all / Share toolbar, drag across lines), as a PDF reader does on a
 * scanned page with an OCR text layer.
 *
 * Each line is a transparent `Text` sitting on its recognised box: the font is sized from the box's height and stretched
 * horizontally (the style's `scaleX`, part of the text's own layout) so the glyphs span the box's width, which puts every character
 * close to where it is on the paper. All lines share ONE [SelectionContainer], and are emitted in reading order.
 *
 * Everything here is in REAL (already zoomed) coordinates: the page is zoomed by layout (see [ZoomablePage]), [fit] is the fit in the
 * zoomed box, and nothing is drawn through a transform, so the selection's handles, magnifier and toolbar are placed where the text is.
 *
 * @param fit the page's fit inside this layer's (zoomed) box, in pixels
 */
@Composable
internal fun OcrTextLayer(lines: List<PageTextLine>, fit: FittedPage, selection: PageSelection) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val colors = remember(primary) { TextSelectionColors(handleColor = primary, backgroundColor = primary.copy(alpha = 0.35f)) }
    val platformToolbar = LocalTextToolbar.current
    val toolbar = remember(platformToolbar, selection) { TrackingTextToolbar(platformToolbar, selection) }
    // Only lines that can be selected are composed: a blank text or a sub-pixel box would register a selectable with nothing to select.
    val shown = remember(lines, fit) { selectableLines(lines, fit) }
    CompositionLocalProvider(LocalTextSelectionColors provides colors, LocalTextToolbar provides toolbar) {
        // A new key replaces the container, which is how a selection is cleared. The colours are part of the key because they key every
        // text's selection controller: a controller replaced under a text that keeps its measured layout never gets the layout again
        // (Compose hands it over only when the layout changes), and a long-press then finds no selectable ("SelectionLayout must not be empty").
        // The zoom is part of the key too: a new scale lays every text out afresh, so the press guard starts over with it.
        key(selection.generation, colors, shown.size, fit.shownWidth) {
        val laidOut = remember { LaidOutCount(shown.size) }
        SelectionContainer {
            Box(Modifier.fillMaxSize().then(laidOut.guard())) {
                shown.forEach { (id, line) ->
                  key(id) {
                    val widthPx = line.bounds.width * fit.shownWidth
                    val heightPx = line.bounds.height * fit.shownHeight
                    val style = remember(heightPx, density) {
                        TextStyle(
                            color = Color.Transparent,
                            fontSize = TextUnit(heightPx * GLYPH_SHARE / density.fontScale / density.density, TextUnitType.Sp),
                            lineHeight = TextUnit(heightPx / density.fontScale / density.density, TextUnitType.Sp),
                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                        )
                    }
                    val naturalWidth = remember(line.text, style) {
                        measurer.measure(line.text, style, softWrap = false, maxLines = 1).size.width.coerceAtLeast(1)
                    }
                    val stretched = remember(style, widthPx, naturalWidth) {
                        style.copy(textGeometricTransform = TextGeometricTransform(scaleX = (widthPx / naturalWidth).coerceAtLeast(0.01f)))
                    }
                    Text(
                        text = line.text,
                        style = stretched,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        onTextLayout = { laidOut.reported(id) },
                        modifier = Modifier
                            .offset { IntOffset(fit.left(line.bounds).roundToInt(), fit.top(line.bounds).roundToInt()) }
                            .wrapContentSize(Alignment.TopStart, unbounded = true),
                    )
                  }
                }
            }
        }
        }
    }
}

/**
 * The lines that get a selectable `Text`, each with a key that is stable for the line (its place in [lines] and its text): non-blank,
 * and with a box of at least one pixel on the page. Pure, so the rule is testable.
 */
internal fun selectableLines(lines: List<PageTextLine>, fit: FittedPage): List<Pair<String, PageTextLine>> =
    lines.mapIndexedNotNull { index, line ->
        val widthPx = line.bounds.width * fit.shownWidth
        val heightPx = line.bounds.height * fit.shownHeight
        if (line.text.isBlank() || widthPx < 1f || heightPx < 1f) null else "$index:${line.text}" to line
    }

/**
 * Safety net for the platform's long-press: a press that lands before every line has been laid out is ignored instead of reaching a
 * text whose selection layout is still empty (which Compose answers with an exception that closes the app). A line reports once it has
 * been measured; [guard] consumes a first touch (before the texts see it) until all lines have.
 */
internal class LaidOutCount(private val expected: Int) {
    private val reportedKeys = HashSet<String>()
    private var ready by mutableStateOf(expected == 0)

    fun reported(key: String) {
        reportedKeys += key
        if (reportedKeys.size >= expected) ready = true
    }

    val isReady: Boolean get() = ready

    fun guard(): Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!ready) down.consume()
        }
    }
}
