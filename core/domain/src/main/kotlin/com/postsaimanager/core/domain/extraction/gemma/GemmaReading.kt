package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Field
import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Item
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What the model answered, as it wrote it and before anything was checked. Every id may still be one that does not exist, every
 * text one that is not in the letter: [GemmaReadingVerifier] decides what stays.
 *
 * A party is named by an [id][GemmaParty.id] (a candidate or line id, or "none") for a letter with text lines, or by its own
 * [text][GemmaParty.text] for a picture-only reading.
 */
data class GemmaParty(val id: String? = null, val text: String? = null, val kind: String? = null)

/** A date or an amount (or a reference) the model chose: by candidate id, or as a [value] it read from the picture. */
data class GemmaValue(val candidateId: String? = null, val value: String? = null, val meaning: String? = null)

/** What the letter asks of its reader, with the date and the amount it says it for (candidate ids, or "none"). */
data class GemmaAction(val kind: String, val dateId: String? = null, val amountId: String? = null)

data class GemmaFact(val label: String, val value: String)

data class GemmaReading(
    /** The answer to "does this document ask its reader to do anything?": true for yes, false for no, null when it was not given. */
    val asksReader: Boolean? = null,
    /** The answer to "has it been paid already?"; null when it was not given. */
    val paid: PaidState? = null,
    val sender: GemmaParty? = null,
    val addressee: GemmaParty? = null,
    val contact: GemmaParty? = null,
    val subjectPerson: GemmaParty? = null,
    val dates: List<GemmaValue> = emptyList(),
    val amounts: List<GemmaValue> = emptyList(),
    val references: List<GemmaValue> = emptyList(),
    val actions: List<GemmaAction> = emptyList(),
    val category: String? = null,
    /** What the letter reports on the timeline, a kind of the event registry. */
    val eventKind: String? = null,
    val language: String? = null,
    val name: String? = null,
    /** Only a picture-only answer has these two; for a letter with text they are written by [GemmaTextWriter]. */
    val summary: String? = null,
    val keyInfo: List<GemmaFact> = emptyList(),
)

/**
 * Reads the model's JSON into a [GemmaReading]; lenient about missing or mistyped members (each simply stays empty).
 *
 * The answer is short: one-letter keys ([Field], [Item]) and codes for the words of the registries' lists ([CodeBook]); this is the one
 * place that maps them back to the ids, so everything after it speaks the registries' own ids. A word that is not a code (an id in full)
 * is read as that id.
 */
object GemmaReadingParser {

    sealed interface Parsed {
        class Ok(val reading: GemmaReading) : Parsed
        class Bad(val reason: String) : Parsed
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String, vocab: GemmaVocabulary = GemmaVocabulary.DEFAULT): Parsed {
        val root = try {
            json.parseToJsonElement(text.trim()).jsonObject
        } catch (e: Exception) {
            return Parsed.Bad("the answer is not a JSON object: ${e.message?.take(80)}")
        }
        // One entry per party that exists: the first of a role is the party (a model that lists a role twice has said the same thing twice).
        val parties = root.items(Field.PARTIES).mapNotNull { o ->
            val role = vocab.partyRoleCodes.idOf(o.str(Item.WHO)) ?: return@mapNotNull null
            role to GemmaParty(
                id = o.str(Item.ID), text = o.str(Item.NAME), kind = vocab.partyKindCodes.idOf(o.str(Item.KIND)),
            )
        }.distinctBy { it.first }.toMap()
        return Parsed.Ok(
            GemmaReading(
                asksReader = when (root.str(Field.ASKS_READER)?.lowercase()) {
                    GemmaVocabulary.YES -> true
                    GemmaVocabulary.NO -> false
                    else -> null
                },
                paid = PaidState.of(root.str(Field.PAID)),
                sender = parties[GemmaSchema.SENDER],
                addressee = parties[GemmaSchema.ADDRESSEE],
                contact = parties[GemmaSchema.CONTACT],
                subjectPerson = parties[GemmaSchema.SUBJECT_PERSON],
                dates = root.values(Field.DATES, vocab.dateMeaningCodes),
                amounts = root.values(Field.AMOUNTS, vocab.amountMeaningCodes),
                references = root.values(Field.REFERENCES, vocab.referenceKindCodes, kindKey = true),
                actions = root.items(Field.ACTIONS).mapNotNull { o ->
                    GemmaAction(vocab.actionKindCodes.idOf(o.str(Item.KIND)) ?: return@mapNotNull null, o.str(Item.DATE_ID), o.str(Item.AMOUNT_ID))
                },
                category = vocab.categoryCodes.idOf(root.str(Field.CATEGORY)),
                eventKind = vocab.eventKindCodes.idOf(root.str(Field.EVENT_KIND)),
                language = root.str(Field.LANGUAGE),
                name = root.str(Field.NAME),
                summary = root.str(Field.SUMMARY),
                keyInfo = root.items(Field.KEY_INFO).mapNotNull { o ->
                    GemmaFact(o.str(Item.LABEL) ?: return@mapNotNull null, o.str(Item.VALUE) ?: return@mapNotNull null)
                },
            ),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.items(key: String): List<JsonObject> =
        ((this[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }

    /** The entries of a list of values: the candidate's id (or the printed value of a picture-only answer) and its meaning or kind as an id. */
    private fun JsonObject.values(key: String, codes: CodeBook, kindKey: Boolean = false): List<GemmaValue> =
        items(key).map {
            GemmaValue(
                candidateId = it.str(Item.ID), value = it.str(Item.VALUE),
                meaning = codes.idOf(it.str(if (kindKey) Item.KIND else Item.MEANING) ?: it.str(Item.KIND)),
            )
        }
}
