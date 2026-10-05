package com.postsaimanager.feature.documents

import androidx.annotation.StringRes
import com.postsaimanager.core.domain.extraction.address.AddressRows
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import com.postsaimanager.core.domain.extraction.v2.PartyRole
import com.postsaimanager.core.domain.extraction.v2.Slots
import com.postsaimanager.core.domain.usecase.UnderstandingToFields
import com.postsaimanager.core.model.AddressPart
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
        UnderstandingToFields.SLOT_SUBJECT_PERSON to R.string.slot_subject_person,
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

    /**
     * The label of each stored address row, per role: `AddressRows.prefixOf(role) + part.key`, from one (addressee, sender) pair of
     * resources per part. The raw-lines row has no part and is listed after them.
     */
    private val addressParts: Map<AddressPart, Pair<Int, Int>> = mapOf(
        AddressPart.RECIPIENT_NAME to (R.string.addr_addressee_name to R.string.addr_sender_name),
        AddressPart.ORGANISATION to (R.string.addr_addressee_organisation to R.string.addr_sender_organisation),
        AddressPart.DEPARTMENT to (R.string.addr_addressee_department to R.string.addr_sender_department),
        AddressPart.CARE_OF to (R.string.addr_addressee_care_of to R.string.addr_sender_care_of),
        AddressPart.STREET to (R.string.addr_addressee_street to R.string.addr_sender_street),
        AddressPart.HOUSE_NUMBER to (R.string.addr_addressee_house_number to R.string.addr_sender_house_number),
        AddressPart.ADDRESS_EXTRA to (R.string.addr_addressee_extra to R.string.addr_sender_extra),
        AddressPart.POSTCODE to (R.string.addr_addressee_postcode to R.string.addr_sender_postcode),
        AddressPart.CITY to (R.string.addr_addressee_city to R.string.addr_sender_city),
        AddressPart.REGION to (R.string.addr_addressee_region to R.string.addr_sender_region),
        AddressPart.COUNTRY to (R.string.addr_addressee_country to R.string.addr_sender_country),
        AddressPart.PO_BOX to (R.string.addr_addressee_po_box to R.string.addr_sender_po_box),
        AddressPart.PACKSTATION to (R.string.addr_addressee_packstation to R.string.addr_sender_packstation),
    )

    /** The address rows' labels by stored key (`addressee.street`), read from [addressParts] and [AddressRows]. */
    private val addressRows: Map<String, Int> = buildMap {
        for (role in listOf(PartyRole.ADDRESSEE, PartyRole.SENDER)) {
            val prefix = AddressRows.prefixOf(role) ?: continue
            val sender = role == PartyRole.SENDER
            for ((part, res) in addressParts) put(prefix + part.key, if (sender) res.second else res.first)
            put(prefix + AddressRows.RAW, if (sender) R.string.addr_sender_raw else R.string.addr_addressee_raw)
        }
    }

    /** One label per family; a legacy type id is rendered as the family it maps to ([LegacyTypes]). */
    private val types: Map<String, Int> = mapOf(
        ExtractionSchema.RECEIPT.id to R.string.doctype_receipt,
        ExtractionSchema.OUTGOING_LETTER.id to R.string.doctype_outgoing_letter,
        ExtractionSchema.PAYMENT_PROOF.id to R.string.doctype_payment_proof,
        ExtractionSchema.OFFICIAL_LETTER.id to R.string.doctype_official_letter,
        ExtractionSchema.INVOICE_BILL.id to R.string.doctype_invoice_bill,
        ExtractionSchema.FORM_APPLICATION.id to R.string.doctype_form_application,
        ExtractionSchema.STATEMENT.id to R.string.doctype_statement,
        ExtractionSchema.CONTRACT_POLICY.id to R.string.doctype_contract_policy,
        ExtractionSchema.CERTIFICATE_ID.id to R.string.doctype_certificate_id,
        ExtractionSchema.MEDICAL.id to R.string.doctype_medical,
        ExtractionSchema.TICKET_BOOKING.id to R.string.doctype_ticket_booking,
        ExtractionSchema.FREE_FORM.id to R.string.doctype_free_form,
    )

    private val topics: Map<String, Int> = mapOf(
        "government" to R.string.topic_government,
        "tax" to R.string.topic_tax,
        "health" to R.string.topic_health,
        "insurance" to R.string.topic_insurance,
        "bank_finance" to R.string.topic_bank_finance,
        "housing_utilities" to R.string.topic_housing_utilities,
        "work" to R.string.topic_work,
        "school_education" to R.string.topic_school_education,
        "vehicle" to R.string.topic_vehicle,
        "telecom" to R.string.topic_telecom,
        "shopping" to R.string.topic_shopping,
        "travel" to R.string.topic_travel,
        "legal" to R.string.topic_legal,
        "personal" to R.string.topic_personal,
    )

    /** The label resource for a slot key, or null for a key with none (an extra, or a name a person typed). */
    @StringRes
    fun slot(key: String?): Int? = key?.let { slots[it] ?: addressRows[it] }

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

    /**
     * The localized label of an extra stored under a bare key (`x:amount`) when that key is a slot's id or the slot's stored English
     * label ("amount" is the total's), so the key reads as the slot does everywhere else; null for any other name.
     */
    @StringRes
    fun extraKeySlot(name: String): Int? {
        val key = name.takeIf { it.startsWith(ExtractedData.EXTRA_KEY_PREFIX) }
            ?.removePrefix(ExtractedData.EXTRA_KEY_PREFIX)?.replace(Regex("_\\d+$"), "")?.trim() ?: return null
        val slot = ExtractionSchema.DEFAULT.allSlots.firstOrNull {
            it.json.equals(key, ignoreCase = true) || it.label.replace(' ', '_').equals(key, ignoreCase = true)
        } ?: return null
        return slots[slot.json]
    }

    @StringRes
    fun type(id: String?): Int? = id?.let { types[it] ?: types[LegacyTypes.of(it)?.family] }

    /** The label resource for a topic id, or null for an id with none. */
    @StringRes
    fun topic(id: String?): Int? = id?.let(topics::get)

    /** Every key that has a label; for the test that guards the schema. */
    val slotKeys: Set<String> get() = slots.keys + addressRows.keys
    val typeIds: Set<String> get() = types.keys
    val topicIds: Set<String> get() = topics.keys

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
        UnderstandingToFields.SLOT_SUBJECT_PERSON -> setOf(UnderstandingToFields.SUBJECT_PERSON)
        UnderstandingToFields.SLOT_UNLABELLED -> setOf(UnderstandingToFields.SLOT_UNLABELLED)
        else -> ExtractionSchema.DEFAULT.allSlots.filter { it.json == key }.map { it.label }.toSet()
    }
}
