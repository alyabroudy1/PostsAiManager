package com.postsaimanager.core.data.mapper

import com.postsaimanager.core.model.ActionItem
import com.postsaimanager.core.model.FieldAlternative
import com.postsaimanager.core.model.TextBounds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Reads and writes the few structured values stored as JSON text in a column (a list of strings, a
 * box). Lenient on read: a value that cannot be parsed reads as absent, never as a failure of the row.
 */
internal object JsonColumns {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings = ListSerializer(String.serializer())

    fun encodeStrings(values: List<String>): String? =
        values.takeIf { it.isNotEmpty() }?.let { runCatching { json.encodeToString(strings, it) }.getOrNull() }

    fun decodeStrings(text: String?): List<String> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(strings, text) }.getOrDefault(emptyList())

    private val actionList = ListSerializer(ActionItem.serializer())

    fun encodeActions(values: List<ActionItem>): String? =
        values.takeIf { it.isNotEmpty() }?.let { runCatching { json.encodeToString(actionList, it) }.getOrNull() }

    /**
     * The stored actions: a JSON list of objects. A value stored by an earlier build (a list of plain sentences the model wrote) holds no
     * object, so it reads as no actions; the background re-read that the extractor version triggers writes the new shape. An entry that
     * cannot be read is dropped alone.
     */
    fun decodeActions(text: String?): List<ActionItem> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonObject)?.let { runCatching { json.decodeFromJsonElement(ActionItem.serializer(), it) }.getOrNull() }
        }
    }

    private val alternativeList = ListSerializer(FieldAlternative.serializer())

    fun encodeAlternatives(values: List<FieldAlternative>): String? =
        values.takeIf { it.isNotEmpty() }?.let { runCatching { json.encodeToString(alternativeList, it) }.getOrNull() }

    fun decodeAlternatives(text: String?): List<FieldAlternative> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(alternativeList, text) }.getOrDefault(emptyList())

    fun encodeBounds(bounds: TextBounds?): String? =
        bounds?.let { runCatching { json.encodeToString(TextBounds.serializer(), it) }.getOrNull() }

    fun decodeBounds(text: String?): TextBounds? =
        if (text.isNullOrBlank()) null else runCatching { json.decodeFromString(TextBounds.serializer(), text) }.getOrNull()
}
