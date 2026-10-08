package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.actions.ActionKinds

/**
 * Holds the rest of an answer to the model's own "has it been paid already?" ([PaidState]): a document it says is already paid has no
 * payment left to make, so it has no action that asks for one, no amount that is "the amount the reader has to pay" (the amount is the
 * total of the receipt or invoice: it was paid) and no date "by which the reader must pay".
 *
 * Code decides none of it: the model said paid, and an answer that says "pay" besides is the one that contradicts itself. Which category
 * the document is (a receipt, or a bill that was settled) stays the model's, read with the categories' descriptions. The ids named here
 * are the registries' own (the pay action kind, the two amount meanings and the due-date meaning).
 */
internal object PaidConsistency {

    private const val TOTAL_DUE = "TOTAL_DUE"
    private const val INVOICE_TOTAL = "INVOICE_TOTAL"
    private const val DUE_DATE = "DUE_DATE"

    private fun settled(paid: PaidState?) = paid == PaidState.ALREADY_PAID

    /** Whether the action [kindId] may stay: the action that asks for a payment is dropped for a document that is already paid. */
    fun keepsAction(paid: PaidState?, kindId: String): Boolean = !(settled(paid) && kindId == ActionKinds.PAY.id)

    /** The meaning an amount keeps: "the amount to pay" of a document that is already paid is the total that was paid. */
    fun amountMeaning(paid: PaidState?, meaningId: String?): String? =
        if (settled(paid) && meaningId == TOTAL_DUE) INVOICE_TOTAL else meaningId

    /** The meaning a date keeps: a document that is already paid has no date by which to pay (it is an ordinary date, no meaning). */
    fun dateMeaning(paid: PaidState?, meaningId: String?): String? =
        if (settled(paid) && meaningId == DUE_DATE) null else meaningId
}
