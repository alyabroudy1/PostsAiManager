package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.model.DocumentType

/**
 * What extraction v2 can be asked about: the slots, and which slots each document type has.
 *
 * This file is the whole schema, as data. The grammar, the prompt, the verifier and the adapter
 * all read [ExtractionSchema], so:
 * - **a new slot** is one [SlotKey] line in [Slots] plus its name in the [DocType]s that use it;
 * - **a new document type** is one [DocType] line in [ExtractionSchema.DEFAULT], usually just its
 *   type-specific slots (the universal core in [Slots.CORE] is added by [DocType.of]);
 * - **a new language** needs nothing here (nothing in the schema is language specific).
 */

/** What sort of candidate a slot accepts, and so which ids the grammar lists for it. */
enum class SlotKind(vararg val candidates: CandidateKind) {
    /** Answered as `{"id":"A1","r":<amount role>,"c":<confidence>}`. */
    AMOUNT(CandidateKind.AMOUNT),

    /** Answered as `{"id":"D1","r":<date role>,"c":..}`. */
    DATE(CandidateKind.DATE, CandidateKind.DATETIME),

    /** A date, or a period in words the model quotes as a rule (`{"rule":"...","r":..,"c":..}`), verified by [RelativePeriod]. */
    DEADLINE(CandidateKind.DATE, CandidateKind.DATETIME),
    IBAN(CandidateKind.IBAN),

    /** A reference or identifier: an invoice, customer, contract, case or meter number. */
    REFERENCE(CandidateKind.REFERENCE),

    /** Several references, e.g. the ones a reply cites. */
    REFERENCE_LIST(CandidateKind.REFERENCE),

    /** A person or organisation: a name candidate id, or a verified quote. */
    NAME(CandidateKind.NAME),

    /** One of [SlotKey.ACTIONS]; not a value from the page. */
    ACTION,
}

/** What an amount or a date *is*, in the model's words. The enums the grammar offers. */
object Roles {
    val AMOUNT = listOf("TOTAL_DUE", "NET", "VAT", "GROSS", "INSTALMENT", "ADVANCE", "FEE", "CREDIT", "PREVIOUS", "OTHER")
    val DATE = listOf("LETTER_DATE", "DUE_DATE", "DEADLINE", "APPOINTMENT", "EFFECTIVE_FROM", "PERIOD", "EVENT", "OTHER")
}

/** The existing field a slot feeds when it is the first of its group present; see [ExtractionV2Adapter]. */
enum class Canonical { AMOUNT, DEADLINE, DOCUMENT_DATE, IBAN }

/**
 * A place in a document that the model may fill.
 *
 * @property json the stable key: in the grammar, in the model's answer and (from workstream E) in
 *   storage. Nothing else about a slot may be relied on to stay the same.
 * @property label the English name the app stores today so existing fields keep their names. It is a
 *   render key: workstream E localises it, so nothing may parse or compare it.
 * @property expects the roles ([Roles]) the model may give a value it puts here. A different role is
 *   the model contradicting itself (a "net" amount in the total slot); the value is kept and marked
 *   for review. Empty means any.
 */
data class SlotKey(
    val json: String,
    val kind: SlotKind,
    val label: String,
    val canonical: Canonical? = null,
    val expects: Set<String> = emptySet(),
) {
    override fun toString() = json

    companion object {
        /** What an outgoing letter is for. Not a value on the page, so the grammar lists it outright. */
        val ACTIONS = listOf("objection", "cancellation", "instalment_request", "application", "reply", "payment", "other")
    }
}

object Slots {
    private val PAYABLE = setOf("TOTAL_DUE", "GROSS")
    private val DUE = setOf("DUE_DATE", "DEADLINE")

    // ── the universal core: every document type has these ──
    val LETTER_DATE = SlotKey("letter_date", SlotKind.DATE, "Document Date", Canonical.DOCUMENT_DATE, setOf("LETTER_DATE"))

