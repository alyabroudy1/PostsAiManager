package com.postsaimanager.feature.documents

import androidx.annotation.StringRes
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.ExtractedData
import com.postsaimanager.core.model.ValueSource

/**
 * The string resource each slot key and document type id is rendered from. Data holds keys, never
 * English; a new slot or type needs one line here and one string per language (a test fails until
 * every slot in [ExtractionSchema.DEFAULT] has both).
 */
object SlotLabels {

    private val slots: Map<String, Int> = mapOf(
        UnderstandingToFields.SLOT_SENDER to R.string.slot_sender,
        UnderstandingToFields.SLOT_ADDRESSEE to R.string.slot_addressee,
        UnderstandingToFields.SLOT_CONTACT to R.string.slot_contact,
        UnderstandingToFields.SLOT_SUBJECT to R.string.slot_subject,
        UnderstandingToFields.SLOT_UNLABELLED to R.string.slot_unlabelled,
        Slots.LETTER_DATE.json to R.string.slot_letter_date,
        Slots.TOTAL.json to R.string.slot_total,
        Slots.DUE_DATE.json to R.string.slot_due_date,
        Slots.IBAN.json to R.string.slot_iban,
        Slots.REFERENCE.json to R.string.slot_reference,
        Slots.CUSTOMER_NO.json to R.string.slot_customer_no,
        Slots.FEE.json to R.string.slot_fee,
        Slots.NEW_AMOUNT.json to R.string.slot_new_amount,
        Slots.PREVIOUS_AMOUNT.json to R.string.slot_previous_amount,
        Slots.PROOF_AMOUNT.json to R.string.slot_proof_amount,
        Slots.OBJECTION_DEADLINE.json to R.string.slot_objection_deadline,
        Slots.ORIGINAL_DUE_DATE.json to R.string.slot_original_due_date,
        Slots.EFFECTIVE_DATE.json to R.string.slot_effective_date,
        Slots.CONTRACT_END.json to R.string.slot_contract_end,
        Slots.EVENT_DATE.json to R.string.slot_event_date,
        Slots.APPOINTMENT.json to R.string.slot_appointment,
        Slots.SENT_DATE.json to R.string.slot_sent_date,
        Slots.PROOF_DATE.json to R.string.slot_proof_date,
        Slots.INVOICE_NO.json to R.string.slot_invoice_no,
        Slots.CONTRACT_NO.json to R.string.slot_contract_no,
        Slots.POLICY_NO.json to R.string.slot_policy_no,
        Slots.CASE_NO.json to R.string.slot_case_no,
        Slots.TAX_NO.json to R.string.slot_tax_no,
        Slots.RECEIPT_NO.json to R.string.slot_receipt_no,
        Slots.PROOF_REFERENCE.json to R.string.slot_proof_reference,
        Slots.RECIPIENT_ORG.json to R.string.slot_recipient_org,
        Slots.PROOF_RECIPIENT.json to R.string.slot_proof_recipient,
        Slots.CITED_REFERENCES.json to R.string.slot_cited_references,
        Slots.ACTION_KIND.json to R.string.slot_action_kind,
    )

    private val types: Map<String, Int> = mapOf(
        ExtractionSchema.BILL.id to R.string.doctype_bill,
        ExtractionSchema.REMINDER_DUNNING.id to R.string.doctype_reminder_dunning,
        ExtractionSchema.AUTHORITY_TAX.id to R.string.doctype_authority_tax,
        ExtractionSchema.HEALTH.id to R.string.doctype_health,
        ExtractionSchema.INSURANCE_CONTRACT.id to R.string.doctype_insurance_contract,
        ExtractionSchema.SCHOOL.id to R.string.doctype_school,
        ExtractionSchema.RECEIPT.id to R.string.doctype_receipt,
        ExtractionSchema.INFO_NO_ACTION.id to R.string.doctype_info_no_action,
        ExtractionSchema.OUTGOING_LETTER.id to R.string.doctype_outgoing_letter,
        ExtractionSchema.PAYMENT_PROOF.id to R.string.doctype_payment_proof,
        ExtractionSchema.OTHER.id to R.string.doctype_other,
    )

    /** The label resource for a slot key, or null for a key with none (an extra, or a name a person typed). */
    @StringRes
    fun slot(key: String?): Int? = key?.let(slots::get)

    /** The words of a found value's label: [res] takes [number] as its one argument ("Found date %1$d"). */
    data class FoundLabel(@StringRes val res: Int, val number: Int)

    private val foundKinds: Map<CandidateKind, Int> = mapOf(
        CandidateKind.DATE to R.string.found_date,
        CandidateKind.AMOUNT to R.string.found_amount,
        CandidateKind.IBAN to R.string.found_iban,
        CandidateKind.REFERENCE to R.string.found_reference,
        CandidateKind.PHONE to R.string.found_phone,
        CandidateKind.EMAIL to R.string.found_email,
    )

    /** The label of a value code found (`found:DATE:1`), or null when [key] is not a found key. */
    fun found(key: String?): FoundLabel? {
        val (kind, number) = ExtractionV2Adapter.parseFoundKey(key) ?: return null
        return foundKinds[kind]?.let { FoundLabel(it, number) }
    }

    /**
     * The name to show for an extra stored under its bare slot key (`x:amount`), which happens when its
     * printed label collided with a fixed field's name; null for any other name. The key's own words,
     * since the printed label is not kept apart from the name.
     */
    fun extraKeyName(name: String): String? =
        name.takeIf { it.startsWith(ExtractedData.EXTRA_KEY_PREFIX) }
            ?.removePrefix(ExtractedData.EXTRA_KEY_PREFIX)?.replace('_', ' ')?.ifBlank { null }

    @StringRes
    fun type(id: String?): Int? = id?.let(types::get)

    /** Every key that has a label; for the test that guards the schema. */
    val slotKeys: Set<String> get() = slots.keys
    val typeIds: Set<String> get() = types.keys

    /**
     * The resource to render [field]'s label from, or null to show [ExtractedData.fieldName] as it is.
     *
     * A machine value is always rendered from its slot key. A value a person has taken over keeps a
     * name they may have typed, so it is rendered from the key only while its name is still one the
     * app itself wrote; a name the person changed is theirs and is shown unchanged.
     */
    @StringRes
    fun labelFor(field: ExtractedData): Int? {
        val res = slot(field.slotKey.takeUnless { field.isExtra }) ?: return null
        if (field.source == ValueSource.MACHINE) return res
        return res.takeIf { field.fieldName in defaultNames(field.slotKey!!) }
    }

    /** The names the app itself has stored for a slot: today's English labels and the ones older versions wrote. */
    private fun defaultNames(key: String): Set<String> = when (key) {
        UnderstandingToFields.SLOT_SENDER ->
            setOf(UnderstandingToFields.SENDER_NAME, UnderstandingToFields.SENDER_ORGANISATION)
        UnderstandingToFields.SLOT_ADDRESSEE -> setOf(UnderstandingToFields.RECEIVER_NAME)
        UnderstandingToFields.SLOT_CONTACT -> setOf(UnderstandingToFields.CONTACT_PERSON)
        UnderstandingToFields.SLOT_SUBJECT -> setOf(UnderstandingToFields.SUBJECT)
        UnderstandingToFields.SLOT_UNLABELLED -> setOf(UnderstandingToFields.SLOT_UNLABELLED)
        else -> ExtractionSchema.DEFAULT.allSlots.filter { it.json == key }.map { it.label }.toSet()
    }
}
