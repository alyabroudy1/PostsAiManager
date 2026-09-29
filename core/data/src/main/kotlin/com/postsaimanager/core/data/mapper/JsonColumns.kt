package com.postsaimanager.core.data.mapper

import com.postsaimanager.core.model.TextBounds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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

    fun encodeBounds(bounds: TextBounds?): String? =
        bounds?.let { runCatching { json.encodeToString(TextBounds.serializer(), it) }.getOrNull() }

    fun decodeBounds(text: String?): TextBounds? =
        if (text.isNullOrBlank()) null else runCatching { json.decodeFromString(TextBounds.serializer(), text) }.getOrNull()
}
