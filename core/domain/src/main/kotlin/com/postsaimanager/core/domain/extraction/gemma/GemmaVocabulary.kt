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
import com.postsaimanager.core.domain.timeline.EventKinds

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
    val eventKinds: EventKinds = EventKinds.DEFAULT,
) {

    /** The timeline kinds the reader may name for what the letter reports: the registry's scored ones, and "information" when none fits. */
    val eventKindIds: List<String> = eventKinds.scored.map { it.id } + EventKinds.INFORMATION

    /** The registry's kind for [id] when it is one the reader may name; null for any other word. */
    fun eventKind(id: String?): String? = id?.trim()?.takeIf { it in eventKindIds }

    val dateMeanings: List<ValueMeaning> get() = meanings.of(MeaningKind.DATE)
    val amountMeanings: List<ValueMeaning> get() = meanings.of(MeaningKind.AMOUNT)

    /** The kinds a reference may be named: the schema's reference slots (invoice, customer, contract, case ...), the account, and other. */
    val referenceKinds: List<String> = schema.allSlots.filter { it.kind == SlotKind.REFERENCE }.map { it.json }.distinct() + IBAN_KIND + OTHER

    /** The category words: the registry's ids and the one for a document that fits none. */
    val categoryIds: List<String> = categories.map { it.id } + DOCUMENT_CATEGORY

    val partyKinds: List<String> = PartyKind.entries.map { it.name.lowercase() }

    /**
     * The words of every list as short codes (see [CodeBook]): the answer is written with the codes, which are cheaper to write than
     * the ids, and the parser maps them back. The registries stay the owners of the ids; a code is only the id's place in its list.
     */
    val categoryCodes = CodeBook("c", categoryIds)
    val dateMeaningCodes = CodeBook("d", dateMeanings.map { it.id } + OTHER)
    val amountMeaningCodes = CodeBook("m", amountMeanings.map { it.id } + OTHER)
    val actionKindCodes = CodeBook("k", actionKinds.map { it.id })
    val referenceKindCodes = CodeBook("r", referenceKinds)
    val eventKindCodes = CodeBook("e", eventKindIds)
    val partyKindCodes = CodeBook("t", partyKinds)
    val partyRoleCodes = CodeBook("w", GemmaSchema.PARTIES)

    /**
     * The amount meanings a letter's answer lists per amount: every one but the amount to pay, which has a field of its own
     * ([GemmaSchema.Field.TO_PAY]) so that one amount, never several, can claim it.
     */
    val listedAmountMeanings: List<ValueMeaning> get() = amountMeanings.filter { it.id != TO_PAY_MEANING }

    /** The codes the schema offers for an amount's meaning in a letter with text: all the codes but the amount to pay's. */
    val listedAmountMeaningCodes: List<String> get() = amountMeaningCodes.codes.filter { it != amountMeaningCodes.codeOf(TO_PAY_MEANING) }

    fun category(id: String?): DocCategory? = categories.firstOrNull { it.id == id?.trim()?.lowercase() }

    fun actionKind(id: String?): ActionKind? = actionKinds.firstOrNull { it.id == id?.trim() }

    fun dateMeaning(id: String?): ValueMeaning? = dateMeanings.firstOrNull { it.id == id?.trim() }

    fun amountMeaning(id: String?): ValueMeaning? = amountMeanings.firstOrNull { it.id == id?.trim() }

    companion object {
        /** "None of these": the answer of a list the letter does not fit, stored as no meaning, no kind, no value. */
        const val OTHER = "other"

        /** The meaning (registry id) of the one amount the reader has to pay: asked as its own field, not once per amount. */
        const val TO_PAY_MEANING = "TOTAL_DUE"

        /** "There is nobody / nothing for this question". */
        const val NONE = "none"

        /** The two answers of "does this document ask its reader to do anything?". */
        const val YES = "yes"
        const val NO = "no"

        /** The category of a document that fits no category: the schema's neutral "Document". */
        const val DOCUMENT_CATEGORY = "document"

        /** The reference kind of an account number (an IBAN candidate). */
        const val IBAN_KIND = "iban"

        val DEFAULT = GemmaVocabulary()
    }
}

/**
 * The ids of one registry list as short codes: the first is `<prefix>1`, the next `<prefix>2` and so on, so the model writes one or two
 * tokens instead of an id such as `INVOICE_TOTAL`. The prompt lists each code with the registry's own sentence for it.
 *
 * Data, not words: nothing here knows what an id means; the code is the id's position, built from the registry at start. A word that is
 * not a code of the book (an id written in full) is read as that id, so an answer in either spelling is understood.
 */
class CodeBook(prefix: String, ids: List<String>) {

    private val listed = ids.distinct()

    /** The codes in the list's order. */
    val codes: List<String> = listed.indices.map { "$prefix${it + 1}" }

    private val idByCode = codes.zip(listed).toMap()

    /** The code of [id], or null when the list does not hold it. */
    fun codeOf(id: String): String? = listed.indexOf(id).takeIf { it >= 0 }?.let { codes[it] }

    /** The id behind [word] when it is one of the codes; any other word is returned as it was written (it may be an id in full). */
    fun idOf(word: String?): String? {
        val w = word?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return idByCode[w.lowercase()] ?: w
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
