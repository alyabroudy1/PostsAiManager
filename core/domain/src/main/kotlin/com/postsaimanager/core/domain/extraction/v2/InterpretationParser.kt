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
                id = o.str("id") ?: return@mapNotNull null,
                kind = o.str("k"),
                relation = o.str("rel"),
                confidence = o.str("c"),
            )
        }

        val slots = LinkedHashMap<String, RawSlot>()
        (root["s"] as? JsonObject)?.forEach { (key, value) ->
            val o = value as? JsonObject ?: return@forEach // "NONE" (a plain string) means no value
            slots[key] = RawSlot(
                id = o.str("id"),
                role = o.str("r"),
                rule = o.str("rule"),
                ids = o.array("ids").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                confidence = o.str("c"),
            )
        }

        val extras = root.array("x").mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RawExtra(
                label = o.str("lb") ?: return@mapNotNull null,
                key = o.str("k").orEmpty(),
                id = o.str("id") ?: StructuredGrammar.NONE,
                value = o.str("v").orEmpty(),
                confidence = o.str("c"),
            )
        }

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
                otherLabel = root.str("other"),
                title = root.str("title"),
                subject = root.str("subject"),
                summary = root.str("summary"),
                questions = root.array("qs").mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
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

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList() ?: emptyList()
}
