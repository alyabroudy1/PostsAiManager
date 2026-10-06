package com.postsaimanager.core.designsystem.component

import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.OcrLine
import com.postsaimanager.core.model.TextBounds
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A selected stretch of a page's text as caret offsets: characters [start] until [end] (exclusive), `start < end`. */
internal data class TextSelection(val start: Int, val end: Int)

/** Where a selection handle hangs: [x] at the caret, between the line's [top] and [bottom]; all normalised page coordinates. */
internal data class CaretAnchor(val x: Float, val top: Float, val bottom: Float)

/**
 * A page's recognised text as one linear string with a box for every character, so it can be selected like text in an editor.
 * Purely geometric; no coordinates other than normalised page space (0..1) go in or come out.
 *
 * Built from the page's blocks already in reading order. Lines are joined with `\n` (blocks too), and an offset into [text] is a caret
 * position: 0 is before the first character, `text.length` after the last.
 *
 * Where a character sits horizontally:
 *  - a line with stored word boxes: each word's characters are spread evenly over that word's box, the gaps between words over the gap;
 *  - a line without them (read before words were kept, or whose words don't match its text): the characters, spaces included,
 *    are spread evenly over the line's box, which is the same as splitting it across the words in proportion to their length;
 *  - a block without lines: its text is split on `\n` and the block's box is divided vertically among those lines.
 * Spreading by character count is approximate, but a caret lands within a letter or two of the touch.
 */
internal class PageTextLayout(blocks: List<OcrBlock>) {

    /** One text line: its characters are [start] until [end] of [text]; [xs] holds the `end - start + 1` normalised x of its caret positions. */
    private class Line(val start: Int, val end: Int, val bounds: TextBounds, val xs: FloatArray)

    val text: String
    private val lines: List<Line>

    init {
        val sb = StringBuilder()
        val out = mutableListOf<Line>()
        for (block in blocks) {
            for (line in linesOf(block)) {
                val t = line.text.replace('\n', ' ')
                if (sb.isNotEmpty()) sb.append('\n')
                val start = sb.length
                sb.append(t)
                out += Line(start, start + t.length, line.bounds, caretXs(t, line))
            }
        }
        text = sb.toString()
        lines = out
    }

    val isEmpty: Boolean get() = text.isEmpty()

    /** The text between the carets, exactly as stored: spaces inside a line, a newline between lines. */
    fun textOf(selection: TextSelection): String = text.substring(selection.start.coerceIn(0, text.length), selection.end.coerceIn(0, text.length))

    /** The whole page. */
    fun all(): TextSelection? = if (text.isEmpty()) null else TextSelection(0, text.length)

    /**
     * The character nearest the point ([x], [y]) within an ellipse of [radiusX] by [radiusY] around it, or null when none is that close.
     * The radii are separate because one screen distance is a different fraction of the page's width and height.
     * A press between two lines picks the nearer line; one just inside a box edge still finds its character.
     */
    fun hitTest(x: Float, y: Float, radiusX: Float, radiusY: Float): Int? {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (line in lines) {
            val dy = gap(y, line.bounds.top, line.bounds.bottom)
            if (dy > radiusY) continue
            for (i in line.start until line.end) {
                val k = i - line.start
                val dx = gap(x, line.xs[k], line.xs[k + 1])
                val d = hypot(dx / radiusX, dy / radiusY)
                if (d <= 1f && d < bestDistance) {
                    best = i
                    bestDistance = d
                }
            }
        }
        return best.takeIf { it >= 0 }
    }

    /**
     * The caret nearest the point, with no distance limit: what dragging a handle uses. The nearest line wins (the left one of two
     * side-by-side columns when the point is level with both and nearer to it); above everything is the start, below everything the end.
     */
    fun caretAt(x: Float, y: Float): Int {
        if (lines.isEmpty()) return 0
        if (y < lines.minOf { it.bounds.top }) return 0
        if (y > lines.maxOf { it.bounds.bottom }) return text.length
        val nearestVertical = lines.minOf { gap(y, it.bounds.top, it.bounds.bottom) }
        val line = lines
            .filter { gap(y, it.bounds.top, it.bounds.bottom) <= nearestVertical + LEVEL }
            .minBy { gap(x, it.xs.first(), it.xs.last()) }
        var k = 0
        for (i in line.xs.indices) if (kotlin.math.abs(line.xs[i] - x) < kotlin.math.abs(line.xs[k] - x)) k = i
        return line.start + k
    }

