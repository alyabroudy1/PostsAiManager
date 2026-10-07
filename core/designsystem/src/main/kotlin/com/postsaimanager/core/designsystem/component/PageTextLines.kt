package com.postsaimanager.core.designsystem.component

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.TextBounds

/** One recognised line of a page and its box in normalised page coordinates (0..1). */
internal data class PageTextLine(val text: String, val bounds: TextBounds)

/**
 * A page's recognised text as lines in reading order, ready to be laid invisibly over the page image (see [OcrTextLayer]).
 * Blocks arrive already in reading order; each contributes its own stored lines, or, for a block read before lines were kept,
 * its text split on `\n` with the block's box divided vertically among the parts. Blank lines are dropped; a line's inner
 * newlines become spaces so one line stays one selectable run.
 */
internal fun pageTextLines(blocks: List<OcrBlock>): List<PageTextLine> =
    blocks.flatMap { block -> linesOf(block) }
        .map { PageTextLine(it.text.replace('\n', ' '), it.bounds) }

private fun linesOf(block: OcrBlock): List<OcrLine> {
    val stored = block.lines.filter { it.text.isNotBlank() }
    if (stored.isNotEmpty()) return stored
    val parts = block.text.split('\n').filter { it.isNotBlank() }
    if (parts.isEmpty()) return emptyList()
    val b = block.bounds
    val h = b.height / parts.size
    return parts.mapIndexed { i, p -> OcrLine(p, TextBounds(b.left, b.top + h * i, b.right, b.top + h * (i + 1))) }
}
