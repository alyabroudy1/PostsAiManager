package com.postsaimanager.core.domain.extraction.actions

/**
 * The parts of an action that a stored field can state. The key is what [com.postsaimanager.core.model.ActionItem.bindings] is keyed by.
 *
 * [DATE], [AMOUNT] and [PARTY] are stated in the line itself; [REFERENCE] and [IBAN] are shown under it (a value to copy, not a clause).
 */
enum class ActionPart(val key: String) {
    DATE("date"),
    AMOUNT("amount"),
    PARTY("party"),
    REFERENCE("reference"),
    IBAN("iban"),
    ;

    companion object {
        fun of(key: String): ActionPart? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One kind of thing a letter can ask of its reader. The model chooses among the kinds by score; it never writes a line. This is data: a
 * new kind is one entry in [ActionKinds] and, for the UI, its line templates (string resources, keyed by [id]).
 *
 * @property id the stable key, stored in the document and used by the UI to find the line's templates
 * @property task what the kind asks of the reader, completing "Does this letter ask the reader to ...?" (English: the letter may be in any language)
 * @property dateMeaning what the kind's date is, completing "Is «Deadline: 15.10.2026» ...?"; null when the kind states no date
 * @property amountMeaning what the kind's amount is, for the same question; null when the kind states no amount
 * @property party whether the kind names the sender (the party the reader pays, answers, sends to ...; never the addressee)
 * @property referenceSlots the stored slots a reference to quote is taken from, first present wins; empty when the kind has none
 * @property iban whether the kind shows the sender's account (where to pay)
 */
data class ActionKind(
    val id: String,
    val task: String,
    val dateMeaning: String? = null,
    val amountMeaning: String? = null,
    val party: Boolean = true,
    val referenceSlots: List<String> = emptyList(),
    val iban: Boolean = false,
) {
    /** The parts this kind may state, in the order a line lists them. */
    val parts: Set<ActionPart>
        get() = buildSet {
            if (dateMeaning != null) add(ActionPart.DATE)
            if (amountMeaning != null) add(ActionPart.AMOUNT)
            if (party) add(ActionPart.PARTY)
            if (referenceSlots.isNotEmpty()) add(ActionPart.REFERENCE)
            if (iban) add(ActionPart.IBAN)
        }
}

/**
 * The catalogue of action kinds. Nothing here is language specific: the descriptions are the model's own question (English), the
 * lines the person reads are string resources in the app's language.
 *
 * What is bound to a kind is only ever a stored, verified field: the date and the amount are chosen among the stored date and amount
 * slots by score, the party is the stored sender (the payee of a payment is the sender, never the addressee), the reference and the
 * account are the stored slots named here.
 */
object ActionKinds {

    /** The slots a reference to quote is looked up in for a kind that asks the reader to answer, send or confirm something. */
    private val CASE_REFERENCES = listOf("case_no", "reference", "contract_no", "policy_no", "invoice_no", "customer_no")

    val PAY = ActionKind(
        id = "pay",
        task = "pay an amount of money",
        dateMeaning = "the date by which the reader is asked to pay",
        amountMeaning = "the amount the reader is asked to pay",
        referenceSlots = listOf("invoice_no", "reference", "customer_no"),
        iban = true,
    )

    val REPLY = ActionKind(
        id = "reply",
        task = "reply to the sender or respond in writing",
        dateMeaning = "the date by which the reader is asked to reply",
        referenceSlots = CASE_REFERENCES,
    )

    val OBJECT_CANCEL = ActionKind(
        id = "object_cancel",
        task = "object, appeal or cancel before a deadline",
        dateMeaning = "the last date on which the reader is asked to object or cancel",
        party = false,
        referenceSlots = CASE_REFERENCES,
    )

    val ATTEND = ActionKind(
        id = "attend",
        task = "attend an appointment at a given date and time",
        dateMeaning = "the date of the appointment the reader is asked to attend",
        party = false,
    )

    val SEND_DOCUMENTS = ActionKind(
        id = "send_documents",
        task = "send documents or information to the sender",
        dateMeaning = "the date by which the reader is asked to send them",
        referenceSlots = CASE_REFERENCES,
    )

    val SIGN_RETURN = ActionKind(
        id = "sign_return",
        task = "sign a document or form and return it",
        dateMeaning = "the date by which the reader is asked to return it",
        party = false,
        referenceSlots = CASE_REFERENCES,
    )

    val CONFIRM_RENEW = ActionKind(
        id = "confirm_renew",
        task = "confirm, accept or renew something, such as a contract, a membership or an offer",
        dateMeaning = "the date by which the reader is asked to confirm",
        party = false,
        referenceSlots = CASE_REFERENCES,
    )

    val CONTACT = ActionKind(
        id = "contact",
        task = "contact the sender, by phone, e-mail or in person",
        dateMeaning = "the date by which the reader is asked to get in touch",
        referenceSlots = CASE_REFERENCES,
    )

    val ALL: List<ActionKind> = listOf(PAY, REPLY, OBJECT_CANCEL, ATTEND, SEND_DOCUMENTS, SIGN_RETURN, CONFIRM_RENEW, CONTACT)

    fun of(id: String): ActionKind? = ALL.firstOrNull { it.id == id }
}
