package com.postsaimanager.core.domain.document.list

import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import java.time.LocalDate
import javax.inject.Inject

/** Which side of a letter a name on a list row belongs to. */
enum class DocumentParty { SENDER, ADDRESSEE }

/**
 * The seam between a name as the letter printed it and the name a list row shows. Today the printed
 * name is shown as is ([IdentityPartyNameResolver]); when profiles can say "this addressee is Mia", a
 * resolver backed by them replaces the binding and no screen changes.
 */
fun interface PartyNameResolver {
    fun resolve(party: DocumentParty, printed: String): String
}

class IdentityPartyNameResolver @Inject constructor() : PartyNameResolver {
    override fun resolve(party: DocumentParty, printed: String): String = printed
}

/** What a hint may look at: verified facts of one document, no text. */
data class ActionFacts(
    val documentId: String,
    /** The type id the model chose (`bill`, `info_no_action`, ...); null before a model read it. */
    val documentType: String?,
    /** The date the reader must pay or act by, when the letter has one and it could be read. */
    val dueDate: LocalDate?,
    /** The model said the document's main amount is one to pay. */
    val hasAmountDue: Boolean,
    val today: LocalDate,
)

/**
 * How many things a person still has to do for a document; 0 shows no badge. The list asks this and
 * nothing else, so real tasks (the open-task count of the document) replace [DueFieldsActionHint]
 * behind this interface without a screen changing.
 */
fun interface ActionHint {
    fun openActions(facts: ActionFacts): Int
}

/**
 * The stop-gap until tasks exist: one open action when the letter has a deadline that has not been past
 * for long, or an amount to pay with no deadline that says it is long over. An information letter has
 * none. Reads only fields the model chose and the code verified; no word of any language is looked for.
 */
class DueFieldsActionHint @Inject constructor() : ActionHint {

    override fun openActions(facts: ActionFacts): Int {
        if (!mayAskSomething(facts.documentType)) return 0
        val due = facts.dueDate
        if (due != null) return if (!due.isBefore(facts.today.minusDays(STALE_AFTER_DAYS))) 1 else 0
        return if (facts.hasAmountDue) 1 else 0
    }

    /** A known family that asks nothing of its reader (a receipt, a certificate, a ticket) has no action; an unknown or abstained one is judged by its fields. */
    private fun mayAskSomething(typeId: String?): Boolean {
        val family = ExtractionSchema.DEFAULT.family(typeId) ?: return true
        return family.actionable || !family.scored
    }

    companion object {
        /** A deadline older than this is history, not something to do. */
        const val STALE_AFTER_DAYS = 30L
    }
}
