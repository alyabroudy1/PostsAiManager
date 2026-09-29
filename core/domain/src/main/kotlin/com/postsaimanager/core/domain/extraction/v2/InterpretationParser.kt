package com.postsaimanager.core.domain.extraction.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads the model's answers into [RawInterpretation] (call 1) and [RawText] (call 2), without
 * trusting any of it.
 *
 * The grammar makes malformed JSON impossible, but a reply can still be cut off at the token limit,
 * so a parse failure is an expected outcome, reported as [Parsed.Bad] with the reason. This also
 * reads a recorded answer, which is how the scripted-interpreter tests and the benchmark replay work.
 */
object InterpretationParser {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    sealed interface Parsed<out T> {
        class Ok<T>(val value: T) : Parsed<T>
        class Bad(val reason: String) : Parsed<Nothing>
    }

    /** Call 1's answer. */
    fun parse(text: String): Parsed<RawInterpretation> {
        val root = objectOf(text) ?: return Parsed.Bad("no valid JSON object in the answer")
        val type = root.str("type") ?: return Parsed.Bad("answer has no type")

        val parties = root.array("parties").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RawParty(
                role = o.str("r") ?: return@mapNotNull null,
                id = o.str("id", StructuredGrammar.MAX_QUOTE_CHARS) ?: return@mapNotNull null,
                name = o.str("n", StructuredGrammar.MAX_PARTY_NAME_CHARS)?.trim()?.ifEmpty { null },
                kind = o.str("k"),
                relation = o.str("rel"),
                confidence = o.str("c"),
            )
        }.take(StructuredGrammar.MAX_PARTIES)

        val slots = LinkedHashMap<String, RawSlot>()
        (root["s"] as? JsonObject)?.forEach { (key, value) ->
            val o = value as? JsonObject ?: return@forEach // "NONE" (a plain string) means no value
            slots[key] = RawSlot(
                id = o.str("id", StructuredGrammar.MAX_QUOTE_CHARS),
                role = o.str("r"),
                rule = o.str("rule", StructuredGrammar.MAX_QUOTE_CHARS),
                ids = o.array("ids").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(StructuredGrammar.MAX_REF_IDS),
                confidence = o.str("c"),
            )
        }

        val extras = root.array("x").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RawExtra(
                label = o.str("lb", StructuredGrammar.MAX_EXTRA_LABEL_CHARS) ?: return@mapNotNull null,
                key = o.str("k", StructuredGrammar.MAX_EXTRA_KEY_CHARS).orEmpty(),
                id = o.str("id") ?: StructuredGrammar.NONE,
                value = o.str("v", StructuredGrammar.MAX_EXTRA_VALUE_CHARS).orEmpty(),
                confidence = o.str("c"),
            )
        }.take(StructuredGrammar.MAX_EXTRAS)

        return Parsed.Ok(
            RawInterpretation(
                type = type,
                typeConfidence = root.str("tc"),
                language = root.str("lang"),
                parties = parties,
                slots = slots,
                extras = extras,
            ),
        )
    }

    /** Call 2's answer. */
    fun parseText(text: String): Parsed<RawText> {
        val root = objectOf(text) ?: return Parsed.Bad("no valid JSON object in the answer")
        return Parsed.Ok(
            RawText(
                otherLabel = root.str("other", TextGrammar.MAX_OTHER_CHARS),
                title = root.str("title", TextGrammar.MAX_TITLE_CHARS),
                subject = root.str("subject", TextGrammar.MAX_SUBJECT_CHARS),
                summary = root.str("summary", TextGrammar.MAX_SUMMARY_CHARS),
                questions = root.array("qs")
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.take(TextGrammar.MAX_QUESTION_CHARS) }
                    .take(TextGrammar.MAX_QUESTIONS),
            ),
        )
    }

    private fun objectOf(text: String): JsonObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** The string at [key], cut to [maxChars] when given: the grammar does not bound string length, this does. */
    private fun JsonObject.str(key: String, maxChars: Int = Int.MAX_VALUE): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.take(maxChars)

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList() ?: emptyList()
}
