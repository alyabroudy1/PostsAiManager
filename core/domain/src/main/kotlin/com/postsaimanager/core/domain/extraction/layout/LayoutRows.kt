package com.postsaimanager.core.domain.extraction.layout

import com.postsaimanager.core.model.TextBounds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Which things sit on one row of a page, and which rows are the rows of a table, by geometry alone. No word, number or script is read, so
 * it is the same for every language.
 *
 * Two boxes are on the same y-band when their vertical extents overlap by at least [OVERLAP_OF_LINE_HEIGHT] of the smaller one's height (a
 * tolerance relative to the line height, so a small or a large font both work). Several boxes on one band are not yet a row: two blocks of text
 * standing side by side (a recipient's address and a block of references beside it) share their bands too. Cells are joined into rows
 * only inside a **table region**: consecutive bands that each hold several cells, where the cells of one band line up (by their left edge,
 * their right edge or their centre) with those of the band before. A region is a table when it has three columns over two bands, or a list
 * of label and value (two columns) over three bands, such as the totals of an invoice. Two side-by-side blocks of two lines are not one.
 */
object RowBands {

    /** How much of the smaller line's height two lines must share vertically to be on one row. */
    const val OVERLAP_OF_LINE_HEIGHT = 0.5f

    /** How far (fraction of the page width) two edges may differ and still be one column. */
    const val ALIGN_TOLERANCE = 0.02f

    private const val MIN_ALIGNED = 2
    private const val TABLE_COLUMNS = 3
    private const val LIST_BANDS = 3

    fun sameBand(a: TextBounds, b: TextBounds): Boolean {
        val shared = min(a.bottom, b.bottom) - max(a.top, b.top)
        val smaller = min(a.height, b.height)
        return shared > 0f && shared >= OVERLAP_OF_LINE_HEIGHT * smaller
    }

    /**
     * [items] grouped into bands, the bands top to bottom, the items of a band left to right (or right to left when [rightToLeft]).
     * Bands that a later item connects are merged, so a tall cell joins the rows it spans.
     */
    fun <T> bands(items: List<T>, rightToLeft: Boolean = false, bounds: (T) -> TextBounds): List<List<T>> {
        val bands = ArrayList<MutableList<T>>()
        for (item in items.sortedBy { bounds(it).top }) {
            val b = bounds(item)
            val hits = bands.filter { band -> band.any { sameBand(bounds(it), b) } }
            when (hits.size) {
                0 -> bands += mutableListOf(item)
                1 -> hits.first() += item
                else -> {
                    val first = hits.first()
                    for (other in hits.drop(1)) { first += other; bands -= other }
                    first += item
                }
            }
        }
        fun centre(band: List<T>) = band.map { bounds(it).centerY }.average()
        return bands.sortedBy { centre(it) }.map { band ->
            if (rightToLeft) band.sortedByDescending { bounds(it).right } else band.sortedBy { bounds(it).left }
        }
    }

    /**
     * The bands of [items] that are rows of a table (see the class comment), in the order of [bands]: the others are lines of text, however
     * many of them happen to stand at one height.
     */
    fun <T> tableBands(bands: List<List<T>>, bounds: (T) -> TextBounds): List<List<T>> = tableRegions(bands, bounds).flatten()

    /** The tables of [bands], each as its bands top to bottom. */
    fun <T> tableRegions(bands: List<List<T>>, bounds: (T) -> TextBounds): List<List<List<T>>> {
        val out = ArrayList<List<List<T>>>()
        var region = ArrayList<List<T>>()
        fun close() {
            val columns = region.maxOfOrNull { it.size } ?: 0
            if (region.size >= 2 && (columns >= TABLE_COLUMNS || region.size >= LIST_BANDS)) out += region
            region = ArrayList()
        }
        for (band in bands) {
            if (band.size < 2 || !horizontallyApart(band, bounds)) { close(); continue }
            if (region.isNotEmpty() && !lineUp(region.last(), band, bounds)) close()
            region += listOf(band)
        }
        close()
        return out
    }

    /** The items that are cells of a table row, among [items] (one page, one class of lines). */
    fun <T> tableCells(items: List<T>, bounds: (T) -> TextBounds): Set<T> =
        tableBands(bands(items, bounds = bounds), bounds).flatten().toCollection(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>()))

    private fun <T> horizontallyApart(band: List<T>, bounds: (T) -> TextBounds): Boolean =
        band.any { a -> band.any { b -> a !== b && !overlapsHorizontally(bounds(a), bounds(b)) } }

    /** Whether at least [MIN_ALIGNED] cells of [next] stand in a column of [previous] (left edge, right edge or centre, within [ALIGN_TOLERANCE]). */
    private fun <T> lineUp(previous: List<T>, next: List<T>, bounds: (T) -> TextBounds): Boolean {
        val above = previous.map(bounds)
        val aligned = next.map(bounds).count { n ->
            above.any { p -> abs(p.left - n.left) <= ALIGN_TOLERANCE || abs(p.right - n.right) <= ALIGN_TOLERANCE || abs(p.centerX - n.centerX) <= ALIGN_TOLERANCE }
        }
        return aligned >= MIN_ALIGNED
    }

    private fun overlapsHorizontally(a: TextBounds, b: TextBounds): Boolean = min(a.right, b.right) > max(a.left, b.left)
}

