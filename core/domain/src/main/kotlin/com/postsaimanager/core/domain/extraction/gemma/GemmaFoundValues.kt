package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.address.AddressReading
import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates
import com.postsaimanager.core.domain.extraction.v2.RawInterpretation
import com.postsaimanager.core.domain.extraction.v2.RawSlot

/**
 * What code found in the letter that Gemma's answer does not carry, added to the mapped reading so the steps after a reading have it:
 *
 * - the structured **addresses** (the sender's feeds the organisation's suggested address; see [GemmaAddresses]);
 * - the letter's own **date**, kept in the document-date slot when the model named no date as the letter's own: the timeline dates an
 *   event by it ([com.postsaimanager.core.domain.timeline.EventDateResolver]), and without it a letter would be dated by the day it was
 *   scanned. Only the layout speaks here, never a word: the one date printed in the letterhead or the reference block of page 1. A letter
 *   with none or several there gets none. It is stored at the "found" level of confidence with no meaning attached, and a date the
 *   model gave that meaning is never replaced.
 *
 * Nothing here decides what a value means.
 */
class GemmaFoundValues(private val schema: ExtractionSchema = ExtractionSchema.DEFAULT) {

    fun apply(raw: RawInterpretation, addresses: AddressReading?, letterDate: Candidate?): RawInterpretation {
        val withAddresses = addresses?.let {
            raw.copy(addresses = it.addresses, senderAddressAlternatives = it.senderAlternatives)
        } ?: raw
        return withLetterDate(withAddresses, letterDate)
    }

    /**
     * The only date candidate of page 1's letterhead and reference block, or null (none, or several: the layout does not say which is the
     * letter's). A clock time on its own (the "09:41" of a screenshot's status bar) is no date, so it is neither the letter's date nor one
     * of the several: the date is typed as a calendar date before the layout is asked.
     */
    fun candidateOf(letter: GemmaLetter, offered: OfferedCandidates): Candidate? =
        letter.candidatesOf(CandidateKind.DATE, CandidateKind.DATETIME)
            .filter { c -> c.lineId?.let(letter::line)?.let { it.page == 1 && it.zone in HEADER_ZONES } == true }
            .mapNotNull { offered.get(it.id) }
            .filter { it.attrs["timeOnly"] == null }
            .singleOrNull()

    private fun withLetterDate(raw: RawInterpretation, letterDate: Candidate?): RawInterpretation {
        val key = MeaningSlots.slotOf(MeaningKind.DATE, LETTER_DATE) ?: return raw
        if (letterDate == null || raw.slots.containsKey(key)) return raw
        val slot = schema.allSlots.firstOrNull { it.json == key } ?: return raw
        return raw.copy(slots = raw.slots + (key to RawSlot(id = letterDate.id, role = roleOf(slot), confidence = FOUND_CONFIDENCE)))
    }

    private companion object {
        const val LETTER_DATE = "LETTER_DATE"
        const val FOUND_CONFIDENCE = "MEDIUM"
        val HEADER_ZONES = setOf(LetterZone.LETTERHEAD.tag, LetterZone.INFO_BLOCK.tag)
    }
}
