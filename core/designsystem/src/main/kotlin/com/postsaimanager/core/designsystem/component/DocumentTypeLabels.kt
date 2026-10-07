package com.postsaimanager.core.designsystem.component

import androidx.annotation.StringRes
import com.postsaimanager.core.designsystem.R

/**
 * The one owner of the words of a document type (a family id of the extraction registry): the list's type tag, the detail header and
 * the composed texts all read them from here. Data holds ids, never words; a new family is one line here and one string per language
 * (`SlotLabelsTest` fails until every family of the registry has one).
 *
 * What a tag says is the document's broad CATEGORY (letter, bill, appointment, contract, statement, receipt, form, message, notice), so the
 * stored ids that stand for a category share its words: the label mapping is migrated here, in code, and no stored id changes (the
 * mapping of ids to categories is `ExtractionSchema.categoryOf`, which this module cannot read; the two lists are kept in step by
 * `SlotLabelsTest`).
 */
object DocumentTypeLabels {

    private val labels: Map<String, Int> = mapOf(
        // letter
        "official_letter" to R.string.doctype_official_letter,
        "outgoing_letter" to R.string.doctype_official_letter,
        // bill or invoice
        "invoice_bill" to R.string.doctype_invoice_bill,
        // receipt
        "receipt" to R.string.doctype_receipt,
        "payment_proof" to R.string.doctype_receipt,
        // form
        "form_application" to R.string.doctype_form_application,
        // statement
        "statement" to R.string.doctype_statement,
        // contract
        "contract_policy" to R.string.doctype_contract_policy,
        // appointment
        "appointment_reminder" to R.string.doctype_appointment_reminder,
        "medical" to R.string.doctype_appointment_reminder,
        "ticket_booking" to R.string.doctype_appointment_reminder,
        // message or note
        "message_note" to R.string.doctype_message_note,
        // notice or decision
        "notice_decision" to R.string.doctype_notice_decision,
        "certificate_id" to R.string.doctype_notice_decision,
        // the neutral "Document"
        "free_form" to R.string.doctype_free_form,
    )

    /** The label of the family [id], or null for an id with none. */
    @StringRes
    fun of(id: String?): Int? = id?.let(labels::get)

    /** Every family id that has a label; for the test that guards the registry. */
    val ids: Set<String> get() = labels.keys
}
