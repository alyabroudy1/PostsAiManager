package com.postsaimanager.core.domain.extraction.layout

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.TextBounds

/**
 * The positioned blocks of a page whose text the user corrected: plain text, top to bottom, in one column.
 *
 * A reading works on blocks and where they sit, and the corrected text has no positions (the person typed words, not boxes). So the page is
 * given the one layout every text has: its lines in order, each taking an equal share of the page's height, the paragraphs (separated by a
 * blank line) as the blocks. A reader that finds columns, the sender's window or the address field by position finds none on such a page and
 * reads it as running text, which is exactly what the corrected text is. Nothing here guesses what any line means.
 */
object PlainTextBlocks {

    private const val MARGIN = 0.05f
    private const val CONFIDENCE = 1f

    /** The blocks for [text]; none for a text with no line in it. */
    fun of(text: String): List<OcrBlock> {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n"))
            .map { paragraph -> paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }
        val lineCount = paragraphs.sumOf { it.size }
        if (lineCount == 0) return emptyList()
        val share = (1f - 2 * MARGIN) / lineCount
        var index = 0
        return paragraphs.map { lines ->
            val first = index
            val ocrLines = lines.map { line ->
                val top = MARGIN + share * index
                index++
                OcrLine(text = line, bounds = TextBounds(MARGIN, top, 1f - MARGIN, top + share))
            }
            OcrBlock(
                text = lines.joinToString("\n"),
                bounds = TextBounds(MARGIN, MARGIN + share * first, 1f - MARGIN, MARGIN + share * index),
                confidence = CONFIDENCE,
                lines = ocrLines,
            )
        }
    }
}