    /** The main amount of the document: what is due, the total of a receipt, the fee, the premium. */
    val TOTAL = SlotKey("total", SlotKind.AMOUNT, "Amount", Canonical.AMOUNT, PAYABLE + setOf("INSTALMENT", "ADVANCE", "FEE", "OTHER"))

    /** The date the reader must act by or pay by. */
    val DUE_DATE = SlotKey("due_date", SlotKind.DEADLINE, "Deadline", Canonical.DEADLINE, DUE + "EVENT")
    val IBAN = SlotKey("iban", SlotKind.IBAN, "IBAN", Canonical.IBAN)
    val REFERENCE = SlotKey("reference", SlotKind.REFERENCE, "Reference")
    val CUSTOMER_NO = SlotKey("customer_no", SlotKind.REFERENCE, "Customer Number")

    /** The slots every [DocType] has, in the order they are asked. */
    val CORE = listOf(LETTER_DATE, TOTAL, DUE_DATE, IBAN, REFERENCE, CUSTOMER_NO)

    // ── type-specific ──
    val FEE = SlotKey("fee", SlotKind.AMOUNT, "Fee", expects = setOf("FEE", "OTHER"))
    val NEW_AMOUNT = SlotKey(
        "new_amount", SlotKind.AMOUNT, "New Amount", Canonical.AMOUNT, PAYABLE + setOf("INSTALMENT", "ADVANCE", "OTHER"),
    )
    val PREVIOUS_AMOUNT = SlotKey("previous_amount", SlotKind.AMOUNT, "Previous Amount", expects = setOf("PREVIOUS", "OTHER"))
    val PROOF_AMOUNT = SlotKey("proof_amount", SlotKind.AMOUNT, "Amount Paid", Canonical.AMOUNT, PAYABLE + "OTHER")

    val OBJECTION_DEADLINE = SlotKey(
        "objection_deadline", SlotKind.DEADLINE, "Objection Deadline", Canonical.DEADLINE, DUE + "OTHER",
    )
    val ORIGINAL_DUE_DATE = SlotKey("original_due_date", SlotKind.DATE, "Original Due Date", expects = DUE + "OTHER")
    val EFFECTIVE_DATE = SlotKey("effective_date", SlotKind.DATE, "Effective Date", expects = setOf("EFFECTIVE_FROM", "EVENT", "OTHER"))
    val CONTRACT_END = SlotKey(
        "contract_end", SlotKind.DATE, "Contract End",
        expects = setOf("EFFECTIVE_FROM", "EVENT", "DEADLINE", "PERIOD", "OTHER"),
    )
    val EVENT_DATE = SlotKey("event_date", SlotKind.DATE, "Event Date", expects = setOf("EVENT", "APPOINTMENT", "OTHER"))
    val APPOINTMENT = SlotKey("appointment", SlotKind.DATE, "Appointment", expects = setOf("APPOINTMENT", "EVENT"))
    val SENT_DATE = SlotKey("sent_date", SlotKind.DATE, "Sent Date", Canonical.DOCUMENT_DATE, setOf("LETTER_DATE"))
    val PROOF_DATE = SlotKey("proof_date", SlotKind.DATE, "Payment Date", Canonical.DOCUMENT_DATE, setOf("LETTER_DATE", "EVENT", "OTHER"))

    val INVOICE_NO = SlotKey("invoice_no", SlotKind.REFERENCE, "Invoice Number")
    val CONTRACT_NO = SlotKey("contract_no", SlotKind.REFERENCE, "Contract Number")
    val POLICY_NO = SlotKey("policy_no", SlotKind.REFERENCE, "Policy Number")
    val CASE_NO = SlotKey("case_no", SlotKind.REFERENCE, "Case Number")
    val TAX_NO = SlotKey("tax_no", SlotKind.REFERENCE, "Tax Number")
    val RECEIPT_NO = SlotKey("receipt_no", SlotKind.REFERENCE, "Receipt Number")
    val PROOF_REFERENCE = SlotKey("proof_reference", SlotKind.REFERENCE, "Payment Reference")

