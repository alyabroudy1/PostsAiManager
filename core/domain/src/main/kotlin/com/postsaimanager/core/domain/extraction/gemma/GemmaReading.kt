package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.gemma.GemmaSchema.Field
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
    val summary: String? = null,
    val keyInfo: List<GemmaFact> = emptyList(),
)

/** Reads the model's JSON into a [GemmaReading]; lenient about missing or mistyped members (each simply stays empty). */
object GemmaReadingParser {

    sealed interface Parsed {
        class Ok(val reading: GemmaReading) : Parsed
        class Bad(val reason: String) : Parsed
    }

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String): Parsed {
        val root = try {
            json.parseToJsonElement(text.trim()).jsonObject
        } catch (e: Exception) {
            return Parsed.Bad("the answer is not a JSON object: ${e.message?.take(80)}")
        }
        return Parsed.Ok(
            GemmaReading(
                asksReader = when (root.str(Field.ASKS_READER)?.lowercase()) {
                    GemmaVocabulary.YES -> true
                    GemmaVocabulary.NO -> false
                    else -> null
                },
                sender = root.party(Field.SENDER),
                addressee = root.party(Field.ADDRESSEE),
                contact = root.party(Field.CONTACT),
                subjectPerson = root.party(Field.SUBJECT_PERSON),
                dates = root.values(Field.DATES),
                amounts = root.values(Field.AMOUNTS),
                references = root.values(Field.REFERENCES),
                actions = root.items(Field.ACTIONS).mapNotNull { o ->
                    GemmaAction(o.str("kind") ?: return@mapNotNull null, o.str("dateId"), o.str("amountId"))
                },
                category = root.str(Field.CATEGORY),
                eventKind = root.str(Field.EVENT_KIND),
                language = root.str(Field.LANGUAGE),
                name = root.str(Field.NAME),
                summary = root.str(Field.SUMMARY),
                keyInfo = root.items(Field.KEY_INFO).mapNotNull { o ->
                    GemmaFact(o.str("label") ?: return@mapNotNull null, o.str("value") ?: return@mapNotNull null)
                },
            ),
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.party(key: String): GemmaParty? {
        val o = this[key] as? JsonObject ?: return null
        return GemmaParty(id = o.str("id"), text = o.str("name"), kind = o.str("kind"))
    }

    private fun JsonObject.items(key: String): List<JsonObject> =
        ((this[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }

    private fun JsonObject.values(key: String): List<GemmaValue> =
        items(key).map { GemmaValue(candidateId = it.str("candidateId"), value = it.str("value"), meaning = it.str("meaning") ?: it.str("kind")) }
}
