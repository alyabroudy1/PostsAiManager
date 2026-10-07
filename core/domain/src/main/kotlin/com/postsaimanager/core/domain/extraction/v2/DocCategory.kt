package com.postsaimanager.core.domain.extraction.v2

/**
 * A broad kind of document, the first level of "what is this": a short list the reading scores AFTER it has read the facts (the sender, the
 * dates and amounts with their meanings), and a label only. It feeds the list tag, the filters, search and the chat's context; it decides
 * no question that is asked of a document.
 *
 * The stored id of a document stays a family id ([DocFamily.id]; "no DB change"), so the category is a mapping over the registry, kept as data:
 * [families] lists the stored ids that stand for the category, the first one valid for a document's direction being the one that is scored
 * and stored (its [DocFamily.description] is the scoring statement, so a recorded score of it still replays). The ids that are not first (the
 * legacy "medical", "ticket_booking", "certificate_id" of documents read before the categories) are read as the category through
 * [ExtractionSchema.categoryOf], so an old document gets the new tag and filter without being re-read.
 *
 * @property id a readable name of the category, for traces and tests (never shown, never parsed)
 * @property phrase the category as the user's own correction is given to the model as context: "The user says this document is <phrase>."
 * @property families the stored family ids that stand for this category, the scored one first; ids a schema does not hold are skipped
 */
data class DocCategory(val id: String, val phrase: String, val families: List<String>) {
    override fun toString() = id

    companion object {
        /**
         * The nine categories, in the order they are scored. The order is the registry's order of the families that were recorded on the
         * device (letter, bill, receipt, form, statement, contract), then the three that were never recorded, so a replay of those recordings
         * still finds its scores in order.
         */
        val DEFAULT: List<DocCategory> = listOf(
            DocCategory("letter", "a letter", listOf("official_letter", "outgoing_letter")),
            DocCategory("bill", "a bill or an invoice", listOf("invoice_bill")),
            DocCategory("receipt", "a receipt", listOf("receipt", "payment_proof")),
            DocCategory("form", "a form", listOf("form_application")),
            DocCategory("statement", "a statement", listOf("statement")),
            DocCategory("contract", "a contract or a policy", listOf("contract_policy")),
            DocCategory("appointment", "an appointment or a booking", listOf("appointment_reminder", "medical", "ticket_booking")),
            DocCategory("message", "a message or a note", listOf("message_note")),
            DocCategory("notice", "a notice or a decision", listOf("notice_decision", "certificate_id")),
        )
    }
}
