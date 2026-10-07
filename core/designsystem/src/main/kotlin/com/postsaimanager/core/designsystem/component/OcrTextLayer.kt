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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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
 * horizontally ([graphicsLayer] `scaleX`) so the glyphs span the box's width, which puts every character close to where it is on the
 * paper. All lines share ONE [SelectionContainer], and are emitted in reading order. Must be placed inside the page's zoom layer, so
 * the selection follows the image.
 *
 * @param fit the page's fit inside this layer's box (unzoomed pixels)
 */
@Composable
internal fun OcrTextLayer(lines: List<PageTextLine>, fit: FittedPage, selection: PageSelection) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val colors = remember(primary) { TextSelectionColors(handleColor = primary, backgroundColor = primary.copy(alpha = 0.35f)) }
    val platformToolbar = LocalTextToolbar.current
    val toolbar = remember(platformToolbar, selection) { TrackingTextToolbar(platformToolbar, selection) }
    CompositionLocalProvider(LocalTextSelectionColors provides colors, LocalTextToolbar provides toolbar) {
        // A new key replaces the container, which is how a selection is cleared.
        key(selection.generation) {
        SelectionContainer {
            Box(Modifier.fillMaxSize()) {
                lines.forEach { line ->
                    val widthPx = line.bounds.width * fit.shownWidth
                    val heightPx = line.bounds.height * fit.shownHeight
                    if (widthPx < 1f || heightPx < 1f) return@forEach
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
                    Text(
                        text = line.text,
                        style = style,
                        softWrap = false,
                        maxLines = 1,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier
                            .offset { IntOffset(fit.left(line.bounds).roundToInt(), fit.top(line.bounds).roundToInt()) }
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(0f, 0f)
                                scaleX = widthPx / naturalWidth
                            }
                            .wrapContentSize(Alignment.TopStart, unbounded = true),
                    )
                }
            }
        }
        }
    }
}