    /** The word (a run without whitespace) the character at [index] belongs to; a space picks the nearer word on its line. Null with no word. */
    fun wordAt(index: Int): TextSelection? {
        if (text.isEmpty()) return null
        var i = index.coerceIn(0, text.lastIndex)
        if (text[i].isWhitespace()) {
            var left = i
            while (left >= 0 && text[left] != '\n' && text[left].isWhitespace()) left--
            var right = i
            while (right <= text.lastIndex && text[right] != '\n' && text[right].isWhitespace()) right++
            val leftOk = left >= 0 && !text[left].isWhitespace() && text[left] != '\n'
            val rightOk = right <= text.lastIndex && !text[right].isWhitespace() && text[right] != '\n'
            i = when {
                leftOk && rightOk -> if (i - left <= right - i) left else right
                leftOk -> left
                rightOk -> right
                else -> return null
            }
        }
        var s = i
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        var e = i + 1
        while (e < text.length && !text[e].isWhitespace()) e++
        return TextSelection(s, e)
    }

    /** One rectangle per line the selection touches, spanning just its characters on that line. */
    fun selectionRects(selection: TextSelection): List<TextBounds> =
        lines.mapNotNull { line ->
            val s = max(selection.start, line.start)
            val e = min(selection.end, line.end)
            if (s < e) TextBounds(line.xs[s - line.start], line.bounds.top, line.xs[e - line.start], line.bounds.bottom) else null
        }

    /** Where the selection's start and end handles hang. */
    fun handleAnchors(selection: TextSelection): Pair<CaretAnchor, CaretAnchor>? {
        val start = anchorAt(selection.start) ?: return null
        val end = anchorAt(selection.end) ?: return null
        return start to end
    }

    /** Every line's box, for tinting all the recognised text at once. */
    fun lineBounds(): List<TextBounds> = lines.map { it.bounds }

    private fun anchorAt(caret: Int): CaretAnchor? {
        val line = lines.firstOrNull { caret in it.start..it.end } ?: return null
        return CaretAnchor(line.xs[caret - line.start], line.bounds.top, line.bounds.bottom)
    }

    private companion object {
        /** Lines whose vertical distance to a point differs by less than this (a hair of page height) count as level with each other. */
        const val LEVEL = 0.004f

        /** Distance from [v] to the interval [low, high]; 0 inside it. */
        fun gap(v: Float, low: Float, high: Float): Float = if (v < low) low - v else if (v > high) v - high else 0f

        fun linesOf(block: OcrBlock): List<OcrLine> {
            val stored = block.lines.filter { it.text.isNotBlank() }
            if (stored.isNotEmpty()) return stored
            val parts = block.text.split('\n').filter { it.isNotBlank() }
            if (parts.isEmpty()) return emptyList()
            val b = block.bounds
            val h = b.height / parts.size
            return parts.mapIndexed { i, p -> OcrLine(p, TextBounds(b.left, b.top + h * i, b.right, b.top + h * (i + 1))) }
        }

        /** The normalised x of each caret position of [t] (`t.length + 1` of them), never decreasing. */
        fun caretXs(t: String, line: OcrLine): FloatArray {
            val n = t.length
            val anchors = wordAnchors(t, line) ?: listOf(0 to line.bounds.left, n to line.bounds.right)
            val xs = FloatArray(n + 1)
            var prevIndex = anchors[0].first
            var prevX = anchors[0].second
            xs[prevIndex] = prevX
            for ((index, rawX) in anchors.drop(1)) {
                val x = max(rawX, prevX)
                if (index > prevIndex) {
                    for (k in 1..index - prevIndex) xs[prevIndex + k] = prevX + (x - prevX) * k / (index - prevIndex)
                } else {
                    xs[index] = x
                }
                prevIndex = index
                prevX = x
            }
            return xs
        }

        /**
         * (character index, x) pairs from the line's start to its end through each word's edges, or null when the words don't line up
         * with the line's text (then the caller spreads the characters over the whole line).
         */
        fun wordAnchors(t: String, line: OcrLine): List<Pair<Int, Float>>? {
            if (line.words.isEmpty()) return null
            val anchors = mutableListOf(0 to line.bounds.left)
            var from = 0
            for (w in line.words) {
                if (w.text.isEmpty()) continue
                val i = t.indexOf(w.text, from)
                if (i < 0) return null
                anchors += i to w.bounds.left
                anchors += (i + w.text.length) to w.bounds.right
                from = i + w.text.length
            }
            anchors += t.length to line.bounds.right
            return anchors
        }
    }
}
