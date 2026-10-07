package com.postsaimanager.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One tool call the model made during a reply, with the result the model got back: what a faithful replay of the turn needs.
 *
 * A chat model that is shown earlier assistant turns ("I prepared the reminder, check the card") with no tool call in front of
 * them learns that this is how such a turn looks and copies it, without calling anything. So the calls travel with the turn
 * that made them ([AiMessage.toolTrace]) and a rebuilt conversation replays them as tool-call turns.
 *
 * @param name the tool, as the model called it (`load_skill`, `run_intent`).
 * @param argumentsJson the arguments as a JSON object, keyed by the tool's own parameter names.
 * @param resultJson what the tool answered, as a JSON object of strings (for `run_intent` the status the model read: "proposed
 *   to the user, waiting for their confirmation on the card").
 */
@Serializable
data class ToolExchange(
    val name: String,
    val argumentsJson: String,
    val resultJson: String,
) {
    /** Characters this exchange adds to a prompt, for the history budget. */
    val promptChars: Int get() = name.length + argumentsJson.length + resultJson.length
}

/** The stored and wire form of a reply's [ToolExchange]s: one JSON array, empty string for none. */
object ToolTrace {

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ToolExchange.serializer())

    fun encode(exchanges: List<ToolExchange>): String =
        if (exchanges.isEmpty()) "" else json.encodeToString(serializer, exchanges)

    /** The exchanges [text] holds; empty for null, blank or anything that is not a trace (the column held something else). */
    fun decode(text: String?): List<ToolExchange> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
    }
}
