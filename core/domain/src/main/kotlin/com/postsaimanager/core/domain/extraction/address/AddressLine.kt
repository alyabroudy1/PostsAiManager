package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.domain.extraction.candidates.OcrText
import com.postsaimanager.core.domain.extraction.layout.AddressShapes
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.model.TextBounds
import kotlin.math.abs

/** One line of an address block: its text (characters normalised like the candidate extractor reads them), page and place. */
class AddressLine(val text: String, val page: Int, val bounds: TextBounds) {
    val centerY: Float get() = bounds.centerY
}

/** How lines become an address block, by geometry: rows are joined, stacks are found around a postcode line. No word is read. */
object AddressLines {

    /** [lines] as address lines, top to bottom. Blank lines are dropped. */
    fun of(lines: List<LayoutLine>): List<AddressLine> =
        lines.map { AddressLine(OcrText.normalizeChars(it.text).trim(), it.page, it.bounds) }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.centerY }

    /**
     * A line that holds a street with its number and, after it, a postcode and a place (`Versicherungsallee 15 12345 Beispielstadt`)
     * becomes two lines, the street and the "postcode place" line, each with its half of the line's box. Decided by the formats'
     * postcode shapes; any other line is untouched.
     */
    fun splitCombined(lines: List<AddressLine>, formats: AddressFormatRegistry): List<AddressLine> = lines.flatMap { line ->
        val at = formats.combinedSplit(line.text)
        if (at == null) {
            listOf(line)
        } else {
            val mid = (line.bounds.top + line.bounds.bottom) / 2f
            listOf(
                AddressLine(line.text.substring(0, at).trim().trimEnd(',', ';', ':', '-', '–', '·', '•', '|', ' '), line.page, line.bounds.copy(bottom = mid)),
                AddressLine(line.text.substring(at).trim(), line.page, line.bounds.copy(top = mid)),
            )
        }
    }

    /**
     * Fragments on one visual row become one line: a city and a postcode that OCR read as two blocks, in the reading direction of the
     * page (left to right, or right to left when the letters are in a right-to-left script). Lines on different rows are untouched.
     */
    fun joinRows(lines: List<AddressLine>): List<AddressLine> {
        if (lines.size < 2) return lines
        val rtl = LetterLayoutAnalyzer.isRightToLeftText(lines.map { it.text })
        val rows = ArrayList<MutableList<AddressLine>>()
        for (l in lines.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            val anchor = row?.first()
            val tolerance = if (anchor == null) 0f else maxOf(MIN_ROW_TOLERANCE, 0.5f * minOf(anchor.bounds.height, l.bounds.height))
            if (row != null && abs(l.centerY - anchor!!.centerY) <= tolerance) row += l else rows += mutableListOf(l)
        }
        return rows.map { row ->
            if (row.size == 1) {
                row.first()
            } else {
                val ordered = if (rtl) row.sortedByDescending { it.bounds.right } else row.sortedBy { it.bounds.left }
                AddressLine(
                    ordered.joinToString(" ") { it.text }, ordered.first().page,
                    TextBounds(row.minOf { it.bounds.left }, row.minOf { it.bounds.top }, row.maxOf { it.bounds.right }, row.maxOf { it.bounds.bottom }),
                )
            }
        }
    }

    /**
     * The stack of lines that ends in the postcode line [anchor] of [lines] (sorted top to bottom): up to [MAX_LINES] lines above it
     * that keep the stack's edge (left, or right on a right-to-left page) and its small gaps, plus a country-shaped line right under it.
     */
    fun stackEndingAt(lines: List<AddressLine>, anchor: Int): List<AddressLine> {
        val rtl = LetterLayoutAnalyzer.isRightToLeftText(lines.map { it.text })
        fun edge(l: AddressLine) = if (rtl) l.bounds.right else l.bounds.left
        val base = lines[anchor]
        var first = anchor
        while (first > 0 && anchor - first + 1 < MAX_LINES) {
            val above = lines[first - 1]
            if (lines[first].centerY - above.centerY > AddressShapes.MAX_LINE_GAP || abs(edge(above) - edge(base)) > AddressShapes.MAX_LEFT_DRIFT) break
            first--
        }
        var last = anchor
        lines.getOrNull(anchor + 1)?.let {
            if (AddressShapes.isCountryText(it.text) && it.centerY - base.centerY <= AddressShapes.COUNTRY_GAP && abs(edge(it) - edge(base)) <= AddressShapes.MAX_LEFT_DRIFT) last = anchor + 1
        }
        return lines.subList(first, last + 1)
    }

    private const val MIN_ROW_TOLERANCE = 0.006f
    private const val MAX_LINES = 6
}
