package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.model.AddressPart

/**
 * What a line of an address block can be told to be, as statements the model scores ("Is «X» <statement>?"): the labels of the
 * word-only lines and the two kinds of delivery point a street-shaped line may really be. Data, written by what the line does in a
 * letter; the model's scores decide, never a word list (a label is never generated, see the scoring lesson in the architecture).
 */
enum class LineAsk(val part: AddressPart, val statement: String) {
    PERSON(AddressPart.RECIPIENT_NAME, "the name of a private person"),
    ORGANISATION(AddressPart.ORGANISATION, "the name of a company, an authority or another organisation"),
    DEPARTMENT(AddressPart.DEPARTMENT, "a department or unit inside an organisation"),
    ROUTING(AddressPart.CARE_OF, "a routing instruction: the letter is sent care of someone else or for the attention of a named person"),
    PO_BOX(AddressPart.PO_BOX, "a post office box number, not a street"),
    PACKSTATION(AddressPart.PACKSTATION, "a parcel locker or pickup station, not a street"),
    ;

    companion object {
        /** The labels of a word-only line, in the order they are asked. */
        val LABELS: List<LineAsk> = listOf(PERSON, ORGANISATION, DEPARTMENT, ROUTING)

        /** What a street-shaped line may be instead of a street. */
        val DELIVERY_POINTS: List<LineAsk> = listOf(PO_BOX, PACKSTATION)

        /** The scoring name of the labels, for [com.postsaimanager.core.domain.extraction.zones.ScoringProfile.threshold]. */
        const val LABEL_ASK = "addr_label"

        /** The scoring name of the delivery points: a line is one only when the model leans Yes. */
        const val DELIVERY_ASK = "addr_delivery"
    }
}

/**
 * How many scores an address costs, by data: the most cells (one line under one statement) a block may use. The recipient's block is the
 * one the letter is about; a sender's address is checked against its own name already, so it gets a third.
 */
data class AddressBudget(val recipientCells: Int = 16, val senderCells: Int = 8)
