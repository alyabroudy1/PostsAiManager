package com.postsaimanager.core.designsystem.component

import androidx.annotation.StringRes
import com.postsaimanager.core.designsystem.R

/**
 * The one owner of the words of a document type (a family id of the extraction registry): the list's type tag, the detail header and
 * the composed texts all read them from here. Data holds ids, never words; a new family is one line here and one string per language
 * (`SlotLabelsTest` fails until every family of the registry has one).
 */
object DocumentTypeLabels {

    private val labels: Map<String, Int> = mapOf(
        "official_letter" to R.string.doctype_official_letter,
        "invoice_bill" to R.string.doctype_invoice_bill,
        "receipt" to R.string.doctype_receipt,
        "form_application" to R.string.doctype_form_application,
        "statement" to R.string.doctype_statement,
        "contract_policy" to R.string.doctype_contract_policy,
        "certificate_id" to R.string.doctype_certificate_id,
        "medical" to R.string.doctype_medical,
        "ticket_booking" to R.string.doctype_ticket_booking,
        "appointment_reminder" to R.string.doctype_appointment_reminder,
        "message_note" to R.string.doctype_message_note,
        "outgoing_letter" to R.string.doctype_outgoing_letter,
        "payment_proof" to R.string.doctype_payment_proof,
        "free_form" to R.string.doctype_free_form,
    )

    /** The label of the family [id], or null for an id with none. */
    @StringRes
    fun of(id: String?): Int? = id?.let(labels::get)

    /** Every family id that has a label; for the test that guards the registry. */
    val ids: Set<String> get() = labels.keys
}
