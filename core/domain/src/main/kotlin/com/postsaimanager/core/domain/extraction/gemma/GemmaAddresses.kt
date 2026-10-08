package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.address.AddressLineLabeler
import com.postsaimanager.core.domain.extraction.address.AddressReading
import com.postsaimanager.core.domain.extraction.address.StructuredAddressReader
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.v2.ConfidenceCombiner
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.Parties
import com.postsaimanager.core.domain.extraction.v2.Party
import com.postsaimanager.core.domain.extraction.v2.PartyRelation
import com.postsaimanager.core.domain.extraction.v2.SlotOrigin
import com.postsaimanager.core.domain.extraction.v2.SlotValue

/**
 * The structured postal addresses of a Gemma reading: the sender's (the letterhead, the return line or the footer) and the addressee's
 * (the address field), read by the shapes of their lines alone ([StructuredAddressReader] with no scoring session: Gemma's one call has
 * already decided who is who, and a second model pass for the address would only repeat the wait).
 *
 * The scoring reading stores these as the `sender.*` and `addressee.*` rows; the after-reading steps (the organisation's suggested
 * address) read those rows, so a reading without them would leave a letter's address unseen. The result goes into
 * [com.postsaimanager.core.domain.extraction.v2.RawInterpretation.addresses].
 */
class GemmaAddresses(private val reader: StructuredAddressReader = StructuredAddressReader(AddressLineLabeler())) {

    /** The addresses for the parties [verified] named, as [AddressReading]; empty when the layout shows none. */
    suspend fun read(layout: LetterLayout, offered: OfferedCandidates, verified: VerifiedReading): AddressReading =
        reader.read(layout, partiesOf(offered, verified), match = null, session = null)

    /** The parties as the address reader takes them: the names as printed, from the name candidates the model chose (a quoted line has no candidate). */
    private fun partiesOf(offered: OfferedCandidates, verified: VerifiedReading): Parties = Parties(
        verified.parties.mapNotNull { p ->
            val c = p.candidateId?.let { offered.get(it) } ?: return@mapNotNull null
            val ai = ConfidenceCombiner.aiScore(CONFIDENT)
            val value = SlotValue(
                slot = null, candidateId = c.id, value = c.raw.trim(), normalized = c.normalized, page = c.page, bbox = c.bbox,
                evidence = c.evidence, origin = SlotOrigin.MODEL_CHOICE, aiConfidence = ai, confidence = ai, validation = c.validation,
                role = p.role.name,
            )
            Party(p.role, p.kind, PartyRelation.NONE, value)
        },
    )

    private companion object {
        const val CONFIDENT = "HIGH"
    }
}
