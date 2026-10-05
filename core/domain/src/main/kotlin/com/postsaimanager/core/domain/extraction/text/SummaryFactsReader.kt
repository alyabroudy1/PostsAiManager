package com.postsaimanager.core.domain.extraction.text

import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.SlotKey
import com.postsaimanager.core.domain.extraction.v2.Slots

/**
 * The one place that reads the facts a summary rests on out of a verified [ExtractionV2Result]: the sender and the addressee the
 * parties settled, the letter's main amount, due date and date, its reference, and the quote-verified subject line. Every value is
 * one the verifier already accepted (a name or value found in the letter), so [SummaryGate] can treat them as the letter's own words.
 *
 * Pure. The result is the first stage's when a ticket is written ([SummaryFacts.carried]) and the whole reading's otherwise.
 */
object SummaryFactsReader {

    fun of(result: ExtractionV2Result): SummaryFacts {
        fun slot(key: SlotKey): String? = result.slots.entries.firstOrNull { it.key.json == key.json }?.value?.value
        return SummaryFacts(
            familyId = result.documentType?.id.orEmpty(),
            sender = result.parties.sender?.name,
            addressee = result.parties.addressees.firstOrNull()?.name,
            amount = slot(Slots.TOTAL),
            dueDate = slot(Slots.DUE_DATE),
            date = slot(Slots.LETTER_DATE),
            subject = result.freeText.subject?.value,
            reference = slot(Slots.REFERENCE),
        )
    }
}
