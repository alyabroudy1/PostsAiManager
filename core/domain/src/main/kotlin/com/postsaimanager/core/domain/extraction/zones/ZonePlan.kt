package com.postsaimanager.core.domain.extraction.zones

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

    /** The zones a question is asked on, after the template's remap; empty when the template does not place it. */
    fun zones(name: String, slot: SlotKey? = null): List<LetterZone> {
        val fromSpecs = template.zones.filter { name in it.asks }.map { zoned.mapped(it.zone) }
        val raw = if (fromSpecs.isNotEmpty() || slot == null) fromSpecs else SlotPlacements.zonesFor(slot, template).map { zoned.mapped(it) }
        return raw.distinct()
    }

    fun isHeader(zones: List<LetterZone>): Boolean = zones.isNotEmpty() && zones.all { it in ZonedLetter.HEADER_ZONES }

    /** The hint the template gives for [zone]; a generic sentence for a zone the template does not describe. */
    fun hint(zone: LetterZone): String =
        template.zones.firstOrNull { zoned.mapped(it.zone) == zone }?.hint ?: "This is part of the letter."

    /** The universal slots, in the order they are asked. */
    val coreSlots: List<SlotKey> = Slots.CORE
}