/**
 * One row of a page as the reader sees it: the cells of a table row on one y-band in reading order, so a label and its value are one piece
 * of text (`2 | Layout-Entwurf | 3 h | 95,00 | 285,00`, `Gesamtbetrag | 1.284,50 €`), or one line of text standing alone.
 *
 * @property lines the cells, left to right (right to left on a right-to-left page); one line outside a table.
 * @property centerY where the row is on the page, for the physical order (a line standing beside another keeps its own band's height).
 */
class LayoutRow(
    val lines: List<LayoutLine>,
    val page: Int,
    val centerY: Float = lines.map { it.bounds.centerY }.average().toFloat(),
    /** The table this row belongs to (the rows of one table share this object), or null for a line of text. */
    val table: Any? = null,
) {

    /** The row's text: the cells joined by a bar; a cell that ends in a colon is followed by its value without one. */
    val text: String = buildString {
        lines.forEachIndexed { i, l ->
            if (i > 0) append(if (lines[i - 1].text.trimEnd().endsWith(':')) " " else " | ")
            append(l.text.trim())
        }
    }

    /** More than one cell: a row of a table. */
    val isMultiColumn: Boolean get() = lines.size > 1

    val left: Float = lines.minOf { it.bounds.left }
    val right: Float = lines.maxOf { it.bounds.right }
}

/** Rebuilds the rows of a page from its lines. */
object LayoutRows {

    /** The zones whose lines are never put on a row: each is a small block read as it is, line by line. */
    private val ALONE = setOf(LetterZone.LETTERHEAD, LetterZone.RETURN_ADDRESS_LINE, LetterZone.ADDRESS_FIELD, LetterZone.INFO_BLOCK)

    /** The class of a zone for rows: the flowing zones (subject, body, payment) are one region of the page, every other zone its own. */
    private fun rowClass(zone: LetterZone): Any = if (zone == LetterZone.SUBJECT || zone == LetterZone.BODY || zone == LetterZone.PAYMENT_SECTION) "flow" else zone

    /**
     * The rows of [lines] (one page, noise left out by the caller) in the physical order of the page: top to bottom, left to right on one
     * height. A line of a header block, or of text, is a row of its own; the cells of a table (see [RowBands]) are one row per band of
     * the table, whatever zones the analyzer gave its cells. Side-by-side blocks of text are never merged into rows.
     */
    fun of(lines: List<LayoutLine>, rightToLeft: Boolean = false): List<LayoutRow> {
        val rows = ArrayList<LayoutRow>()
        val (alone, shared) = lines.partition { it.zone in ALONE }
        alone.forEach { rows += LayoutRow(listOf(it), it.page) }
        for ((_, group) in shared.groupBy { rowClass(it.zone) }) {
            val bands = RowBands.bands(group, rightToLeft) { it.bounds }
            val regions = RowBands.tableRegions(bands) { it.bounds }.map { Any() to it }
            for (band in bands) {
                val centre = band.map { it.bounds.centerY }.average().toFloat()
                val table = regions.firstOrNull { (_, region) -> region.any { it === band } }?.first
                if (table != null) rows += LayoutRow(band, band.first().page, centre, table) else band.forEach { rows += LayoutRow(listOf(it), it.page, centre) }
            }
        }
        return rows.sortedWith(compareBy({ it.centerY }, { if (rightToLeft) -it.right else it.left }))
    }
}