    val RECIPIENT_ORG = SlotKey("recipient_org", SlotKind.NAME, "Sent To")
    val PROOF_RECIPIENT = SlotKey("proof_recipient", SlotKind.NAME, "Paid To")
    val CITED_REFERENCES = SlotKey("cited_references", SlotKind.REFERENCE_LIST, "Cited References")
    val ACTION_KIND = SlotKey("action_kind", SlotKind.ACTION, "Action")
}

/**
 * A document type the model can choose.
 *
 * @property slots the universal [Slots.CORE] followed by the type's own slots.
 * @property legacy what the app's stored document type becomes.
 */
data class DocType(val id: String, val slots: List<SlotKey>, val legacy: DocumentType) {
    override fun toString() = id

    companion object {
        /** A type with the universal core plus [specific] slots. */
        fun of(id: String, legacy: DocumentType, vararg specific: SlotKey) =
            DocType(id, (Slots.CORE + specific).distinct(), legacy)
    }
}

/** The registry the rest of the package reads. */
class ExtractionSchema(val types: List<DocType>) {

    init {
        require(types.isNotEmpty()) { "a schema needs at least one document type" }
        require(types.map { it.id }.toSet().size == types.size) { "duplicate document type id" }
    }

    fun type(id: String?): DocType? = types.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

    /** Every slot of every type, once. */
    val allSlots: List<SlotKey> = types.flatMap { it.slots }.distinct()

    /** Maps the model's type id (as stored on [com.postsaimanager.core.model.DocumentUnderstanding]) to the app's type. */
    fun legacyType(id: String?): DocumentType? = type(id)?.legacy

    companion object {
        val BILL = DocType.of("bill", DocumentType.INVOICE, Slots.INVOICE_NO)
        val REMINDER_DUNNING = DocType.of(
            "reminder_dunning", DocumentType.INVOICE, Slots.INVOICE_NO, Slots.FEE, Slots.ORIGINAL_DUE_DATE,
        )
        val AUTHORITY_TAX = DocType.of(
            "authority_tax", DocumentType.OFFICIAL_LETTER, Slots.OBJECTION_DEADLINE, Slots.CASE_NO, Slots.TAX_NO,
        )
        val HEALTH = DocType.of("health", DocumentType.NOTICE, Slots.APPOINTMENT)
        val INSURANCE_CONTRACT = DocType.of(
            "insurance_contract", DocumentType.CONTRACT,
            Slots.NEW_AMOUNT, Slots.PREVIOUS_AMOUNT, Slots.EFFECTIVE_DATE, Slots.CONTRACT_END, Slots.POLICY_NO, Slots.CONTRACT_NO,
        )
        val SCHOOL = DocType.of("school", DocumentType.NOTICE, Slots.EVENT_DATE)
        val RECEIPT = DocType.of("receipt", DocumentType.RECEIPT, Slots.RECEIPT_NO)
        val INFO_NO_ACTION = DocType.of("info_no_action", DocumentType.NOTICE, Slots.EFFECTIVE_DATE)

        /** A letter the user sent (P3; the pipeline does not produce it yet). */
        val OUTGOING_LETTER = DocType.of(
            "outgoing_letter", DocumentType.OFFICIAL_LETTER,
            Slots.RECIPIENT_ORG, Slots.SENT_DATE, Slots.ACTION_KIND, Slots.CITED_REFERENCES,
        )

        /** A payment confirmation the user holds (P3; the pipeline does not produce it yet). */
        val PAYMENT_PROOF = DocType.of(
            "payment_proof", DocumentType.RECEIPT,
            Slots.PROOF_AMOUNT, Slots.PROOF_DATE, Slots.PROOF_RECIPIENT, Slots.PROOF_REFERENCE,
        )
        val OTHER = DocType.of("other", DocumentType.OTHER)

        val DEFAULT = ExtractionSchema(
            listOf(
                BILL, REMINDER_DUNNING, AUTHORITY_TAX, HEALTH, INSURANCE_CONTRACT, SCHOOL, RECEIPT,
                INFO_NO_ACTION, OUTGOING_LETTER, PAYMENT_PROOF, OTHER,
            ),
        )
    }
}
