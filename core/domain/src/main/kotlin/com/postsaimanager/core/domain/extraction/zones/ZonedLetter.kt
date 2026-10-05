package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LayoutRow
import com.postsaimanager.core.domain.extraction.layout.LayoutRows
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.layout.PageLayoutView
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A letter seen through a layout template: the analyzer's zones read through the template's
 * [LayoutTemplate.remap], the text of each zone, and which zones each offered candidate was printed in.
 *
 * Geometry and text positions only; no word of the letter is interpreted here.
 */
class ZonedLetter(
    val layout: LetterLayout,
    val template: LayoutTemplate,
    val offered: OfferedCandidates,
) {

    /**
     * The rows of each page, top to bottom (see [LayoutRows]): the lines on one y-band are one row, so a label and its value, or the
     * cells of a row of a table of positions, are read together, in the physical order of the page.
     */
    private val rowsByPage: Map<Int, List<LayoutRow>> = layout.pages.associate { p ->
        val lines = p.lines.filter { !it.isNoise }
        p.pageNumber to LayoutRows.of(lines, LetterLayoutAnalyzer.isRightToLeftText(lines.map { it.text }))
    }

    /** The zone of each line that sits on a row: the row's, so a row is never split across zones. Identity keys: equal lines are different lines. */
    private val rowZone: Map<LayoutLine, LetterZone> = IdentityHashMap<LayoutLine, LetterZone>().also { map ->
        for (rows in rowsByPage.values.map { it.groupBy { r -> r.table ?: r } }.flatMap { it.values }) {
            // One zone per table (a lone line is its own): the payment zone marks amounts by shape, so a table with a cell in it is a payment
            // table, otherwise the first cell's zone is the zone. A label, its value and the positions of a table are never split across zones.
            val cells = rows.flatMap { it.lines }
            val zone = cells.firstOrNull { it.zone == LetterZone.PAYMENT_SECTION }?.zone ?: cells.first().zone
            cells.forEach { map[it] = template.remap[zone] ?: zone }
        }
    }

    /** The zone [line] belongs to under this template: that of its row. */
    fun zoneOf(line: LayoutLine): LetterZone = rowZone[line] ?: template.remap[line.zone] ?: line.zone

    /** [zone] after the template's remap: a placement or a spec that names a zone the template folds elsewhere. */
    fun mapped(zone: LetterZone): LetterZone = template.remap[zone] ?: zone

    /** Non-noise lines of [zone]: page 1 only for the header zones, every page for the others. */
    fun lines(zone: LetterZone): List<LayoutLine> {
        val pages = if (zone in HEADER_ZONES) layout.pages.take(1) else layout.pages
        return pages.flatMap { p -> p.lines.filter { !it.isNoise && zoneOf(it) == zone } }
    }

    fun hasText(zone: LetterZone): Boolean = lines(zone).isNotEmpty()

    /** The zones each offered candidate was printed in (every occurrence on its pages, so a repeated value counts in each). */
    private val candidateZones: Map<String, Set<LetterZone>> = offered.rows.associate { row ->
        row.candidate.id to zonesOf(row.candidate, row.pages)
    }

    fun zonesOfCandidate(id: String): Set<LetterZone> = candidateZones[id].orEmpty()

    private fun zonesOf(c: Candidate, pages: List<Int>): Set<LetterZone> {
        val out = LinkedHashSet<LetterZone>()
        when (c.attrs["zone"]) {
            "ADDRESS_FIELD" -> out += LetterZone.ADDRESS_FIELD
            "LETTERHEAD" -> out += LetterZone.LETTERHEAD
            "RETURN_ADDRESS" -> out += LetterZone.RETURN_ADDRESS_LINE
            "FOOTER" -> out += LetterZone.FOOTER
        }
        val key = squash(c.raw)
        if (key.isNotEmpty()) {
            for (page in pages.ifEmpty { listOf(c.page) }) {
                layout.page(page)?.lines.orEmpty().filter { !it.isNoise && squash(it.text).contains(key) }.forEach { out += zoneOf(it) }
            }
        }
        if (out.isEmpty()) {
            // Not found as text (a normalised value): the nearest line on its page decides.
            val box = c.bbox
            val line = layout.page(c.page)?.lines.orEmpty().filter { !it.isNoise }.let { ls ->
                if (box == null) ls.firstOrNull() else ls.minByOrNull { abs(it.bounds.centerX - box.centerX) + abs(it.bounds.centerY - box.centerY) }
            }
            out += line?.let { zoneOf(it) } ?: LetterZone.BODY
        }
        return out.map { mapped(it) }.toSet()
    }

    /**
     * The row a candidate is printed on and the rows just above and below it (physical order of the page: top to bottom, each row
     * left to right), for a scoring question. A row is every line on its y-band, so a value comes with its label
     * (`Gesamtbetrag | 1.284,50 €`) and a position of a table with its cells. Nulls when not found.
     */
    class Context(val above: String?, val line: String?, val below: String?)

    fun context(c: Candidate): Context {
        val rows = rowsByPage[c.page].orEmpty()
        val at = rowOf(c, rows)?.let { rows.indexOf(it) } ?: return Context(null, null, null)
        fun cut(row: LayoutRow?) = row?.text?.take(CONTEXT_CHARS)
        return Context(cut(neighbour(rows, at, -1)), cut(rows[at]), cut(neighbour(rows, at, 1)))
    }

    /** The next row up ([step] -1) or down (1) from row [at] that stands over or under it, not a block standing beside it. */
    private fun neighbour(rows: List<LayoutRow>, at: Int, step: Int): LayoutRow? {
        var i = at + step
        while (i in rows.indices) {
            if (min(rows[i].right, rows[at].right) > max(rows[i].left, rows[at].left)) return rows[i]
            i += step
        }
        return null
    }

    /**
     * The row [c] is printed on: among the rows that print it, one where it stands as a cell of its own beside other cells (a value
     * with its label, the evidence a sentence that merely repeats it lacks) before the others; then the one nearest the candidate's block.
     */
    private fun rowOf(c: Candidate, rows: List<LayoutRow>): LayoutRow? {
        val key = squash(c.raw)
        if (key.isEmpty()) return null
        val printing = rows.filter { row -> row.lines.any { squash(it.text).contains(key) } }
        if (printing.isEmpty()) return null
        val box = c.bbox
        fun distance(row: LayoutRow): Float {
            if (box == null) return rows.indexOf(row).toFloat()
            return row.lines.filter { squash(it.text).contains(key) }.minOf { abs(it.bounds.centerX - box.centerX) + abs(it.bounds.centerY - box.centerY) }
        }
        val cellOfItsOwn = printing.filter { row -> row.isMultiColumn && row.lines.any { squash(it.text) == key } }
        return (cellOfItsOwn.ifEmpty { printing }).minByOrNull { distance(it) }
    }

    /**
     * The words printed just before [c] on its row, for a question about a number: the text before the value in its own cell, else the cell
     * before it (the left neighbour in reading order). Text of the page as the OCR read it, never composed; null when nothing stands before
     * the value.
     */
    fun printedLabel(c: Candidate): String? {
        val row = rowOf(c, rowsByPage[c.page].orEmpty()) ?: return null
        val key = squash(c.raw)
        val at = row.lines.indexOfFirst { squash(it.text).contains(key) }
        if (at < 0) return null
        val cell = row.lines[at].text
        // The value starts at its first letter or digit: the punctuation before it (the full stop of "Nr.") belongs to the label.
        val start = cell.indices.firstOrNull { cell[it].isLetterOrDigit() && squash(cell.substring(it)).startsWith(key) } ?: return null
        val own = cell.substring(0, start).trim().trimEnd(':', ' ')
        val label = own.ifEmpty { if (at > 0) row.lines[at - 1].text.trim().trimEnd(':', ' ') else "" }
        return label.split(' ').filter { it.isNotEmpty() }.takeLast(LABEL_WORDS).joinToString(" ")
            .takeIf { squash(it).isNotEmpty() && squash(it) != key }
    }

    /** A short look at the zone before and the zone after an ask's zones, in reading order on page 1; either may be absent. */
    class Glimpse(val before: Pair<LetterZone, String>?, val after: Pair<LetterZone, String>?)

    /**
     * The last [maxLines] lines of the zone just above the asked [zones] and the first [maxLines] of the one just below,
     * on page 1 in reading order, each cut to [maxChars]. What this returns is context only: it is not part of the
     * asked zones and their candidates are never offered with it.
     */
    fun glimpse(zones: Collection<LetterZone>, maxLines: Int = GLIMPSE_LINES, maxChars: Int = GLIMPSE_CHARS): Glimpse {
        val lines = layout.pages.firstOrNull()?.lines.orEmpty().filter { !it.isNoise }
        val wanted = zones.toSet()
        val inside = lines.indices.filter { zoneOf(lines[it]) in wanted }
        if (inside.isEmpty()) return Glimpse(null, null)

        fun take(indices: List<Int>): String? {
            val picked = ArrayList<String>()
            var used = 0
            for (i in indices) {
                val t = lines[i].text.trim()
                if (t.isEmpty()) continue
                if (used + t.length > maxChars && picked.isNotEmpty()) break
                picked += t.take(maxChars)
                used += t.length + 3
                if (picked.size >= maxLines) break
            }
            return picked.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        }

        val start = inside.first()
        var i = start - 1
        while (i >= 0 && zoneOf(lines[i]) in wanted) i--
        val beforeZone = lines.getOrNull(i)?.let { zoneOf(it) }
        val before = beforeZone?.let { z ->
            val run = (i downTo 0).takeWhile { zoneOf(lines[it]) == z }.take(maxLines).reversed()
            take(run)?.let { z to it }
        }

        var j = inside.last() + 1
        while (j < lines.size && zoneOf(lines[j]) in wanted) j++
        val afterZone = lines.getOrNull(j)?.let { zoneOf(it) }
        val after = afterZone?.let { z ->
            val run = (j until lines.size).takeWhile { zoneOf(lines[it]) == z }.take(maxLines)
            take(run)?.let { z to it }
        }
        return Glimpse(before, after)
    }

    /** The offered candidates printed in any of [zones], in the table's order. */
    fun candidatesIn(zones: Collection<LetterZone>): OfferedCandidates {
        val wanted = zones.toSet()
        return OfferedCandidates(offered.rows.filter { row -> zonesOfCandidate(row.candidate.id).any { it in wanted } })
    }

    // ── text ─────────────────────────────────────────────────────────────────────

    private class Run(val page: Int, val order: Int, val zone: LetterZone, val text: String, val lineCount: Int)

    /** One piece of a page's text as the prompts print it: a line of a header block (or two on one row), or a whole row of the rest. */
    private class Item(val page: Int, val zone: LetterZone, val text: String)

    /**
     * The page's text in print order. The header blocks (letterhead, return line, address field, info block) first, a line each, as the
     * analyzer ordered them; then the rows of everything else in the physical order of the page (top to bottom, each row left to right),
     * the footer last. A row is one item whatever zones its cells were first given: a label and its value, or a position of a table, are
     * never split across runs.
     */
    private fun items(page: PageLayoutView): List<Item> {
        val lines = page.lines.filter { !it.isNoise }
        val out = ArrayList<Item>()
        var prev: LayoutLine? = null
        val builders = ArrayList<StringBuilder>()
        val zones = ArrayList<LetterZone>()
        for (l in lines.filter { it.zone in HEAD_ANALYZER_ZONES }) {
            val zone = zoneOf(l)
            if (prev != null && zoneOf(prev) == zone && sameRow(prev, l)) builders.last().append(' ').append(l.text.trim()) else { builders += StringBuilder(l.text.trim()); zones += zone }
            prev = l
        }
        builders.forEachIndexed { i, b -> out += Item(page.pageNumber, zones[i], b.toString()) }
        val rest = rowsByPage[page.pageNumber].orEmpty().filter { it.lines.first().zone !in HEAD_ANALYZER_ZONES }
        for (row in rest.sortedWith(compareBy({ if (it.lines.first().zone == LetterZone.FOOTER) 1 else 0 }, { it.centerY }))) {
            out += Item(page.pageNumber, zoneOf(row.lines.first()), row.text)
        }
        return out
    }

    private val itemsByPage: Map<Int, List<Item>> by lazy { layout.pages.associate { it.pageNumber to items(it) } }

    private fun runs(zones: Collection<LetterZone>): List<Run> {
        val wanted = zones.toSet()
        val out = ArrayList<Run>()
        for (page in layout.pages) {
            val items = itemsByPage[page.pageNumber].orEmpty()
            var i = 0
            var order = 0
            while (i < items.size) {
                val zone = items[i].zone
                val taken = ArrayList<String>()
                var count = 0
                while (i < items.size && items[i].zone == zone && count < MAX_RUN_LINES) {
                    if (zone in wanted && (zone !in HEADER_ZONES || page.pageNumber == 1)) taken += items[i].text
                    count++
                    i++
                }
                if (taken.isNotEmpty()) out += Run(page.pageNumber, order, zone, "[${zone.tag}] " + taken.joinToString(" / "), taken.size)
                order++
            }
        }
        return out
    }

    private fun sameRow(a: LayoutLine, b: LayoutLine): Boolean {
        val tol = maxOf(0.008f, 0.5f * minOf(a.bounds.height, b.bounds.height))
        return abs(a.bounds.centerY - b.bounds.centerY) <= tol && b.bounds.left > a.bounds.left + 0.05f
    }

    /** The text of one [zone], its rows one per piece, joined by " / ". Empty when the zone has no text. */
    fun zoneText(zone: LetterZone): String = layout.pages
        .filter { zone !in HEADER_ZONES || it.pageNumber == 1 }
        .flatMap { p -> itemsByPage[p.pageNumber].orEmpty().filter { it.zone == zone } }
        .joinToString(" / ") { it.text }

    /**
     * The text of [zones] in reading order, page by page, as tagged runs (`[body] a / b`), within [budgetChars].
     * When it does not fit, whole runs are dropped: payment and subject runs first kept, then the last page's
     * body, then the rest; what was left out is counted in a closing line.
     */
    fun render(zones: Collection<LetterZone>, budgetChars: Int = Int.MAX_VALUE): String = select(zones, budgetChars).let { sel ->
        val multiPage = layout.pages.size > 1
        val sb = StringBuilder()
        var page = -1
        for (r in sel.all.filter { it in sel.chosen }.sortedWith(compareBy({ it.page }, { it.order }))) {
            if (multiPage && r.page != page) {
                sb.append("=== PAGE ${r.page} ===\n")
                page = r.page
            }
            sb.append(r.text).append('\n')
        }
        if (sel.dropped > 0) sb.append("[... ${sel.dropped} more lines not shown]\n")
        sb.toString().trimEnd()
    }

    /** What [render] leaves out within [budgetChars]: how many lines, and the first page that lost a run (0 when nothing is left out). */
    class Coverage(val droppedLines: Int, val firstCutPage: Int)

    fun coverage(zones: Collection<LetterZone>, budgetChars: Int): Coverage =
        select(zones, budgetChars).let { sel -> Coverage(sel.dropped, sel.all.filter { it !in sel.chosen }.minOfOrNull { it.page } ?: 0) }

    private class Selection(val all: List<Run>, val chosen: Set<Run>, val dropped: Int)

    private fun select(zones: Collection<LetterZone>, budgetChars: Int): Selection {
        val all = runs(zones)
        val lastPage = layout.pages.lastOrNull()?.pageNumber ?: 1
        fun tier(r: Run) = when {
            r.zone == LetterZone.PAYMENT_SECTION || r.zone == LetterZone.SUBJECT -> 1
            r.zone == LetterZone.BODY && r.page == lastPage -> 2
            else -> 3
        }
        val chosen = HashSet<Run>()
        var used = 0
        var dropped = 0
        for (r in all.sortedWith(compareBy({ tier(it) }, { it.page }, { it.order }))) {
            val cost = r.text.length + 1
            if (used + cost <= budgetChars) {
                used += cost
                chosen += r
            } else {
                dropped += r.lineCount
            }
        }
        return Selection(all, chosen, dropped)
    }

    companion object {
        /** The zones that sit above the body on page 1: their text is short and belongs to one ask. */
        val HEADER_ZONES = setOf(LetterZone.LETTERHEAD, LetterZone.RETURN_ADDRESS_LINE, LetterZone.ADDRESS_FIELD, LetterZone.INFO_BLOCK)

        /** The analyzer's header blocks: their lines are printed one each, never merged into rows with the lines around them. */
        private val HEAD_ANALYZER_ZONES = setOf(LetterZone.LETTERHEAD, LetterZone.RETURN_ADDRESS_LINE, LetterZone.ADDRESS_FIELD, LetterZone.INFO_BLOCK)

        private const val MAX_RUN_LINES = 8
        private const val CONTEXT_CHARS = 90
        private const val LABEL_WORDS = 4

        /** How much of the neighbouring zones a glimpse shows: about two lines and 160 characters. */
        const val GLIMPSE_LINES = 2
        const val GLIMPSE_CHARS = 160

        fun squash(s: String): String = buildString { s.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) } }
    }
}
