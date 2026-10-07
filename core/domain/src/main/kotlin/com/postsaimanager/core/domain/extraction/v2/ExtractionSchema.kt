package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.model.DocumentType

/**
 * What extraction v2 can be asked about: the slots, and which slots each document type has.
 *
 * This file is the whole schema, as data. The grammar, the prompt, the verifier and the adapter
 * all read [ExtractionSchema], so:
 * - **a new slot** is one [SlotKey] line in [Slots] plus its name in the [DocFamily]s or [Topic]s that use it;
 * - **a new document family** is one [DocFamily] line in [ExtractionSchema.DEFAULT], usually just its
 *   family-specific slots (the universal core in [Slots.CORE] is added by [DocFamily.of]);
 * - **a new topic** is one [Topic] line in [ExtractionSchema.TOPICS];
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
    /**
     * The slots every [DocFamily] has, in the order they are asked: the date, the amount, the due date, the account and every kind of
     * reference number. Family-independent because the classifier can file a bill as a letter (or the reverse) and the numbers on a page
     * matter whatever it was filed as; which of them matter for a document is decided afterwards, by the family's hint (the key
     * information). Declared after the reference slots it lists (object initialisation order).
     */
    val CORE = listOf(LETTER_DATE, TOTAL, DUE_DATE, IBAN, REFERENCE, CUSTOMER_NO, INVOICE_NO, CONTRACT_NO, POLICY_NO, CASE_NO, TAX_NO)

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
 * A kind of document the model can choose: what the document is, as opposed to what it is about (see [Topic]).
 *
 * @property slots the universal [Slots.CORE] followed by the family's own slots.
 * @property legacy what the app's stored document type becomes.
 * @property actionable the letter asks something of its reader (pay, answer, sign, attend), so the
 *   questions the model suggested for it are worth offering where no document is open. A property of
 *   the family, not a judgement about any one letter's text.
 * @property description one English line saying what the family is (a content description, for the scoring
 *   question; it is a model prompt, not UI text).
 * @property directions the document directions this family can describe (a letter the user received is never an
 *   outgoing letter or a proof of payment); data, read by [ExtractionSchema.familiesFor].
 * @property hasRecipientBlock the family is a letter with an addressee block (a structured recipient address is worth reading).
 * @property sensitive the all-documents chat never shows these documents (health letters); see [ExtractionSchema.isSensitive].
 * @property scored whether the classifier asks about this family. False for the abstain outcome ([ExtractionSchema.FREE_FORM]):
 *   a scored "anything else" gets a middling Yes on every letter and wins, so it is what is chosen when no family scores above the threshold.
 * @property hint a few English words of guidance for the model on what matters in this kind of document (what the reader needs to know
 *   and to do). Prompt text, never a rule: it steers which facts the second stage keeps as the key information, and the model still
 *   decides for each letter. A new family adds its hint here, as data.
 */
data class DocFamily(
    val id: String,
    val slots: List<SlotKey>,
    val legacy: DocumentType,
    val actionable: Boolean = false,
    val description: String = "",
    val directions: Set<DocDirection> = setOf(DocDirection.INCOMING),
    val hasRecipientBlock: Boolean = false,
    val sensitive: Boolean = false,
    val scored: Boolean = true,
    val hint: String = "",
    /** The slots that belong to this family beyond the universal core (a bill's invoice number): a document of the family is expected to have them. */
    val own: List<SlotKey> = emptyList(),
) {
    override fun toString() = id

    /** The same family with the guidance for the model on what matters in it. */
    fun withHint(text: String): DocFamily = copy(hint = text)

    /** The same family, for documents of [directions] instead of incoming ones. */
    fun forDirections(vararg directions: DocDirection): DocFamily = copy(directions = directions.toSet())

    /** The same family, marked as one that asks something of its reader. */
    fun asksSomething(): DocFamily = copy(actionable = true)

    /** The same family with the line that explains it to the model. */
    fun described(text: String): DocFamily = copy(description = text)

    /** The same family, for letters that carry an addressee block. */
    fun withRecipientBlock(): DocFamily = copy(hasRecipientBlock = true)

    /** The same family, kept out of the all-documents chat. */
    fun markedSensitive(): DocFamily = copy(sensitive = true)

    /** The same family, never asked about by the classifier (the abstain outcome). */
    fun unscored(): DocFamily = copy(scored = false)

    companion object {
        /** A family with the universal core plus [specific] slots. */
        fun of(id: String, legacy: DocumentType, vararg specific: SlotKey) =
            DocFamily(id, (Slots.CORE + specific).distinct(), legacy, own = specific.toList())
    }
}

