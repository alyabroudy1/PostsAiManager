package com.postsaimanager.core.domain.ai

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The pictures of a chat message, as the message stores them: in its existing `mediaPath` column, one path as it is or several as
 * a JSON array (no schema change). Also the one place that says how many pictures a message takes and how a replayed history
 * names them.
 */
object MessageImages {

    /** The Gallery's limit (`MAX_IMAGE_COUNT`). */
    const val MAX_PER_MESSAGE = 10

    private val json = Json
    private val serializer = ListSerializer(String.serializer())

    fun encode(paths: List<String>): String? = when (paths.size) {
        0 -> null
        1 -> paths.single()
        else -> json.encodeToString(serializer, paths)
    }

    /** The paths [stored] holds; empty for null or blank. A value that is not a JSON array is one path. */
    fun decode(stored: String?): List<String> {
        if (stored.isNullOrBlank()) return emptyList()
        if (!stored.trimStart().startsWith("[")) return listOf(stored)
        return runCatching { json.decodeFromString(serializer, stored) }.getOrDefault(listOf(stored))
    }

    /**
     * What a rebuilt conversation says in place of the pixels: the model sees the picture only in the reply it was attached to
     * (as in the Gallery's own session replay), later turns carry this marker so the history is honest about it.
     */
    fun marker(count: Int): String = (1..count).joinToString(" ") { "[image: photo $it]" }
}
