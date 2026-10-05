package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.extraction.layout.AddressShapes
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.zones.LayoutTemplate
import com.postsaimanager.core.domain.extraction.zones.LayoutTemplates
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import com.postsaimanager.core.domain.extraction.zones.TemplateMatch
import com.postsaimanager.core.model.PostalAddress

/** What [StructuredAddressReader] read: the addresses by role, the sender's other candidates, and the scores spent. */
class AddressReading(
    val addresses: Map<PartyRole, PostalAddress>,
    val senderAlternatives: List<PostalAddress> = emptyList(),
    /** The scores (cells) spent on the recipient's block and on the sender's. */
    val recipientCells: Int = 0,
    val senderCells: Int = 0,
    /** The recipient's block was read from the runner-up template's address zone. */
    val retried: Boolean = false,
)

/**
 * Stage 9 and 10 of the extraction: the structured address of the addressee and of the sender.
 *
 * - **Recipient:** the page-1 `ADDRESS_FIELD` lines. The **generic retry**: when that zone holds no postcode-shaped line and the
 *   runner-up template is within [ScoringProfile.addressRetryMargin] of the chosen one, the runner-up's own address region is read
 *   instead, once (the shape pass looks at both at no cost; only the zone that shows a postcode line is scored). No country, layout
 *   name or language is in this code: the runner-up is whatever the matcher ranked second, its region is data.
 * - **Sender:** the return line, the letterhead and the footer are shaped without scores, [SenderAddressPicker] takes the first that
 *   verifies, and only the winner's leftover word lines are scored, within the sender budget. The other candidates are the alternatives.
 *
 * Every address goes through [AddressVerifier]. The scores run on the letter's open [PromptSession] (the interpreter's session).
 * `ZoneScoringInterpreter` calls [read] after the parties are settled, for a family with a recipient block; the verifier carries the
 * result into `ExtractionV2Result.addresses` and `senderAddressAlternatives`.
 */
class StructuredAddressReader(
    private val labeler: AddressLineLabeler,
    private val verifier: AddressVerifier = AddressVerifier(),
    private val picker: SenderAddressPicker = SenderAddressPicker(),
    private val finder: SenderCandidateFinder = SenderCandidateFinder(),
    private val profile: ScoringProfile = ScoringProfile(),
    private val budget: AddressBudget = AddressBudget(),
) {

    /**
     * @param match the layout template match of the letter (for the retry); null reads the zone as it is.
     * @param session the open letter session, or null to read by shape only (no scores).
     * @param tail the closing text of the session's questions (what the interpreter's own scoring questions end with).
     */
    suspend fun read(
        layout: LetterLayout,
        parties: Parties = Parties(),
        match: TemplateMatch? = null,
        session: PromptSession? = null,
        tail: String = "",
    ): AddressReading {
        val addresses = linkedMapOf<PartyRole, PostalAddress>()

        // Recipient.
        val (zoneLines, retried) = recipientLines(layout, match)
        var recipientCells = 0
        if (zoneLines.isNotEmpty()) {
            val scoring = session?.let { AddressLineLabeler.Scoring(it, budget.recipientCells, tail) }
            val labeled = labeler.label(zoneLines, parties, scoring)
            recipientCells = labeled.cells
            val names = (parties.allAddressees).map { it.name }
            addresses[PartyRole.ADDRESSEE] = verifier.verify(labeled, names)
        }

        // Sender: shape every place, pick, then score the winner only.
        val senderParties = Parties(parties.withRole(PartyRole.SENDER))
        val senderNames = senderParties.all.map { it.name }
        val places = finder.find(layout)
        val shaped = places.map { place -> place to verifier.verify(labeler.shape(place.lines, senderParties), senderNames) }
        val pick = picker.pick(shaped.map { (place, address) -> SenderCandidate(place.source, address) })
        var senderCells = 0
        var senderAlternatives = emptyList<PostalAddress>()
        if (pick.address != null && pick.source != null) {
            val place = places.first { it.source == pick.source }
            var sender = pick.address
            if (session != null) {
                val labeled = labeler.label(place.lines, senderParties, AddressLineLabeler.Scoring(session, budget.senderCells, tail))
                senderCells = labeled.cells
                sender = verifier.verify(labeled, senderNames)
            }
            addresses[PartyRole.SENDER] = sender
            senderAlternatives = pick.alternatives.map { it.address }
        }
        return AddressReading(addresses, senderAlternatives, recipientCells, senderCells, retried)
    }

    /** The address zone's lines, or, by the generic retry, the lines of the runner-up template's address region. */
    private fun recipientLines(layout: LetterLayout, match: TemplateMatch?): Pair<List<AddressLine>, Boolean> {
        val first = AddressLines.joinRows(AddressLines.of(layout.zone(LetterZone.ADDRESS_FIELD)))
        if (first.any { AddressShapes.isPostcodeLine(it.text) } || match == null) return first to false
        val region = runnerUp(match)?.signature?.zones?.firstOrNull { it.zone == LetterZone.ADDRESS_FIELD }?.region ?: return first to false
        val page = layout.page(1)?.lines.orEmpty().filter { line ->
            !line.isNoise && line.zone !in EXCLUDED_ZONES && region.contains(line.bounds.centerX, line.bounds.centerY)
        }
        val lines = AddressLines.joinRows(AddressLines.of(page))
        val anchor = lines.indexOfLast { AddressShapes.isPostcodeLine(it.text) }
        if (anchor < 0) return first to false
        return AddressLines.stackEndingAt(lines, anchor) to true
    }

    /** The template ranked next to the chosen one, when it is within the profile's margin of it. */
    private fun runnerUp(match: TemplateMatch): LayoutTemplate? {
        val ranked = match.scores.entries.filter { it.key != match.template.id }.sortedByDescending { it.value }
        val next = ranked.firstOrNull() ?: return null
        if (match.score - next.value > profile.addressRetryMargin) return null
        return LayoutTemplates.byId(next.key)
    }

    private companion object {
        /** Zones whose lines are never part of the addressee's block, whatever region they sit in. */
        val EXCLUDED_ZONES = setOf(LetterZone.RETURN_ADDRESS_LINE, LetterZone.INFO_BLOCK, LetterZone.FOOTER, LetterZone.PAYMENT_SECTION)
    }
}
