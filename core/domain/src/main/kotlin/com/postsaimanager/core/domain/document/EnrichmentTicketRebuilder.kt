package com.postsaimanager.core.domain.document

import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.Document
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ReviewState

/**
 * Builds the ticket of a second stage that lost its own (see [DocumentProcessor.enrichDocument]): everything the ticket carried is
 * stored on the document already.
 *
 * - the family and the topics are the document's;
 * - the facts the summary rests on are the values the first stage's fields hold (the sender, the addressee, the main amount, the due
 *   date, the letter's date, the reference), as they are now: a value a person corrected is the better fact, a value a person
 *   ignored is no fact;
 * - the ids of the candidates the first stage took are not stored, so the ticket carries the values of those fields instead
 *   ([EnrichmentTicket.takenValues]): a candidate that reads as one of them is not offered as an extra.
 *
 * The first stage's reading trace (`established`) is not stored; the second stage reads the letter without it. Pure.
 */
object EnrichmentTicketRebuilder {

    fun rebuild(document: Document, fields: List<ExtractedData>): EnrichmentTicket {
        val live = fields.filter { it.reviewState != ReviewState.IGNORED && !it.deletedByUser && it.fieldValue.isNotBlank() }
        fun value(slotKey: String): String? = live.firstOrNull { it.slotKey == slotKey }?.fieldValue?.trim()
        val facts = SummaryFacts(
            familyId = document.extractionType.orEmpty(),
            sender = value(UnderstandingToFields.SLOT_SENDER),
            addressee = value(UnderstandingToFields.SLOT_ADDRESSEE),
            amount = value(Slots.TOTAL.json),
            dueDate = value(Slots.DUE_DATE.json),
            date = value(Slots.LETTER_DATE.json),
            reference = value(Slots.REFERENCE.json),
        )
        // What the second stage itself owns (the extras and the subject line) is not "taken": it is what the stage writes.
        val taken = live.filterNot(UnderstandingToFields::writtenInSecondStage).flatMap { listOfNotNull(it.fieldValue.trim(), it.machineValue?.trim()) }
        return EnrichmentTicket(
            typeId = document.extractionType,
            topics = document.topics,
            facts = facts.carried(),
            takenValues = taken.filter { it.isNotEmpty() }.distinct(),
        )
    }
}
