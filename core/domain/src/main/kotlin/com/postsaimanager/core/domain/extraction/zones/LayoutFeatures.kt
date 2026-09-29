package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The geometry of a [LetterLayout], measured once: where the zones sit, how tall the page reads,
 * how many rows have several columns, how many are label/value pairs, which way the page reads.
 *
 * Only positions, sizes and counts. No text is read except its length (a line's width per character
 * says how tall the page is). [TemplateMatcher] scores the templates on these numbers.
 *
 * @param pageAspect width over height of the page when the caller knows it (an image size); otherwise
 *   the page's shape is estimated from the text ([fontRatio]).
 */
class LayoutFeatures(layout: LetterLayout, pageAspect: Float? = null) {

    private val first = layout.pages.firstOrNull()?.lines.orEmpty().filter { !it.isNoise }
    private val everyLine = layout.pages.flatMap { p -> p.lines.filter { !it.isNoise } }

    /** Page-1 lines of a zone. */
    fun lines(zone: LetterZone): List<LayoutLine> = first.filter { it.zone == zone }

    fun count(zone: LetterZone): Int = lines(zone).size

    /** Lines of the whole letter, all pages, in [zone]. */
    fun countEverywhere(zone: LetterZone): Int = everyLine.count { it.zone == zone }

    /** The share of [zone]'s page-1 lines whose centre is inside [region]; 0 when the zone is absent. */
    fun fit(zone: LetterZone, region: Region): Float {
        val ls = lines(zone)
        if (ls.isEmpty()) return 0f
        return ls.count { region.contains(it.bounds.centerX, it.bounds.centerY) }.toFloat() / ls.size
    }

    /**
     * Median of `width / (height * characters)` over the page's longer lines. A character is about as
     * wide as it is tall in every printed font, so this number moves with the page's own shape: about
     * 0.5 to 0.8 on A4 (the bounds are normalised to the page, and the page is 1.41 times taller than
     * wide), above 1.1 on a receipt roll (3 times taller than wide).
     */
    val fontRatio: Float = pageAspect?.takeIf { it > 0f }?.let { A4_RATIO * (1f / it) / A4_TALLNESS }
        ?: first.filter { it.text.trim().length >= MIN_CHARS && it.bounds.height > 0f }
            .map { it.bounds.width / (it.bounds.height * it.text.trim().length) }
            .sorted().let { r -> if (r.size < MIN_LINES) A4_RATIO else r[r.size / 2] }

    private class Row(val lines: List<LayoutLine>)

    private val rows: List<Row> = everyLine.groupBy { it.page }.values.flatMap { page ->
        val sorted = page.sortedBy { it.bounds.centerY }
        val out = ArrayList<Row>()
        var current = ArrayList<LayoutLine>()
        for (l in sorted) {
            val anchor = current.firstOrNull()
            val tol = if (anchor == null) 0f else maxOf(0.006f, 0.5f * minOf(anchor.bounds.height, l.bounds.height))
            if (anchor == null || abs(l.bounds.centerY - anchor.bounds.centerY) <= tol) {
                current += l
            } else {
                out += Row(current.sortedBy { it.bounds.left })
                current = arrayListOf(l)
            }
        }
        if (current.isNotEmpty()) out += Row(current.sortedBy { it.bounds.left })
        out
    }

    private fun segments(row: Row): Int {
        var n = 1
        for (i in 1 until row.lines.size) {
            if (row.lines[i].bounds.left - row.lines[i - 1].bounds.right >= COLUMN_GAP) n++
        }
        return if (row.lines.size == 1) 1 else n
    }

    /** Rows with three or more separate columns of text: the body of a table. */
    val tableRows: Int = rows.count { segments(it) >= 3 }

    /**
     * The share of rows that are two columns whose left edges repeat: "label ... value" pairs, as in a form.
     * Measured over rows that have any text at all.
     */
    val keyValueShare: Float = run {
        val pairs = rows.filter { segments(it) == 2 }
        if (pairs.size < MIN_LINES || rows.isEmpty()) return@run 0f
        fun bin(x: Float) = (x / KV_BIN).toInt()
        val leftBins = pairs.groupingBy { bin(it.lines.first().bounds.left) }.eachCount().values.sortedDescending()
        val rightBins = pairs.groupingBy { bin(it.lines.last().bounds.left) }.eachCount().values.sortedDescending()
        val aligned = minOf(leftBins.first(), rightBins.first())
        aligned.toFloat() / rows.size
    }

    /**
     * True when the page reads right to left: its address block sits on the right half, or its lines
     * are flush at a common right edge while their left edges scatter.
     */
    val rightToLeft: Boolean = run {
        val address = lines(LetterZone.ADDRESS_FIELD)
        if (address.size >= 2 && address.map { it.bounds.centerX }.average() > RTL_ADDRESS_X) return@run true
        val long = first.filter { it.text.trim().length >= MIN_CHARS }
        if (long.size < MIN_LINES) return@run false
        val leftSpread = spread(long.map { it.bounds.left })
        val rightSpread = spread(long.map { it.bounds.right })
        rightSpread * RTL_FLUSH_FACTOR < leftSpread && long.map { it.bounds.right }.average() > long.map { it.bounds.left }.average() + 0.3
    }

    private fun spread(values: List<Float>): Float {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size).toFloat()
    }

    private companion object {
        const val A4_RATIO = 0.6f
        const val A4_TALLNESS = 1.414f
        const val MIN_CHARS = 8
        const val MIN_LINES = 5
        const val COLUMN_GAP = 0.02f
        const val KV_BIN = 0.03f
        const val RTL_ADDRESS_X = 0.55
        const val RTL_FLUSH_FACTOR = 2f
    }
}
