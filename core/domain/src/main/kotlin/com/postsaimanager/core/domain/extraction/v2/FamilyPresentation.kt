package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.usecase.UnderstandingToFields

/** What a section of the Extracted tab holds; the screen words each kind from a string resource. */
enum class SectionKind {
    /** The addressee: the name row and the structured `addressee.*` rows, assembled into one block. */
    RECIPIENT_BLOCK,

    /** The sender: the name row and the structured `sender.*` rows, assembled into one block. */
    SENDER_BLOCK,

    /** The merchant of a receipt (a sender with no address block). */
    MERCHANT,

    /** Who is involved, as plain rows (a free-form document has no blocks). */
    PARTIES,

    /** The subject and the free text. */
    TEXT,

    /** What the reader has to do: amounts, the account, the dates to act by. */
    ACTION,

    /** What was paid and how (a receipt). */
    PAYMENT,

    DATES,
    REFERENCES,

    /** The open extras the model found; shown collapsed. */
    EXTRAS,
}

/** One section of a presentation: its [kind] and the slot keys placed in it, in order. */
data class Section(val kind: SectionKind, val slots: List<String> = emptyList())

/** How a family's fields are laid out on the Extracted tab. */
data class PresentationSpec(val sections: List<Section>) {
    /** The section [slotKey] is listed in, or null when the spec does not name it. */
    fun sectionOf(slotKey: String): SectionKind? = sections.firstOrNull { slotKey in it.slots }?.kind

    fun has(kind: SectionKind): Boolean = sections.any { it.kind == kind }
}

/**
 * The one owner of how each document family is presented: data, one [PresentationSpec] per family of
 * [ExtractionSchema.DEFAULT]. `ExtractedPresenter` reads the spec; there is no when-chain on the family there. A new family is one line
 * here (or none: an unknown family gets [FREE_FORM]); a slot a spec does not name is placed by its [SlotKind]
 * (money and deadlines under Action, dates under Dates, references under References), so a topic's extra slot needs nothing here.
 *
 * Legacy ids (a document read before extraction-v2-2) go through [LegacyTypes] first.
 */
object FamilyPresentation {

    private fun slots(vararg keys: SlotKey) = keys.map { it.json }

    private val SENDER = UnderstandingToFields.SLOT_SENDER
    private val ADDRESSEE = UnderstandingToFields.SLOT_ADDRESSEE
    private val CONTACT = UnderstandingToFields.SLOT_CONTACT
    private val SUBJECT = UnderstandingToFields.SLOT_SUBJECT

    /** The letter-like families: a recipient block, a sender block, then what to do, the references and the dates. */
    private val LETTER = PresentationSpec(
        listOf(
            Section(SectionKind.RECIPIENT_BLOCK, listOf(ADDRESSEE)),
            Section(SectionKind.SENDER_BLOCK, listOf(SENDER)),
            Section(SectionKind.TEXT, listOf(SUBJECT, CONTACT)),
            Section(
                SectionKind.ACTION,
                slots(
                    Slots.DUE_DATE, Slots.OBJECTION_DEADLINE, Slots.TOTAL, Slots.NEW_AMOUNT, Slots.FEE, Slots.PREVIOUS_AMOUNT,
                    Slots.IBAN,
                ),
            ),
            Section(
                SectionKind.REFERENCES,
                slots(
                    Slots.REFERENCE, Slots.INVOICE_NO, Slots.CONTRACT_NO, Slots.POLICY_NO, Slots.CASE_NO, Slots.TAX_NO,
                    Slots.CUSTOMER_NO,
                ),
            ),
            Section(
                SectionKind.DATES,
                slots(
                    Slots.LETTER_DATE, Slots.APPOINTMENT, Slots.EFFECTIVE_DATE, Slots.CONTRACT_END, Slots.ORIGINAL_DUE_DATE,
                    Slots.EVENT_DATE,
                ),
            ),
            Section(SectionKind.EXTRAS),
        ),
    )

    /** A receipt: the merchant, the total and the payment, the date, the references. */
    private val RECEIPT = PresentationSpec(
        listOf(
            Section(SectionKind.MERCHANT, listOf(SENDER)),
            Section(SectionKind.TEXT, listOf(SUBJECT)),
            Section(SectionKind.PAYMENT, slots(Slots.TOTAL, Slots.PROOF_AMOUNT, Slots.FEE, Slots.IBAN)),
            Section(SectionKind.DATES, slots(Slots.LETTER_DATE, Slots.PROOF_DATE, Slots.DUE_DATE)),
            Section(SectionKind.REFERENCES, slots(Slots.RECEIPT_NO, Slots.PROOF_REFERENCE, Slots.REFERENCE, Slots.CUSTOMER_NO)),
            Section(SectionKind.EXTRAS),
        ),
    )

    /** The abstain layout, and the one for families that carry no address block: people, text, then the rest grouped as before. */
    private val FREE_FORM = PresentationSpec(
        listOf(
            Section(SectionKind.PARTIES, listOf(SENDER, ADDRESSEE, CONTACT)),
            Section(SectionKind.TEXT, listOf(SUBJECT)),
            Section(SectionKind.ACTION, slots(Slots.TOTAL, Slots.IBAN, Slots.DUE_DATE)),
            Section(SectionKind.DATES, slots(Slots.LETTER_DATE)),
            Section(SectionKind.REFERENCES, slots(Slots.REFERENCE, Slots.CUSTOMER_NO)),
            Section(SectionKind.EXTRAS),
        ),
    )

    private val BY_FAMILY: Map<String, PresentationSpec> = mapOf(
        ExtractionSchema.OFFICIAL_LETTER.id to LETTER,
        ExtractionSchema.INVOICE_BILL.id to LETTER,
        ExtractionSchema.STATEMENT.id to LETTER,
        ExtractionSchema.CONTRACT_POLICY.id to LETTER,
        ExtractionSchema.MEDICAL.id to LETTER,
        ExtractionSchema.OUTGOING_LETTER.id to LETTER,
        ExtractionSchema.RECEIPT.id to RECEIPT,
        ExtractionSchema.PAYMENT_PROOF.id to RECEIPT,
        ExtractionSchema.FORM_APPLICATION.id to FREE_FORM,
        ExtractionSchema.CERTIFICATE_ID.id to FREE_FORM,
        ExtractionSchema.TICKET_BOOKING.id to FREE_FORM,
        ExtractionSchema.FREE_FORM.id to FREE_FORM,
    )

    /** The family id a stored type id stands for: a family id itself, or the family a legacy id maps to; null when neither. */
    fun familyId(typeId: String?): String? {
        val id = typeId?.trim()?.lowercase() ?: return null
        return id.takeIf { it in BY_FAMILY } ?: LegacyTypes.of(id)?.family
    }

    /** The spec for [typeId] (a family id or a legacy id); [FREE_FORM]'s for anything unknown or null. */
    fun of(typeId: String?): PresentationSpec = familyId(typeId)?.let(BY_FAMILY::get) ?: FREE_FORM

    /** Every family id that has a spec, for the test that guards the registry. */
    val familyIds: Set<String> get() = BY_FAMILY.keys
}
