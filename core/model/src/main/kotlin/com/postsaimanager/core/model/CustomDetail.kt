package com.postsaimanager.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A detail the user named themselves ("Customer number", "Opening hours", "Direct line"): a label and a value, on a profile or on a
 * contact person next to the predefined fields. The list keeps the user's order. What the user typed is never touched by a reading.
 */
@Serializable
data class CustomDetail(val label: String, val value: String)

/** The one owner of how a list of [CustomDetail] is stored (a JSON text in one column) and of the edits the user makes to it. */
object CustomDetails {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CustomDetail.serializer())

    /** The stored text of [details], or null when there are none (an empty column). */
    fun encode(details: List<CustomDetail>): String? =
        details.takeIf { it.isNotEmpty() }?.let { json.encodeToString(serializer, it) }

    /** The details stored in [text]; a missing or unreadable text is an empty list, never an error. */
    fun decode(text: String?): List<CustomDetail> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    /** Whether any label or value of [details] contains [query] (case-insensitive); what the profiles search looks at. */
    fun matches(details: List<CustomDetail>, query: String): Boolean =
        query.isNotBlank() && details.any { it.label.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }

    /** [details] without blank entries (a detail needs a label and a value), trimmed. Applied when the user saves. */
    fun cleaned(details: List<CustomDetail>): List<CustomDetail> =
        details.map { CustomDetail(it.label.trim(), it.value.trim()) }.filter { it.label.isNotEmpty() && it.value.isNotEmpty() }

    /** Appends a detail. */
    fun add(details: List<CustomDetail>, detail: CustomDetail): List<CustomDetail> = details + detail

    /** Replaces the detail at [index] (a bad index changes nothing). */
    fun replace(details: List<CustomDetail>, index: Int, detail: CustomDetail): List<CustomDetail> =
        if (index in details.indices) details.toMutableList().also { it[index] = detail } else details

    /** Removes the detail at [index] (a bad index changes nothing). */
    fun remove(details: List<CustomDetail>, index: Int): List<CustomDetail> =
        if (index in details.indices) details.filterIndexed { i, _ -> i != index } else details

    /** Moves the detail at [from] to position [to] (both clamped to the list; a bad [from] changes nothing). */
    fun move(details: List<CustomDetail>, from: Int, to: Int): List<CustomDetail> {
        if (from !in details.indices) return details
        val list = details.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return list
    }
}
