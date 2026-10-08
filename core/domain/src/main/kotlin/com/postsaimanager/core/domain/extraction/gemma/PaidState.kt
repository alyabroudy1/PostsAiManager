package com.postsaimanager.core.domain.extraction.gemma

/**
 * The reader's answer to "has what this document is about been paid already?", asked right after "does it ask its reader to do anything?"
 * and before the category: a till receipt or a confirmation of payment is [ALREADY_PAID], a request to pay is [TO_PAY], a document with
 * no payment in it is [NOT_APPLICABLE].
 *
 * The model decides; [GemmaReadingVerifier] only holds the rest of the answer to it (no payment to make, no amount "to pay" for a
 * document that says everything is paid), and the summary writer is told it, so it never asks for a payment already made.
 *
 * @property id the word the schema offers and the parser reads
 * @property sentence what the answer means, for the prompt of the answer itself and of the summary (English: the letter may be in any language)
 */
enum class PaidState(val id: String, val sentence: String) {
    ALREADY_PAID("already_paid", "the amount in this document has already been paid: nothing is left to pay"),
    TO_PAY("to_pay", "the document asks for a payment that is still to be made"),
    NOT_APPLICABLE("not_applicable", "no payment is involved in this document"),
    ;

    companion object {
        /** The state named by [id] (case and spaces ignored), or null for any other word. */
        fun of(id: String?): PaidState? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
    }
}
