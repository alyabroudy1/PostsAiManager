package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.extraction.layout.LayoutLine

internal enum class TokKind { TEXT, RUN, BOX }

/**
 * A piece of a recognised line: text, a fill run or a box glyph, with its place on the page.
 *
 * Positions are in a reading-direction frame: `u` grows in the direction the page is read (left to right, or right to left
 * on a right-to-left page), so "to the right of a label" and "the next text" mean the same on every page.
 */
internal class Tok(
    val kind: TokKind,
    val text: String,
    val uLeft: Float,
    val uRight: Float,
    val top: Float,
    val bottom: Float,
    val lineId: Int,
    val checked: Boolean = false,
) {
    val width: Float get() = uRight - uLeft
    val height: Float get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
    val centerU: Float get() = (uLeft + uRight) / 2f
    val hasLetter: Boolean get() = text.any { it.isLetter() }
}

/**
 * The shapes a form's blanks have, as data: fill-run characters, box glyphs and their OCR look-alikes. Only shapes, never
 * words: no language is read here.
 */
internal object FormShapes {

    private const val BOX_GLYPHS = "☐□▢◻◽⬜❏❐❑❒○◯⭘ロ口"
    private const val CHECKED_GLYPHS = "☒☑✓✔✗✘"

    /** OCR often reads a lone box as one of these; accepted only when the whole recognised line is that single character. */
    private val LONE_LOOKALIKES = setOf("O", "o", "0", "D")
    private const val LONE_MAX_WIDTH = 0.05f

    private val TOKEN = Regex(
        "(_{3,}|(?:_\\s){3,}_?|\\.{4,}|(?:\\.\\s){4,}\\.?|…{2,}|[-–—]{4,})" +
            "|(\\[\\s?[xX✓✔]?\\s?\\]|\\(\\s?\\)|[$BOX_GLYPHS$CHECKED_GLYPHS])",
    )

    /** The line as tokens in reading order, positioned by character share of the line's box (the line's width is spread evenly). */
    fun tokenize(line: LayoutLine, lineId: Int, rtl: Boolean): List<Tok> {
        val text = line.text.trim()
        if (text.isEmpty()) return emptyList()
        val b = line.bounds
        val base = if (rtl) 1f - b.right else b.left
        val len = text.length
        fun u(index: Int) = base + b.width * index / len

        if (text in LONE_LOOKALIKES && b.width <= LONE_MAX_WIDTH) {
            return listOf(Tok(TokKind.BOX, text, base, base + b.width, b.top, b.bottom, lineId))
        }

        val out = ArrayList<Tok>()
        fun addText(from: Int, to: Int) {
            var s = from
            var e = to
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (s >= e) return
            val piece = text.substring(s, e)
            if (piece.none { it.isLetterOrDigit() }) return
            out += Tok(TokKind.TEXT, piece, u(s), u(e), b.top, b.bottom, lineId)
        }

        var at = 0
        for (m in TOKEN.findAll(text)) {
            addText(at, m.range.first)
            val run = m.groupValues[1].isNotEmpty()
            val value = m.value
            out += if (run) {
                Tok(TokKind.RUN, value, u(m.range.first), u(m.range.last + 1), b.top, b.bottom, lineId)
            } else {
                val checked = value.any { it in CHECKED_GLYPHS || it == 'x' || it == 'X' || it == '✓' || it == '✔' }
                Tok(TokKind.BOX, value, u(m.range.first), u(m.range.last + 1), b.top, b.bottom, lineId, checked)
            }
            at = m.range.last + 1
        }
        addText(at, len)
        return out
    }

    /** [label] without the colon, fill characters and box glyphs around it; null when no letter or digit is left. */
    fun cleanLabel(label: String): String? {
        val stripped = TOKEN.replace(label, " ").trim().trimEnd(':', '：', '﹕', '-', '–', '—', '.', '·', ' ').trim()
        val single = stripped.replace(Regex("\\s+"), " ")
        return single.takeIf { s -> s.any { it.isLetterOrDigit() } }
    }

    /** The text ends in a colon: a label that expects its value after it. A punctuation shape, not a word. */
    fun endsWithColon(text: String): Boolean = text.trimEnd().lastOrNull() in COLONS

    private val COLONS = setOf(':', '：', '﹕')
}
