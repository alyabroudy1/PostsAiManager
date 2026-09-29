package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import kotlin.math.abs

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

    /** The zone [line] belongs to under this template. */
    fun zoneOf(line: LayoutLine): LetterZone = template.remap[line.zone] ?: line.zone

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

    /** The line a candidate is printed on and the lines just above and below it (page order), for a scoring question. Nulls when not found. */
    class Context(val above: String?, val line: String?, val below: String?)

    fun context(c: Candidate): Context {
        val lines = layout.page(c.page)?.lines.orEmpty().filter { !it.isNoise }
        val key = squash(c.raw)
        if (key.isEmpty()) return Context(null, null, null)
        val box = c.bbox
        val at = lines.indices.filter { squash(lines[it].text).contains(key) }
            .minByOrNull { i -> if (box == null) i.toFloat() else abs(lines[i].bounds.centerX - box.centerX) + abs(lines[i].bounds.centerY - box.centerY) }
            ?: return Context(null, null, null)
        fun cut(i: Int) = lines.getOrNull(i)?.text?.trim()?.take(CONTEXT_CHARS)
        return Context(cut(at - 1), cut(at), cut(at + 1))
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

    private fun runs(zones: Collection<LetterZone>): List<Run> {
        val wanted = zones.toSet()
        val out = ArrayList<Run>()
        for (page in layout.pages) {
            val lines = page.lines.filter { !it.isNoise }
            var i = 0
            var order = 0
            while (i < lines.size) {
                val zone = zoneOf(lines[i])
                val items = ArrayList<StringBuilder>()
                var prev: LayoutLine? = null
                var count = 0
                while (i < lines.size && zoneOf(lines[i]) == zone && count < MAX_RUN_LINES) {
                    val l = lines[i]
                    if (zone in wanted && (zone !in HEADER_ZONES || page.pageNumber == 1)) {
                        if (prev != null && sameRow(prev, l)) items.last().append(' ').append(l.text.trim()) else items += StringBuilder(l.text.trim())
                    }
                    count++
                    prev = l
                    i++
                }
                if (items.isNotEmpty()) {
                    out += Run(page.pageNumber, order, zone, "[${zone.tag}] " + items.joinToString(" / "), items.size)
                }
                order++
            }
        }
        return out
    }

    private fun sameRow(a: LayoutLine, b: LayoutLine): Boolean {
        val tol = maxOf(0.008f, 0.5f * minOf(a.bounds.height, b.bounds.height))
        return abs(a.bounds.centerY - b.bounds.centerY) <= tol && b.bounds.left > a.bounds.left + 0.05f
    }

    /** The text of one [zone], its lines one per row, joined by " / ". Empty when the zone has no text. */
    fun zoneText(zone: LetterZone): String {
        val rows = ArrayList<StringBuilder>()
        var prev: LayoutLine? = null
        for (l in lines(zone)) {
            if (prev != null && prev.page == l.page && sameRow(prev, l)) rows.last().append(' ').append(l.text.trim()) else rows += StringBuilder(l.text.trim())
            prev = l
        }
        return rows.joinToString(" / ")
    }

    /**
     * The text of [zones] in reading order, page by page, as tagged runs (`[body] a / b`), within [budgetChars].
     * When it does not fit, whole runs are dropped: payment and subject runs first kept, then the last page's
     * body, then the rest; what was left out is counted in a closing line.
     */
    fun render(zones: Collection<LetterZone>, budgetChars: Int = Int.MAX_VALUE): String {
        val all = runs(zones)
        val lastPage = layout.pages.lastOrNull()?.pageNumber ?: 1
        fun tier(r: Run) = when {
            r.zone == LetterZone.PAYMENT_SECTION || r.zone == LetterZone.SUBJECT -> 1
            r.zone == LetterZone.BODY && r.page == lastPage -> 2
            else -> 3
        }
        val multiPage = layout.pages.size > 1
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
        val sb = StringBuilder()
        var page = -1
        for (r in all.filter { it in chosen }.sortedWith(compareBy({ it.page }, { it.order }))) {
            if (multiPage && r.page != page) {
                sb.append("=== PAGE ${r.page} ===\n")
                page = r.page
            }
            sb.append(r.text).append('\n')
        }
        if (dropped > 0) sb.append("[... $dropped more lines not shown]\n")
        return sb.toString().trimEnd()
    }

    companion object {
        /** The zones that sit above the body on page 1: their text is short and belongs to one ask. */
        val HEADER_ZONES = setOf(LetterZone.LETTERHEAD, LetterZone.RETURN_ADDRESS_LINE, LetterZone.ADDRESS_FIELD, LetterZone.INFO_BLOCK)

        private const val MAX_RUN_LINES = 8
        private const val CONTEXT_CHARS = 70

        /** How much of the neighbouring zones a glimpse shows: about two lines and 160 characters. */
        const val GLIMPSE_LINES = 2
        const val GLIMPSE_CHARS = 160

        fun squash(s: String): String = buildString { s.forEach { if (it.isLetterOrDigit()) append(it.lowercaseChar()) } }
    }
}
