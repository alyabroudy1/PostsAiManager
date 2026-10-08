package com.postsaimanager.core.domain.extraction.layout

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.OcrWord
import com.postsaimanager.core.model.TextBounds

/**
 * Removes the avatar or icon glyph a chat screenshot puts in front of a name (the circle with the sender's initial: "Z  Zahnarztpraxis")
 * from the line the OCR read it into, so the glyph is never part of a name.
 *
 * A layout and shape repair, no meaning and no word: the line's first word (an OCR element with its own box) is one capital letter on
 * its own, and it stands apart from the words after it, by its own box being much taller than theirs or by a wide gap before them.
 * A line of ordinary text (an initial that is as tall as the name and one space before it) is left as it is. A line whose words were not
 * kept (pages read before words were stored) cannot be told, and is left too.
 *
 * Idempotent: a block with the glyph removed has nothing left to remove.
 */
object AvatarGlyph {

    /** The glyph's box is at least this many times as tall as the median word after it. */
    const val MIN_HEIGHT_RATIO = 1.4f

    /** The gap between the glyph and the next word is at least this many times the glyph's own width. */
    const val MIN_GAP_WIDTHS = 1.5f

    fun strip(pages: List<List<OcrBlock>>): List<List<OcrBlock>> = pages.map { blocks -> blocks.map(::strip) }

    fun strip(block: OcrBlock): OcrBlock {
        if (block.lines.isEmpty()) return block
        val texts = block.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // The block's text and its lines must be the same lines, or the words cannot be matched to the text.
        if (texts.size != block.lines.size) return block
        var changed = false
        val lines = block.lines.map { line ->
            val glyph = line.words.firstOrNull()
            if (glyph != null && isAvatar(line.words)) {
                changed = true
                withoutFirstWord(line, glyph)
            } else {
                line
            }
        }
        if (!changed) return block
        val text = texts.indices.joinToString("\n") { i -> if (lines[i] === block.lines[i]) texts[i] else lines[i].text }
        val left = lines.minOf { it.bounds.left }
        return block.copy(
            text = text,
            bounds = TextBounds(maxOf(block.bounds.left, left), block.bounds.top, block.bounds.right, block.bounds.bottom),
            lines = lines,
        )
    }

    private fun isAvatar(words: List<OcrWord>): Boolean {
        if (words.size < 2) return false
        val first = words[0]
        val letter = first.text.trim()
        if (letter.codePointCount(0, letter.length) != 1 || !letter.first().isLetter() || !letter.first().isUpperCase()) return false
        val rest = words.drop(1)
        val height = rest.map { it.bounds.height }.sorted().let { it[it.size / 2] }
        if (height <= 0f || first.bounds.width <= 0f) return false
        val taller = first.bounds.height >= MIN_HEIGHT_RATIO * height
        val apart = rest.first().bounds.left - first.bounds.right >= MIN_GAP_WIDTHS * first.bounds.width
        return taller || apart
    }

    private fun withoutFirstWord(line: OcrLine, glyph: OcrWord): OcrLine {
        val text = line.text.trim().removePrefix(glyph.text.trim()).trim()
        val words = line.words.drop(1)
        val left = words.minOf { it.bounds.left }
        return line.copy(text = text, bounds = line.bounds.copy(left = maxOf(line.bounds.left, left)), words = words)
    }
}
