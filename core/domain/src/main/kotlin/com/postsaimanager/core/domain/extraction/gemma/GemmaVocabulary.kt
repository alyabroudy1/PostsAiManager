package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.actions.ActionKind
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.v2.DocCategory
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.MeaningKind
import com.postsaimanager.core.domain.extraction.v2.PartyKind
import com.postsaimanager.core.domain.extraction.v2.SlotKind
import com.postsaimanager.core.domain.extraction.v2.ValueMeaning
import com.postsaimanager.core.domain.extraction.v2.ValueMeanings

/**
 * The words the reader may answer with, all of them taken from the registries the rest of the app already owns (so a new meaning, a new
 * action kind or a new category is one line in its own registry and nothing here): the date and amount meanings, the action kinds,
 * the categories, the kinds of reference. Plus the two words every list has for "none of these" ([OTHER], [NONE]).
 */
class GemmaVocabulary(
    val meanings: ValueMeanings = ValueMeanings.DEFAULT,
    val schema: ExtractionSchema = ExtractionSchema.DEFAULT,
    val actionKinds: List<ActionKind> = ActionKinds.ALL,
    val categories: List<DocCategory> = schema.categories,
) {

    val dateMeanings: List<ValueMeaning> get() = meanings.of(MeaningKind.DATE)
    val amountMeanings: List<ValueMeaning> get() = meanings.of(MeaningKind.AMOUNT)

    /** The kinds a reference may be named: the schema's reference slots (invoice, customer, contract, case ...), the account, and other. */
    val referenceKinds: List<String> = schema.allSlots.filter { it.kind == SlotKind.REFERENCE }.map { it.json }.distinct() + IBAN_KIND + OTHER

    /** The category words: the registry's ids and the one for a document that fits none. */
    val categoryIds: List<String> = categories.map { it.id } + DOCUMENT_CATEGORY

    val partyKinds: List<String> = PartyKind.entries.map { it.name.lowercase() }

    fun category(id: String?): DocCategory? = categories.firstOrNull { it.id == id?.trim()?.lowercase() }

    fun actionKind(id: String?): ActionKind? = actionKinds.firstOrNull { it.id == id?.trim() }

    fun dateMeaning(id: String?): ValueMeaning? = dateMeanings.firstOrNull { it.id == id?.trim() }

    fun amountMeaning(id: String?): ValueMeaning? = amountMeanings.firstOrNull { it.id == id?.trim() }

    companion object {
        /** "None of these": the answer of a list the letter does not fit, stored as no meaning, no kind, no value. */
        const val OTHER = "other"

        /** "There is nobody / nothing for this question". */
        const val NONE = "none"

        /** The category of a document that fits no category: the schema's neutral "Document". */
        const val DOCUMENT_CATEGORY = "document"

        /** The reference kind of an account number (an IBAN candidate). */
        const val IBAN_KIND = "iban"

        val DEFAULT = GemmaVocabulary()
    }
}

/**
 * Which stored slot a decided meaning fills, as data: the reading answers "this date is the due date", the registry says the `due_date`
 * slot holds it. A meaning with no slot (a period's start, a birth date) is stored as an open value under the label printed beside it.
 * Several meanings may share a slot ("total"); the first in the list wins it, so the order is the priority.
 */
object MeaningSlots {

    /** Date meaning id to slot key, in priority order per slot. */
    val DATES: List<Pair<String, String>> = listOf(
        "LETTER_DATE" to "letter_date",
        "DUE_DATE" to "due_date",
        "DEADLINE" to "objection_deadline",
        "APPOINTMENT" to "appointment",
    )

    /** Amount meaning id to slot key, in priority order per slot. */
    val AMOUNTS: List<Pair<String, String>> = listOf(
        "TOTAL_DUE" to "total",
        "INVOICE_TOTAL" to "total",
        "PREMIUM" to "total",
        "CREDIT" to "total",
        "FEE" to "fee",
    )

    fun slotOf(kind: MeaningKind, meaningId: String): String? =
        (if (kind == MeaningKind.DATE) DATES else AMOUNTS).firstOrNull { it.first == meaningId }?.second

    /** The priority of [meaningId] for its slot (lower first); the meaning's place in its list. */
    fun priority(kind: MeaningKind, meaningId: String): Int =
        (if (kind == MeaningKind.DATE) DATES else AMOUNTS).indexOfFirst { it.first == meaningId }.let { if (it < 0) Int.MAX_VALUE else it }
}
