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
 * It is flat on purpose (an object of ids, words and a few short texts: no nesting beyond one list of small objects), and it carries no
 * reasoning field: the model decides, the schema only limits what it can say. The enums come from the registries
 * ([GemmaVocabulary]); the ids come from this letter alone ([GemmaLetter]): a party is a candidate id or a line id of the letter, a
 * date is one of the letter's date candidates, so the model cannot write a value that is not on the page. "none" and "other" are
 * answers of their own.
 *
 * The order of the fields is the order the model writes them: [Field.ASKS_READER] first ("does this document ask its reader to do
 * anything?", which [GemmaReadingVerifier] holds the actions to), then the category (decided knowing it), the parties, the values, the
 * actions, and [Field.EVENT_KIND]: what the letter reports on the timeline, a kind of the event registry. The kind is asked in this one
 * call, not derived from the action kinds or the category afterwards, so the model's reading of the whole letter stays the only owner of
 * that meaning and no mapping table of meanings lives in code.
 *
 * A letter with no text lines (a page the OCR could not read) has nothing to choose from: the same fields are then free strings, and
 * the answer is read as unverified ([GemmaImageOnly]).
 */
object GemmaSchema {

    /** The document's language as a BCP-47 code ("de", "en", "ar"): the model writes it, [GemmaReadingVerifier] checks its shape. */
    const val MAX_LANGUAGE_CHARS = 8
    val LANGUAGE_CODE = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})?")
    const val MAX_NAME_CHARS = 60
    const val MAX_SUMMARY_CHARS = 280
    const val MAX_KEY_INFO = 6
    const val MAX_KEY_LABEL_CHARS = 30
    const val MAX_KEY_VALUE_CHARS = 80
    const val MAX_PARTY_TEXT_CHARS = 80
    const val MAX_DATES = 8
    const val MAX_AMOUNTS = 8
    const val MAX_REFERENCES = 8
    const val MAX_ACTIONS = 4

    /** The answer's field names, one list for the schema, the parser and the prompt. */
    object Field {
        /** "Does this document ask its reader to do anything?": yes or no, decided first, so the category and the actions follow from it. */
        const val ASKS_READER = "asksReader"
        const val EVENT_KIND = "eventKind"
        const val SENDER = "sender"
        const val ADDRESSEE = "addressee"
        const val CONTACT = "contact"
        const val SUBJECT_PERSON = "subjectPerson"
        const val DATES = "dates"
        const val AMOUNTS = "amounts"
        const val REFERENCES = "references"
        const val ACTIONS = "actions"
        const val CATEGORY = "category"
        const val LANGUAGE = "language"
        const val NAME = "name"
        const val SUMMARY = "summary"
        const val KEY_INFO = "keyInfo"
    }

    /** The four parties, in the order the answer lists them. */
    val PARTIES = listOf(Field.SENDER, Field.ADDRESSEE, Field.CONTACT, Field.SUBJECT_PERSON)

    fun build(letter: GemmaLetter, vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT): String =
        (if (letter.isImageOnly) imageOnly(vocab) else forLetter(letter, vocab)).toString()

    /** The ids a party may name: the letter's name candidates and every line, then "none". */
    fun partyIds(letter: GemmaLetter): List<String> =
        letter.candidatesOf(CandidateKind.NAME).map { it.id } + letter.lines.map { it.id } + GemmaVocabulary.NONE

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
            Field.CATEGORY to enumOf(vocab.categoryIds),
            Field.SENDER to party(parties, vocab),
            Field.ADDRESSEE to party(parties, vocab),
            Field.CONTACT to party(parties, vocab),
            Field.SUBJECT_PERSON to party(parties, vocab),
            Field.DATES to list(
                MAX_DATES, dates.isEmpty(),
                objectOf("candidateId" to enumOf(dates), "meaning" to enumOf(vocab.dateMeanings.map { it.id } + GemmaVocabulary.OTHER)),
            ),
            Field.AMOUNTS to list(
                MAX_AMOUNTS, amounts.isEmpty(),
                objectOf("candidateId" to enumOf(amounts), "meaning" to enumOf(vocab.amountMeanings.map { it.id } + GemmaVocabulary.OTHER)),
            ),
            Field.REFERENCES to referenceList(referenceIds(letter), vocab),
            Field.ACTIONS to list(
                MAX_ACTIONS, vocab.actionKinds.isEmpty(),
                objectOf(
                    "kind" to enumOf(vocab.actionKinds.map { it.id }),
                    "dateId" to enumOf(dates + GemmaVocabulary.NONE),
                    "amountId" to enumOf(amounts + GemmaVocabulary.NONE),
                ),
            ),
            Field.EVENT_KIND to enumOf(vocab.eventKindIds),
            Field.LANGUAGE to text(MAX_LANGUAGE_CHARS),
            Field.NAME to text(MAX_NAME_CHARS),
            Field.SUMMARY to text(MAX_SUMMARY_CHARS),
            Field.KEY_INFO to keyInfo(),
        )
    }

    /** The same answer when there is no OCR line to point at: names and values as short strings, to be checked by a person. */
    private fun imageOnly(vocab: GemmaVocabulary): JsonObject {
        fun party() = objectOf("name" to text(MAX_PARTY_TEXT_CHARS), "kind" to enumOf(vocab.partyKinds))
        return objectOf(
            Field.ASKS_READER to enumOf(listOf(GemmaVocabulary.NO, GemmaVocabulary.YES)),
            Field.CATEGORY to enumOf(vocab.categoryIds),
            Field.SENDER to party(),
            Field.ADDRESSEE to party(),
            Field.CONTACT to party(),
            Field.SUBJECT_PERSON to party(),
            Field.DATES to list(MAX_DATES, false, objectOf("value" to text(DATE_CHARS), "meaning" to enumOf(vocab.dateMeanings.map { it.id } + GemmaVocabulary.OTHER))),
            Field.AMOUNTS to list(MAX_AMOUNTS, false, objectOf("value" to text(AMOUNT_CHARS), "meaning" to enumOf(vocab.amountMeanings.map { it.id } + GemmaVocabulary.OTHER))),
            Field.REFERENCES to list(MAX_REFERENCES, false, objectOf("value" to text(MAX_PARTY_TEXT_CHARS), "kind" to enumOf(vocab.referenceKinds))),
            Field.ACTIONS to list(MAX_ACTIONS, vocab.actionKinds.isEmpty(), objectOf("kind" to enumOf(vocab.actionKinds.map { it.id }))),
            Field.EVENT_KIND to enumOf(vocab.eventKindIds),
            Field.LANGUAGE to text(MAX_LANGUAGE_CHARS),
            Field.NAME to text(MAX_NAME_CHARS),
            Field.SUMMARY to text(MAX_SUMMARY_CHARS),
            Field.KEY_INFO to keyInfo(),
        )
    }

    private const val DATE_CHARS = 10
    private const val AMOUNT_CHARS = 24

    private fun party(ids: List<String>, vocab: GemmaVocabulary) =
        objectOf("id" to enumOf(ids), "kind" to enumOf(vocab.partyKinds))

    private fun referenceList(ids: List<String>, vocab: GemmaVocabulary) =
        list(MAX_REFERENCES, ids.isEmpty(), objectOf("candidateId" to enumOf(ids), "kind" to enumOf(vocab.referenceKinds)))

    private fun keyInfo() = list(
        MAX_KEY_INFO, false,
        objectOf("label" to text(MAX_KEY_LABEL_CHARS), "value" to text(MAX_KEY_VALUE_CHARS)),
    )

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
