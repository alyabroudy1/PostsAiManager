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
 * @property question what the questionnaire interpreter asks about this slot, in English (the letter
 *   may be in any language). Data, like everything else here: a new slot brings its own question.
 */
data class SlotKey(
    val json: String,
    val kind: SlotKind,
    val label: String,
    val canonical: Canonical? = null,
    val expects: Set<String> = emptySet(),
    val question: String = "",
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
    val LETTER_DATE = SlotKey(
        "letter_date", SlotKind.DATE, "Document Date", Canonical.DOCUMENT_DATE, setOf("LETTER_DATE"),
        question = "Which date is the date of the letter itself (when it was written or issued)? Not a deadline, a due date or an event.",
    )

    /** The main amount of the document: what is due, the total of a receipt, the fee, the premium. */
    val TOTAL = SlotKey(
        "total", SlotKind.AMOUNT, "Amount", Canonical.AMOUNT, PAYABLE + setOf("INSTALMENT", "ADVANCE", "FEE", "OTHER"),
        question = "Which amount is the main amount of this document: what the reader has to pay, or the total? Not a net or VAT part of it.",
    )

    /** The date the reader must act by or pay by. */
    val DUE_DATE = SlotKey(
        "due_date", SlotKind.DEADLINE, "Deadline", Canonical.DEADLINE, DUE + "EVENT",
        question = "By which date must the reader pay or act? If the letter gives only a period in words " +
            "(for example \"within 14 days\"), answer RULE and copy those words exactly.",
    )
    val IBAN = SlotKey("iban", SlotKind.IBAN, "IBAN", Canonical.IBAN, question = "Which IBAN is the account the reader should pay to?")
    val REFERENCE = SlotKey(
        "reference", SlotKind.REFERENCE, "Reference",
        question = "Which reference does the letter itself cite for this matter (a file, case, transaction or payment reference)?",
    )
    val CUSTOMER_NO = SlotKey(
        "customer_no", SlotKind.REFERENCE, "Customer Number",
        question = "Which number identifies the reader as a customer, member or account holder?",
    )

    /** The slots every [DocType] has, in the order they are asked. */
    val CORE = listOf(LETTER_DATE, TOTAL, DUE_DATE, IBAN, REFERENCE, CUSTOMER_NO)

    // ── type-specific ──
    val FEE = SlotKey(
        "fee", SlotKind.AMOUNT, "Fee", expects = setOf("FEE", "OTHER"),
        question = "Which amount is a fee or surcharge the letter adds (a reminder or late fee, a charge)?",
    )
    val NEW_AMOUNT = SlotKey(
        "new_amount", SlotKind.AMOUNT, "New Amount", Canonical.AMOUNT, PAYABLE + setOf("INSTALMENT", "ADVANCE", "OTHER"),
        question = "Which amount is the new price, premium or instalment that applies from now on?",
    )
    val PREVIOUS_AMOUNT = SlotKey(
        "previous_amount", SlotKind.AMOUNT, "Previous Amount", expects = setOf("PREVIOUS", "OTHER"),
        question = "Which amount is the previous price or premium, the one that applied before the change?",
    )
    val PROOF_AMOUNT = SlotKey(
        "proof_amount", SlotKind.AMOUNT, "Amount Paid", Canonical.AMOUNT, PAYABLE + "OTHER",
        question = "Which amount was paid according to this document?",
    )

    val OBJECTION_DEADLINE = SlotKey(
        "objection_deadline", SlotKind.DEADLINE, "Objection Deadline", Canonical.DEADLINE, DUE + "OTHER",
        question = "By which date can the reader object or appeal? If the letter gives only a period in words " +
            "(for example \"within one month\"), answer RULE and copy those words exactly.",
    )
    val ORIGINAL_DUE_DATE = SlotKey(
        "original_due_date", SlotKind.DATE, "Original Due Date", expects = DUE + "OTHER",
        question = "Which date was the original due date of the payment that this letter reminds about?",
    )
    val EFFECTIVE_DATE = SlotKey(
        "effective_date", SlotKind.DATE, "Effective Date", expects = setOf("EFFECTIVE_FROM", "EVENT", "OTHER"),
        question = "From which date does the change or the news in this letter take effect?",
    )
    val CONTRACT_END = SlotKey(
        "contract_end", SlotKind.DATE, "Contract End",
        expects = setOf("EFFECTIVE_FROM", "EVENT", "DEADLINE", "PERIOD", "OTHER"),
        question = "On which date does the contract end or can it be terminated?",
    )
    val EVENT_DATE = SlotKey(
        "event_date", SlotKind.DATE, "Event Date", expects = setOf("EVENT", "APPOINTMENT", "OTHER"),
        question = "On which date does the event, trip or meeting the letter announces take place?",
    )
    val APPOINTMENT = SlotKey(
        "appointment", SlotKind.DATE, "Appointment", expects = setOf("APPOINTMENT", "EVENT"),
        question = "On which date is the appointment the letter gives or confirms?",
    )
    val SENT_DATE = SlotKey(
        "sent_date", SlotKind.DATE, "Sent Date", Canonical.DOCUMENT_DATE, setOf("LETTER_DATE"),
        question = "Which date is the date on which this letter was written or sent?",
    )
    val PROOF_DATE = SlotKey(
        "proof_date", SlotKind.DATE, "Payment Date", Canonical.DOCUMENT_DATE, setOf("LETTER_DATE", "EVENT", "OTHER"),
        question = "Which date is the date of the payment this document confirms?",
    )

    val INVOICE_NO = SlotKey(
        "invoice_no", SlotKind.REFERENCE, "Invoice Number",
        question = "Which number is the invoice number (the bill this letter is about)?",
    )
    val CONTRACT_NO = SlotKey(
        "contract_no", SlotKind.REFERENCE, "Contract Number",
        question = "Which number is the contract number?",
    )
    val POLICY_NO = SlotKey(
        "policy_no", SlotKind.REFERENCE, "Policy Number",
        question = "Which number is the insurance policy number?",
    )
    val CASE_NO = SlotKey(
        "case_no", SlotKind.REFERENCE, "Case Number",
        question = "Which number is the file, case or tax office reference of the authority?",
    )
    val TAX_NO = SlotKey(
        "tax_no", SlotKind.REFERENCE, "Tax Number",
        question = "Which number is the tax number or tax identification number of the reader?",
    )
    val RECEIPT_NO = SlotKey(
        "receipt_no", SlotKind.REFERENCE, "Receipt Number",
        question = "Which number is the receipt or transaction number?",
    )
    val PROOF_REFERENCE = SlotKey(
        "proof_reference", SlotKind.REFERENCE, "Payment Reference",
        question = "Which reference number does the payment carry (the purpose, transaction or booking reference)?",
    )

    val RECIPIENT_ORG = SlotKey(
        "recipient_org", SlotKind.NAME, "Sent To",
        question = "To which organisation or person was this letter sent? Answer with a name id, or a quote copied exactly.",
    )
    val PROOF_RECIPIENT = SlotKey(
        "proof_recipient", SlotKind.NAME, "Paid To",
        question = "Who received the payment? Answer with a name id, or a quote copied exactly.",
    )
    val CITED_REFERENCES = SlotKey(
        "cited_references", SlotKind.REFERENCE_LIST, "Cited References",
        question = "Which references does the letter cite or answer to? List up to 4 ids.",
    )
    val ACTION_KIND = SlotKey(
        "action_kind", SlotKind.ACTION, "Action",
        question = "What is this outgoing letter for?",
    )
}

