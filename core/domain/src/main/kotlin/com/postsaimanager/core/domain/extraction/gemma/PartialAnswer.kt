package com.postsaimanager.core.domain.extraction.gemma

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads what is complete of a text-step answer that was cut off (the token cap ended a looping generation): every fact object of the
 * `k` array that closed before the cut is kept, the rest is dropped. Nothing is guessed: a half-written fact is not a fact. The summary
 * of a cut-off answer is not salvaged (it comes before the facts, and a loop in it means it is not a summary).
 */
internal object PartialAnswer {

    /** The (summary, facts) of the complete part of [answer], or null when no fact was complete. The summary is always empty. */
    fun salvage(answer: String): Pair<String, List<Pair<String, String>>>? {
        val key = answer.indexOf("\"${GemmaTextWriter.KEY_FACTS}\"")
        if (key < 0) return null
        val open = answer.indexOf('[', key)
        if (open < 0) return null
        val facts = mutableListOf<Pair<String, String>>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1
        for (i in open + 1 until answer.length) {
            val c = answer[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> if (depth++ == 0) start = i
                '}' -> if (--depth == 0 && start >= 0) {
                    factOf(answer.substring(start, i + 1))?.let { facts += it }
                    start = -1
                }
                ']' -> if (depth == 0) break
            }
        }
        return if (facts.isEmpty()) null else "" to facts
    }

    private fun factOf(text: String): Pair<String, String>? {
        val obj = runCatching { Json { isLenient = true }.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val label = obj.str(GemmaTextWriter.KEY_LABEL)
        val value = obj.str(GemmaTextWriter.KEY_VALUE)
        return if (label.isEmpty() || value.isEmpty()) null else label to value
    }
}
