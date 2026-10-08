package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The JSON Schema the reader's answer is constrained to (LiteRT-LM `ResponseFormat.json`), built for each letter.
 *
 * **Short on purpose.** On the phone the answer is written token by token (about 14 a second), so the answer is the part of a reading that
 * costs the most time. Everything that can be short is: the keys are one letter ([Field], [Item]); the words of the registries' lists
 * (categories, meanings, action kinds, event kinds, kinds of party and of reference) are short codes ([CodeBook]) that [GemmaReadingParser]
 * maps back; the parties are a list with one entry per party that exists (an empty one is never written); the lists are as short as the
 * letter allows. The free texts that take the most tokens, the summary and the key facts, are not part of this answer at all: they are
 * written by a second, lower-priority step once the reading is stored ([GemmaTextWriter]). The name stays: the title needs it.
 *
 * It is flat on purpose, and it carries no reasoning field: the model decides, the schema only limits what it can say. The words come
 * from the registries ([GemmaVocabulary]); the ids come from this letter alone ([GemmaLetter]): a party is a candidate id or a line id of
 * the letter, a date is one of the letter's date candidates, so the model cannot write a value that is not on the page. "none" and
 * "other" are answers of their own.
 *
 * The order of the fields is the order the model writes them: [Field.ASKS_READER] first ("does this document ask its reader to do
 * anything?", which [GemmaReadingVerifier] holds the actions to), then [Field.PAID] ("has it been paid already?", which the verifier holds
 * the actions and the amounts' meanings to), then the category (decided knowing both), the parties, the values, the actions, and
 * [Field.EVENT_KIND]: what the letter reports on the timeline, a kind of the event registry. The kind is asked in this one call, not
 * derived from the action kinds or the category afterwards, so the model's reading of the whole letter stays the only owner of that
 * meaning and no mapping table of meanings lives in code.
 *
 * A letter with no text lines (a page the OCR could not read) has nothing to choose from: the same fields are then free strings, and
 * the answer is read as unverified ([GemmaImageOnly]); there is no text to write a second step from, so it also carries the summary and
 * the key facts.
 */
object GemmaSchema {

    /** The document's language as a BCP-47 code ("de", "en", "ar"): the model writes it, [GemmaReadingVerifier] checks its shape. */
    const val MAX_LANGUAGE_CHARS = 8
    val LANGUAGE_CODE = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?")
    const val MAX_NAME_CHARS = 60
    const val MAX_PARTY_TEXT_CHARS = 80
    const val MAX_PARTIES = 4
    const val MAX_DATES = 6
    const val MAX_AMOUNTS = 4
    const val MAX_REFERENCES = 5
    const val MAX_ACTIONS = 3

    /** Only a picture-only reading carries these two (the second step has no text to write from). */
    const val MAX_SUMMARY_CHARS = 280
    const val MAX_KEY_INFO = 6
    const val MAX_KEY_LABEL_CHARS = 30
    const val MAX_KEY_VALUE_CHARS = 80

    /** The answer's field names: one letter each, one list for the schema, the parser and the prompt. */
    object Field {
        /** "Does this document ask its reader to do anything?": yes or no, decided first, so the category and the actions follow from it. */
        const val ASKS_READER = "a"

        /** "Has what it is about been paid already?" ([PaidState]), decided right after, before the category. */
        const val PAID = "p"
        const val CATEGORY = "c"

        /** The parties that exist, one entry each: who ([Item.WHO]), which line or candidate ([Item.ID]), what kind ([Item.KIND]). */
        const val PARTIES = "r"
        const val DATES = "d"
        const val AMOUNTS = "m"

        /** The one amount the reader has to pay (a candidate id, or "none"): one field, so no two amounts can both claim it. */
        const val TO_PAY = "t"
        const val REFERENCES = "f"
        const val ACTIONS = "k"
        const val EVENT_KIND = "e"
        const val LANGUAGE = "l"
        const val NAME = "n"

        /** Picture-only answers only. */
        const val SUMMARY = "s"
        const val KEY_INFO = "y"
    }

    /** The keys inside the entries of the lists. */
    object Item {
        const val WHO = "w"
        const val ID = "i"
        const val KIND = "k"
        const val MEANING = "m"
        const val DATE_ID = "d"
        const val AMOUNT_ID = "a"

        /** Picture-only entries: the text as printed. */
        const val NAME = "n"
        const val VALUE = "v"
        const val LABEL = "l"
    }

    /** The parties the answer may list, in the order of their codes ([GemmaVocabulary.partyRoleCodes]). */
    const val SENDER = "sender"
    const val ADDRESSEE = "addressee"
    const val CONTACT = "contact"
    const val SUBJECT_PERSON = "subjectPerson"
    val PARTIES = listOf(SENDER, ADDRESSEE, CONTACT, SUBJECT_PERSON)

    fun build(letter: GemmaLetter, vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT): String =
        (if (letter.isImageOnly) imageOnly(vocab) else forLetter(letter, vocab)).toString()

    /**
     * The ids a party may name: the letter's name candidates first, then the lines (the fallback for a name no candidate holds), then
     * "none". The label of a label/value pair ("Ansprechpartnerin") is no line to choose: its value is the party.
     */
    fun partyIds(letter: GemmaLetter): List<String> =
        letter.candidatesOf(CandidateKind.NAME).map { it.id } + letter.lines.filterNot { it.isLabel }.map { it.id } + GemmaVocabulary.NONE

    fun dateIds(letter: GemmaLetter): List<String> = letter.candidatesOf(CandidateKind.DATE, CandidateKind.DATETIME).map { it.id }

    fun amountIds(letter: GemmaLetter): List<String> = letter.candidatesOf(CandidateKind.AMOUNT).map { it.id }

    /** What a reference may point at: reference numbers, accounts and the contact values (phone, e-mail, BIC) the letter holds. */
    fun referenceIds(letter: GemmaLetter): List<String> =
        letter.candidatesOf(CandidateKind.REFERENCE, CandidateKind.IBAN, CandidateKind.PHONE, CandidateKind.EMAIL, CandidateKind.BIC).map { it.id }

    private fun forLetter(letter: GemmaLetter, vocab: GemmaVocabulary): JsonObject {
        val parties = partyIds(letter)
        val dates = dateIds(letter)
        val amounts = amountIds(letter)
        return objectOf(
            Field.ASKS_READER to enumOf(listOf(GemmaVocabulary.NO, GemmaVocabulary.YES)),
            Field.PAID to enumOf(PaidState.entries.map { it.id }),
            Field.CATEGORY to enumOf(vocab.categoryCodes.codes),
            // One required field per role, each an id of this letter or "none": a decision for every role, never a silent skip.
            *PARTIES.map { role ->
                role to objectOf(Item.ID to enumOf(parties), Item.KIND to enumOf(vocab.partyKindCodes.codes))
            }.toTypedArray(),
            Field.DATES to list(
                MAX_DATES, dates.isEmpty(),
                objectOf(Item.ID to enumOf(dates), Item.MEANING to enumOf(vocab.dateMeaningCodes.codes)),
            ),
            Field.AMOUNTS to list(
                MAX_AMOUNTS, amounts.isEmpty(),
                objectOf(Item.ID to enumOf(amounts), Item.MEANING to enumOf(vocab.listedAmountMeaningCodes)),
            ),
            Field.TO_PAY to enumOf(amounts + GemmaVocabulary.NONE),
            Field.REFERENCES to referenceList(referenceIds(letter), vocab),
            Field.ACTIONS to list(
                MAX_ACTIONS, vocab.actionKinds.isEmpty(),
                objectOf(
                    Item.KIND to enumOf(vocab.actionKindCodes.codes),
                    Item.DATE_ID to enumOf(dates + GemmaVocabulary.NONE),
                    Item.AMOUNT_ID to enumOf(amounts + GemmaVocabulary.NONE),
                ),
            ),
            Field.EVENT_KIND to enumOf(vocab.eventKindCodes.codes),
            Field.LANGUAGE to text(MAX_LANGUAGE_CHARS),
            Field.NAME to text(MAX_NAME_CHARS),
        )
    }

    /** The same answer when there is no OCR line to point at: names and values as short strings, to be checked by a person. */
    private fun imageOnly(vocab: GemmaVocabulary): JsonObject = objectOf(
        Field.ASKS_READER to enumOf(listOf(GemmaVocabulary.NO, GemmaVocabulary.YES)),
        Field.PAID to enumOf(PaidState.entries.map { it.id }),
        Field.CATEGORY to enumOf(vocab.categoryCodes.codes),
        Field.PARTIES to list(
            MAX_PARTIES, false,
            objectOf(Item.WHO to enumOf(vocab.partyRoleCodes.codes), Item.NAME to text(MAX_PARTY_TEXT_CHARS), Item.KIND to enumOf(vocab.partyKindCodes.codes)),
        ),
        Field.DATES to list(MAX_DATES, false, objectOf(Item.VALUE to text(DATE_CHARS), Item.MEANING to enumOf(vocab.dateMeaningCodes.codes))),
        Field.AMOUNTS to list(MAX_AMOUNTS, false, objectOf(Item.VALUE to text(AMOUNT_CHARS), Item.MEANING to enumOf(vocab.amountMeaningCodes.codes))),
        Field.REFERENCES to list(MAX_REFERENCES, false, objectOf(Item.VALUE to text(MAX_PARTY_TEXT_CHARS), Item.KIND to enumOf(vocab.referenceKindCodes.codes))),
        Field.ACTIONS to list(MAX_ACTIONS, vocab.actionKinds.isEmpty(), objectOf(Item.KIND to enumOf(vocab.actionKindCodes.codes))),
        Field.EVENT_KIND to enumOf(vocab.eventKindCodes.codes),
        Field.LANGUAGE to text(MAX_LANGUAGE_CHARS),
        Field.NAME to text(MAX_NAME_CHARS),
        Field.SUMMARY to text(MAX_SUMMARY_CHARS),
        Field.KEY_INFO to list(
            MAX_KEY_INFO, false,
            objectOf(Item.LABEL to text(MAX_KEY_LABEL_CHARS), Item.VALUE to text(MAX_KEY_VALUE_CHARS)),
        ),
    )

    private const val DATE_CHARS = 10
    private const val AMOUNT_CHARS = 24

    private fun referenceList(ids: List<String>, vocab: GemmaVocabulary) =
        list(MAX_REFERENCES, ids.isEmpty(), objectOf(Item.ID to enumOf(ids), Item.KIND to enumOf(vocab.referenceKindCodes.codes)))

    /** An object whose properties are all required, in the order given. */
    private fun objectOf(vararg properties: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        putJsonArray("required") { properties.forEach { add(JsonPrimitive(it.first)) } }
        put("additionalProperties", false)
    }

    /** A list of [item]s with at most [max] entries; none at all when the letter has nothing to point at ([empty]). */
    private fun list(max: Int, empty: Boolean, item: JsonElement): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", item)
        put("maxItems", if (empty) 0 else max)
    }

    /** An enum of [values]; a list with no value at all cannot be an enum, so it says "none". */
    private fun enumOf(values: List<String>): JsonObject = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.distinct().ifEmpty { listOf(GemmaVocabulary.NONE) }.map(::JsonPrimitive)))
    }

    private fun text(maxChars: Int): JsonObject = buildJsonObject {
        put("type", "string")
        put("maxLength", maxChars)
    }
}
