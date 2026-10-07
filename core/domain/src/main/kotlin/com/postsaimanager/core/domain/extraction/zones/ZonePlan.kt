package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.Slots

/**
 * Which questions are asked on which zones of a letter, worked out from a template (data) and the
 * schema (data). No text is read.
 *
 * Two phases, because what a question needs to see decides how it is asked:
 *  - **header**: the questions whose zones all sit in the header (letterhead, return line, address field,
 *    reference block). Each is asked with its own short zone text.
 *  - **body**: every other question. The body text is read once as the session's prefix and its questions
 *    follow, and the document type is asked first, with the header's answers summarised beside the text.
 */
class ZonePlan(private val template: LayoutTemplate, private val zoned: ZonedLetter) {

    /**
     * The zones a question PREFERS, after the template's remap, in the template's order: where its candidates usually are, so the
     * first to be shown and offered. Never a gate: [offer] still offers every candidate of the page. A question the template places
     * in no zone takes the registry's usual zones ([SlotPlacements]); empty only for a question the registry does not know either.
     */
    fun zones(name: String, slot: SlotKey? = null): List<LetterZone> {
        val fromSpecs = template.zones.filter { name in it.asks }.map { zoned.mapped(it.zone) }
        val raw = when {
            fromSpecs.isNotEmpty() -> fromSpecs
            slot != null -> SlotPlacements.zonesFor(slot, template).map { zoned.mapped(it) }
            else -> SlotPlacements.partyZones(name).map { zoned.mapped(it) }
        }
        return raw.distinct()
    }

    /**
     * The candidates of one question, in the order they are offered: those printed in a [preferred] zone first, then those in a
     * [fallback] zone, then every other candidate of the page; each group keeps the candidate table's order. The layout only orders:
     * nothing is removed because of where it is printed. The only limit is [MAX_OFFERED], applied AFTER the ordering, so the preferred
     * and fallback zones fill it first; a question's preferred candidates are never cut (they are what the question has always scored),
     * only the candidates beyond them are.
     */
    fun offer(candidates: List<Candidate>, preferred: Collection<LetterZone>, fallback: Collection<LetterZone> = emptyList()): List<Candidate> {
        fun rank(c: Candidate): Int {
            val at = zoned.zonesOfCandidate(c.id)
            return when {
                at.any { it in preferred } -> 0
                at.any { it in fallback } -> 1
                else -> 2
            }
        }
        val ranked = candidates.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
        val keep = maxOf(MAX_OFFERED, candidates.count { rank(it) == 0 })
        return ranked.take(keep)
    }

    companion object {
        /** The most candidates one question scores, beyond what its preferred zones hold: the cost of reading every candidate of a page is bounded here. */
        const val MAX_OFFERED = 24
    }

    fun isHeader(zones: List<LetterZone>): Boolean = zones.isNotEmpty() && zones.all { it in ZonedLetter.HEADER_ZONES }

    /** The hint the template gives for [zone]; a generic sentence for a zone the template does not describe. */
    fun hint(zone: LetterZone): String =
        template.zones.firstOrNull { zoned.mapped(it.zone) == zone }?.hint ?: "This is part of the letter."

    /** The universal slots, in the order they are asked. */
    val coreSlots: List<SlotKey> = Slots.CORE
}