/**
 * Whose document it is: what a scanned page can be. [INCOMING] is a letter the user received; [OUTGOING] one the user
 * wrote and sent; [PROOF] a confirmation the user holds of something they did (a payment). The direction is known
 * before the model reads the letter (how the document entered the app), so it narrows the types the model can pick
 * from rather than being guessed from text.
 */
enum class DocDirection { INCOMING, OUTGOING, PROOF }

/**
 * A document type the model can choose.
 *
 * @property slots the universal [Slots.CORE] followed by the type's own slots.
 * @property legacy what the app's stored document type becomes.
 * @property actionable the letter asks something of its reader (pay, answer, sign, attend), so the
 *   questions the model suggested for it are worth offering where no document is open. A property of
 *   the type, not a judgement about any one letter's text.
 * @property description one English line saying what the type is, for the questionnaire's type question.
 * @property directions the document directions this type can describe (a letter the user received is never an
 *   outgoing letter or a proof of payment); data, read by [ExtractionSchema.typesFor].
 */
data class DocType(
    val id: String,
    val slots: List<SlotKey>,
    val legacy: DocumentType,
    val actionable: Boolean = false,
    val description: String = "",
    val directions: Set<DocDirection> = setOf(DocDirection.INCOMING),
) {
    override fun toString() = id

    /** The same type, for documents of [directions] instead of incoming ones. */
    fun forDirections(vararg directions: DocDirection): DocType = copy(directions = directions.toSet())

    /** The same type, marked as one that asks something of its reader. */
    fun asksSomething(): DocType = copy(actionable = true)

    /** The same type with the line that explains it to the model. */
    fun described(text: String): DocType = copy(description = text)

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

    /** The types a document of [direction] can be: the candidates of the type question. */
    fun typesFor(direction: DocDirection): List<DocType> = types.filter { direction in it.directions }

    /** Every slot of every type, once. */
    val allSlots: List<SlotKey> = types.flatMap { it.slots }.distinct()

    /** Maps the model's type id (as stored on [com.postsaimanager.core.model.DocumentUnderstanding]) to the app's type. */
    fun legacyType(id: String?): DocumentType? = type(id)?.legacy

    companion object {
        val BILL = DocType.of("bill", DocumentType.INVOICE, Slots.INVOICE_NO).asksSomething()
            .described("an invoice or bill that asks the reader to pay")
        val REMINDER_DUNNING = DocType.of(
            "reminder_dunning", DocumentType.INVOICE, Slots.INVOICE_NO, Slots.FEE, Slots.ORIGINAL_DUE_DATE,
        ).asksSomething().described("a payment reminder or dunning letter about an unpaid bill")
        val AUTHORITY_TAX = DocType.of(
            "authority_tax", DocumentType.OFFICIAL_LETTER, Slots.OBJECTION_DEADLINE, Slots.CASE_NO, Slots.TAX_NO,
        ).asksSomething().described("a letter or decision from an authority, tax office or public body")

        /** Not actionable for the all-documents chat on purpose: health letters stay out of it (workstream G). */
        val HEALTH = DocType.of("health", DocumentType.NOTICE, Slots.APPOINTMENT)
            .described("a letter from a doctor, clinic or health insurer, such as an appointment")
        val INSURANCE_CONTRACT = DocType.of(
            "insurance_contract", DocumentType.CONTRACT,
            Slots.NEW_AMOUNT, Slots.PREVIOUS_AMOUNT, Slots.EFFECTIVE_DATE, Slots.CONTRACT_END, Slots.POLICY_NO, Slots.CONTRACT_NO,
        ).asksSomething().described("an insurance or service contract, or a change to its price or terms")
        val SCHOOL = DocType.of("school", DocumentType.NOTICE, Slots.EVENT_DATE).asksSomething()
            .described("a letter from a school or kindergarten to parents")
        val RECEIPT = DocType.of("receipt", DocumentType.RECEIPT, Slots.RECEIPT_NO)
            .described("a receipt or proof of a purchase")
        val INFO_NO_ACTION = DocType.of("info_no_action", DocumentType.NOTICE, Slots.EFFECTIVE_DATE)
            .described("an information letter or statement that asks nothing of the reader")

        /** A letter the user sent (P3; the pipeline does not produce it yet). */
        val OUTGOING_LETTER = DocType.of(
            "outgoing_letter", DocumentType.OFFICIAL_LETTER,
            Slots.RECIPIENT_ORG, Slots.SENT_DATE, Slots.ACTION_KIND, Slots.CITED_REFERENCES,
        ).described("a letter the reader wrote and sent to someone else").forDirections(DocDirection.OUTGOING)

        /** A payment confirmation the user holds (P3; the pipeline does not produce it yet). */
        val PAYMENT_PROOF = DocType.of(
            "payment_proof", DocumentType.RECEIPT,
            Slots.PROOF_AMOUNT, Slots.PROOF_DATE, Slots.PROOF_RECIPIENT, Slots.PROOF_REFERENCE,
        ).described("a confirmation that a payment was made").forDirections(DocDirection.PROOF)

        /** Fits a document of any direction; its description is neutral (no "anything", no "any other"), so it gains no score by being vague. */
        val OTHER = DocType.of("other", DocumentType.OTHER).described("a document of a kind not listed here")
            .forDirections(*DocDirection.entries.toTypedArray())

        val DEFAULT = ExtractionSchema(
            listOf(
                BILL, REMINDER_DUNNING, AUTHORITY_TAX, HEALTH, INSURANCE_CONTRACT, SCHOOL, RECEIPT,
                INFO_NO_ACTION, OUTGOING_LETTER, PAYMENT_PROOF, OTHER,
            ),
        )
    }
}