/**
 * What a document is about, independently of its family (a bill can be about health, a contract about insurance).
 * Any number of topics can hold for one document; the best two contribute their [slots] (see [ExtractionSchema.slotsFor]).
 *
 * @property description one English phrase naming the subject ("Does this document concern <description>?"); a model prompt, not UI.
 * @property slots the slots a document about this topic has beyond its family's.
 * @property sensitive documents about this topic stay out of the all-documents chat.
 */
data class Topic(
    val id: String,
    val description: String,
    val slots: List<SlotKey> = emptyList(),
    val sensitive: Boolean = false,
) {
    override fun toString() = id
}

/** The registry the rest of the package reads. */
class ExtractionSchema(val families: List<DocFamily>, val topics: List<Topic> = emptyList()) {

    init {
        require(families.isNotEmpty()) { "a schema needs at least one document family" }
        require(families.map { it.id }.toSet().size == families.size) { "duplicate document family id" }
        require(topics.map { it.id }.toSet().size == topics.size) { "duplicate topic id" }
    }

    fun family(id: String?): DocFamily? = families.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

    fun topic(id: String?): Topic? = topics.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }

    /** The abstain family: what a document is when no family fits ([DocFamily.scored] false), or null for a schema that has none. */
    val abstain: DocFamily? get() = families.firstOrNull { !it.scored }

    /** The families a document of [direction] can be, in registry order: the candidates the classifier scores. The abstain family is never among them. */
    fun familiesFor(direction: DocDirection): List<DocFamily> = families.filter { it.scored && direction in it.directions }

    /**
     * This schema as a document of [direction] sees it: the families it can be plus the abstain family, the same topics. What the
     * interpreters that let the model name the type (rather than score the families) offer, so a received letter is never offered
     * "a letter you sent" whichever strategy reads it.
     */
    fun forDirection(direction: DocDirection): ExtractionSchema =
        ExtractionSchema(families.filter { direction in it.directions }, topics)

    /**
     * The slots a document of [family] about [topics] has: the family's own, then those of the best two topics.
     * [topics] is in the order the classifier ranked them (best first); ids this schema does not know are skipped
     * and do not use up one of the two places.
     */
    fun slotsFor(family: DocFamily, topics: List<String>): List<SlotKey> {
        val best = topics.mapNotNull(::topic).distinct().take(MAX_TOPICS_WITH_SLOTS)
        return (family.slots + best.flatMap { it.slots }).distinct()
    }

    /** The slots a document of [family] about [topics] is expected to have beyond the universal core: the family's own and those of the best two topics. */
    fun ownSlots(family: DocFamily, topics: List<String>): Set<SlotKey> =
        (family.own + topics.mapNotNull(::topic).distinct().take(MAX_TOPICS_WITH_SLOTS).flatMap { it.slots }).toSet()

    /** Whether a document of [familyId] about [topicIds] stays out of the all-documents chat. Unknown ids are not sensitive. */
    fun isSensitive(familyId: String?, topicIds: List<String>): Boolean =
        family(familyId)?.sensitive == true || topicIds.any { topic(it)?.sensitive == true }

    /** Every slot of every family and topic, once. */
    val allSlots: List<SlotKey> = (families.flatMap { it.slots } + topics.flatMap { it.slots }).distinct()

    /** Maps the model's family id (as stored on [com.postsaimanager.core.model.DocumentUnderstanding]) to the app's type. */
    fun legacyType(id: String?): DocumentType? = family(id)?.legacy

    companion object {
        /** How many topics add their slots to a document; a third topic is kept on the document but asks nothing more. */
        const val MAX_TOPICS_WITH_SLOTS = 2

        // ── the families ──
        val OFFICIAL_LETTER = DocFamily.of(
            "official_letter", DocumentType.OFFICIAL_LETTER, Slots.APPOINTMENT, Slots.EFFECTIVE_DATE, Slots.OBJECTION_DEADLINE,
        ).asksSomething().withRecipientBlock()
            .described("a letter or decision from an authority, employer, school or other organisation that informs the reader or decides something")
            .withHint(
                "In a letter, the sender and the recipient (with their addresses) are important. Also note anything else the reader should " +
                    "know, and any action the reader must take (pay, reply, object, attend, send documents) with its deadline.",
            )

        /** Absorbs the legacy bill and reminder_dunning: a reminder is a bill with a fee and an original due date. */
        val INVOICE_BILL = DocFamily.of(
            "invoice_bill", DocumentType.INVOICE, Slots.INVOICE_NO, Slots.FEE, Slots.ORIGINAL_DUE_DATE,
        ).asksSomething().withRecipientBlock()
            .described("an invoice, a bill or a payment reminder that asks the reader to pay")
            .withHint(
                "In a bill, the sender and the recipient are important, and so are the amount to pay, the due date, the account (IBAN) " +
                    "to pay to and the invoice, customer and payment reference numbers. The action is to pay by the due date.",
            )

        val RECEIPT = DocFamily.of("receipt", DocumentType.RECEIPT, Slots.RECEIPT_NO)
            .described("a receipt or proof of a purchase")
            .withHint("In a receipt, the shop, the total paid, the date and the receipt number are important. Usually nothing has to be done.")

        val FORM_APPLICATION = DocFamily.of("form_application", DocumentType.FORM)
            .described("a form or an application that is filled in and returned")
            .withHint(
                "In a form, who it is from and for, what it is for, the deadline to return it and where to send it are important. " +
                    "The action is to fill it in and return it.",
            )

        val STATEMENT = DocFamily.of("statement", DocumentType.NOTICE, Slots.PREVIOUS_AMOUNT).withRecipientBlock()
            .described("a statement of account, a bank statement or a summary of transactions or consumption")
            .withHint(
                "In a statement, the sender and the recipient, the account or customer number, the period it covers, the balance or " +
                    "amount due and any date by which something is to be paid or checked are important.",
            )

        val CONTRACT_POLICY = DocFamily.of(
            "contract_policy", DocumentType.CONTRACT, Slots.CONTRACT_NO, Slots.CONTRACT_END, Slots.EFFECTIVE_DATE, Slots.NEW_AMOUNT,
        ).asksSomething().withRecipientBlock()
            .described("a contract, an insurance policy, or a change to its price or terms")
            .withHint(
                "In a contract or policy, the sender and the recipient, the contract or policy number, the price, the date it takes effect " +
                    "or ends, and any right to object or cancel with its deadline are important.",
            )

        val CERTIFICATE_ID = DocFamily.of("certificate_id", DocumentType.CERTIFICATE, Slots.EFFECTIVE_DATE, Slots.CONTRACT_END)
            .described("a certificate, an identity document, a licence or a card")
            .withHint("In a certificate or an identity document, who it was issued to and by, what it certifies, its number and its validity dates are important.")

        val MEDICAL = DocFamily.of("medical", DocumentType.NOTICE, Slots.APPOINTMENT).withRecipientBlock().markedSensitive()
            .described("a letter from a doctor, clinic or hospital, such as an appointment, a referral or a result")
            .withHint(
                "In a medical letter, the sender and who it is about, an appointment with its date and place, and anything the reader " +
                    "must do (attend, prepare, call back, pay) are important.",
            )

        val TICKET_BOOKING = DocFamily.of("ticket_booking", DocumentType.OTHER, Slots.EVENT_DATE)
            .described("a ticket, a booking confirmation or a travel itinerary")
            .withHint("In a ticket or a booking, the event or trip, the date and time, the place and the booking number are important.")

        /**
         * A short reminder or confirmation of an appointment. Added with the message family below because the registry had no kind for a
         * few lines of text, so a screenshot of appointment reminders was the nearest "official letter". It is scored after the families
         * of extraction-v2-2, so the recorded scores of the first nine keep their columns; the questions of those nine are unchanged.
         */
        val APPOINTMENT_REMINDER = DocFamily.of("appointment_reminder", DocumentType.NOTICE, Slots.APPOINTMENT)
            .asksSomething()
            .described("a short reminder or confirmation of an appointment, such as a text message or an email that gives a date, a time and a place")
            .withHint(
                "In an appointment reminder, who it is from, the date, the time and the place, and what the reader should do " +
                    "(come, bring something, confirm or cancel) are important.",
            )

        /** What a few sentences of text are: a message, a chat or a note, not a letter. */
        val MESSAGE_NOTE = DocFamily.of("message_note", DocumentType.OTHER)
            .described("a short message, a few lines of a chat or text messages, or a handwritten or typed note, rather than a formal letter")
            .withHint("In a message or a note, who it is from and for, what it says and any date, time or place it mentions are important.")

        /** A letter the user sent (P3; the pipeline does not produce it yet). */
        val OUTGOING_LETTER = DocFamily.of(
            "outgoing_letter", DocumentType.OFFICIAL_LETTER,
            Slots.RECIPIENT_ORG, Slots.SENT_DATE, Slots.ACTION_KIND, Slots.CITED_REFERENCES,
        ).described("a letter the reader wrote and sent to someone else").forDirections(DocDirection.OUTGOING)
            .withHint("In a letter the reader sent, who it went to, what it asked for or said, the date it was sent and any reference it cites are important.")

        /** A payment confirmation the user holds (P3; the pipeline does not produce it yet). */
        val PAYMENT_PROOF = DocFamily.of(
            "payment_proof", DocumentType.RECEIPT,
            Slots.PROOF_AMOUNT, Slots.PROOF_DATE, Slots.PROOF_RECIPIENT, Slots.PROOF_REFERENCE,
        ).described("a confirmation that a payment was made").forDirections(DocDirection.PROOF)
            .withHint("In a payment confirmation, the amount, the date, who was paid and the payment reference are important.")

        /** The abstain outcome: what a document is when no family scores above the threshold. Never scored, so it carries only the core. */
        val FREE_FORM = DocFamily.of("free_form", DocumentType.OTHER)
            .described("a document of a kind not listed here").forDirections(*DocDirection.entries.toTypedArray()).unscored()
            .withHint("In any document, who it is from and for, and what the reader should know or do, are important.")

        // ── the topics ──
        val TOPICS: List<Topic> = listOf(
            Topic("government", "a government agency, a public authority or a public service", listOf(Slots.CASE_NO, Slots.OBJECTION_DEADLINE)),
            Topic("tax", "taxes, a tax office or a tax return", listOf(Slots.TAX_NO, Slots.CASE_NO)),
            Topic(
                "health", "health, medical care, a doctor or a health insurer", listOf(Slots.APPOINTMENT),
                sensitive = true,
            ),
            Topic(
                "insurance", "an insurance policy or an insurance company",
                listOf(Slots.POLICY_NO, Slots.NEW_AMOUNT, Slots.PREVIOUS_AMOUNT, Slots.CONTRACT_END),
            ),
            Topic("bank_finance", "a bank, a loan, an account, savings or other personal finance"),
            Topic("housing_utilities", "housing, rent, a property or a household utility such as energy, water or heating", listOf(Slots.CONTRACT_NO)),
            Topic("work", "a job, an employer, employment or a salary"),
            Topic("school_education", "a school, a kindergarten, a university or education", listOf(Slots.EVENT_DATE)),
            Topic("vehicle", "a car, another vehicle, a driving licence or road traffic"),
            Topic("telecom", "a phone, mobile, internet or television service", listOf(Slots.CONTRACT_NO)),
            Topic("shopping", "a purchase from a shop or an online order"),
            Topic("travel", "a trip, a flight, a hotel or a holiday"),
            Topic("legal", "a court, a lawyer or a legal dispute"),
            Topic("personal", "a private matter, family or personal correspondence"),
        )

        /** The families and topics of extraction-v2-2: what every reader of the schema uses. */
        val DEFAULT = ExtractionSchema(
            listOf(
                OFFICIAL_LETTER, INVOICE_BILL, RECEIPT, FORM_APPLICATION, STATEMENT, CONTRACT_POLICY, CERTIFICATE_ID, MEDICAL,
                TICKET_BOOKING, APPOINTMENT_REMINDER, MESSAGE_NOTE, OUTGOING_LETTER, PAYMENT_PROOF, FREE_FORM,
            ),
            TOPICS,
        )
    }
}
