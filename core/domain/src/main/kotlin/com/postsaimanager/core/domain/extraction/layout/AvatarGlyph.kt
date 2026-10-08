package com.postsaimanager.core.domain.extraction.layout

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.OcrWord
import com.postsaimanager.core.model.TextBounds

/**
 * Removes the avatar or icon glyph a chat screenshot puts in front of a name (the circle with the sender's initial: "Z  Zahnarztpraxis")
 * from the line the OCR read it into, so the glyph is never part of a name.
 *
 * A layout and shape repair, no meaning and no word. It reads the structure ML Kit hands over: a line's elements (words), each with its own
 * box. The line's FIRST element is a glyph, not the first letter of a word, when
 *
 * 1. it is one or two characters, all letters and none in lower case (an initial: "Z", "MK"), and it is an element of its own;
 * 2. its box is close to square, measured against the letters beside it (the boxes are normalised to the page, so a pixel aspect is not
 *    known; the line's own letters are the yardstick: [MIN_SQUARENESS] to [MAX_SQUARENESS] times their width-to-height);
 * 3. it stands apart from the next element: the gap is wider than an ordinary word space, [MIN_GAP_CHARS] times the average character
 *    width of the rest of the line (a space is about 0.6 of it), or the box is much taller than the words after it ([MIN_HEIGHT_RATIO]).
 *
 * A line of ordinary text (an initial as tall as the name and one space before it, a lower-case letter, a longer word, a digit) is left as
 * it is. A line whose words were not kept (pages read before words were stored) cannot be told, and is left too: the document is then read
 * from its image again once (`StoredOcr`), and stored with the words.
 *
 * Idempotent: a block with the glyph removed has nothing left to remove.
 */
object AvatarGlyph {

    /** The glyph's box is at least this many times as tall as the median word after it. */
    const val MIN_HEIGHT_RATIO = 1.4f

    /** The gap between the glyph and the next element is at least this many times the average character width of the rest of the line. */
    const val MIN_GAP_CHARS = 1.0f

    /** The glyph's width-to-height, in units of the rest of the line's average character width-to-height: not a bar, not a dash. */
    const val MIN_SQUARENESS = 0.5f
    const val MAX_SQUARENESS = 2.5f

    private const val MAX_CHARS = 2

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
        if (!isInitial(first.text.trim())) return false
        val rest = words.drop(1)
        val height = rest.map { it.bounds.height }.sorted().let { it[it.size / 2] }
        val chars = rest.sumOf { it.text.trim().length }
        if (height <= 0f || first.bounds.width <= 0f || first.bounds.height <= 0f || chars == 0) return false
        // The rest of the line's letters are the yardstick: their average width, and their width-to-height.
        val charWidth = rest.sumOf { it.bounds.width.toDouble() }.toFloat() / chars
        if (charWidth <= 0f) return false
        val squareness = (first.bounds.width / first.bounds.height) / (charWidth / height)
        if (squareness < MIN_SQUARENESS || squareness > MAX_SQUARENESS) return false
        val taller = first.bounds.height >= MIN_HEIGHT_RATIO * height
        val apart = rest.first().bounds.left - first.bounds.right >= MIN_GAP_CHARS * charWidth
        return taller || apart
    }

    /** One or two characters, all letters, none in lower case: an initial, never a word, a digit or a bullet. */
    private fun isInitial(text: String): Boolean =
        text.codePointCount(0, text.length) in 1..MAX_CHARS && text.all { it.isLetter() && !it.isLowerCase() }

    private fun withoutFirstWord(line: OcrLine, glyph: OcrWord): OcrLine {
        val text = line.text.trim().removePrefix(glyph.text.trim()).trim()
        val words = line.words.drop(1)
        val left = words.minOf { it.bounds.left }
        return line.copy(text = text, bounds = line.bounds.copy(left = maxOf(line.bounds.left, left)), words = words)
    }
}
