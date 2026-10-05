package com.postsaimanager.core.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Checks a call's arguments against the tool's JSON schema before the tool runs: every required argument present, none unknown,
 * each of the declared type and, for an enum, one of its values. Returns what is wrong in a sentence the model can act on, or
 * null when the arguments are valid.
 */
object ArgumentValidator {

    fun validate(schema: JsonObject, args: JsonObject): String? {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        required.firstOrNull { it !in args }?.let { return "missing required argument \"$it\"" }
        for ((name, value) in args) {
            val property = properties[name] as? JsonObject
                ?: return "unknown argument \"$name\" (allowed: ${properties.keys.joinToString()})"
            typeError(name, property, value)?.let { return it }
        }
        return null
    }

    private fun typeError(name: String, property: JsonObject, value: kotlinx.serialization.json.JsonElement): String? {
        val type = (property["type"] as? JsonPrimitive)?.content
        val ok = when (type) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
            "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
            "array" -> value is JsonArray && value.all { it is JsonPrimitive && it.isString }
            else -> true
        }
        if (!ok) return "argument \"$name\" must be a $type"
        val allowed = (property["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        if (allowed.isNotEmpty() && (value as? JsonPrimitive)?.content !in allowed) {
            return "argument \"$name\" must be one of: ${allowed.joinToString()}"
        }
        return null
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
}
