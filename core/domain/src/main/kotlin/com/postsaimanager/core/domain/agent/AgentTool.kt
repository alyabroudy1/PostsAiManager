package com.postsaimanager.core.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One function the model may call (function calling, as Gemini and Claude tool use work, on the device).
 *
 * A tool is small and typed: [parameters] is a JSON schema of its arguments (see [ToolParams]), the loop validates the
 * model's arguments against it before [execute] runs, and the tool itself guards its inputs (a value must come from where the
 * feature says it may). What a tool returns is shown to the model as its result; what the user sees is rendered by the feature
 * from the stored call and result, never as raw JSON.
 *
 * The framework knows nothing about forms: a feature registers its tools in a [ToolRegistry] and gives the [AgentLoop] an [AgentSpec].
 */
interface AgentTool {
    val name: String

    /** What the tool does and when to use it, in plain words for the model. */
    val description: String

    /** A JSON schema (`type: object`) of the arguments; build it with [ToolParams.schema]. */
    val parameters: JsonObject

    /** True when a successful call hands the conversation back to the user (the reply comes back as the next user message). */
    val endsTurn: Boolean get() = false

    suspend fun execute(args: JsonObject, context: AgentContext): ToolResult
}

/** How a tool is described to the model: its name, description and argument schema. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

fun AgentTool.spec(): ToolSpec = ToolSpec(name, description, parameters)

/**
 * What a tool returns to the model: success with data, or an error that says why (the model sees it and corrects itself).
 * Always serialised as one JSON object with an `ok` flag.
 */
class ToolResult private constructor(val ok: Boolean, val data: JsonObject) {

    fun toJson(): JsonObject = buildJsonObject {
        put("ok", ok)
        data.forEach { (key, value) -> put(key, value) }
    }

    /** The text the model reads. */
    fun toModelText(): String = toJson().toString()

    /** This result with one more entry (the running state summary, a hint). */
    fun with(key: String, value: String): ToolResult = ToolResult(ok, JsonObject(data + (key to JsonPrimitive(value))))

    /** This result without the entry [key]. */
    fun without(key: String): ToolResult = ToolResult(ok, JsonObject(data - key))

    /** The text of the entry [key], when it is a string. */
    fun text(key: String): String? = (data[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    val errorMessage: String? get() = (data["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        fun ok(data: JsonObject = JsonObject(emptyMap())): ToolResult = ToolResult(true, data)

        fun ok(vararg entries: Pair<String, JsonElement>): ToolResult = ok(JsonObject(mapOf(*entries)))

        fun error(message: String): ToolResult = ToolResult(false, JsonObject(mapOf("error" to JsonPrimitive(message))))

        /** The result a stored `toModelText` was written from; null when it cannot be read. */
        fun fromJson(json: JsonObject): ToolResult {
            val ok = (json["ok"] as? JsonPrimitive)?.booleanOrNull ?: false
            return ToolResult(ok, JsonObject(json.filterKeys { it != "ok" }))
        }
    }
}

/** The kinds of argument a tool can take: what the schema, the grammar and the validator all understand. */
enum class ParamType(val schemaName: String) { STRING("string"), INTEGER("integer"), NUMBER("number"), BOOLEAN("boolean"), STRING_ARRAY("array") }

/** One argument of a tool. [values], when set, are the only strings it can have (an enum). */
data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val values: List<String> = emptyList(),
)

/** Builds the JSON schema of a tool's arguments. */
object ToolParams {

    fun string(name: String, description: String, required: Boolean = true) = ToolParam(name, ParamType.STRING, description, required)

    fun enumString(name: String, description: String, values: List<String>, required: Boolean = true) =
        ToolParam(name, ParamType.STRING, description, required, values)

    fun integer(name: String, description: String, required: Boolean = true) = ToolParam(name, ParamType.INTEGER, description, required)

    fun boolean(name: String, description: String, required: Boolean = true) = ToolParam(name, ParamType.BOOLEAN, description, required)

    fun stringArray(name: String, description: String, required: Boolean = true) = ToolParam(name, ParamType.STRING_ARRAY, description, required)

    /** `{"type":"object","properties":{…},"required":[…]}`; required arguments are listed first, in the order given. */
    fun schema(vararg params: ToolParam): JsonObject {
        val ordered = params.filter { it.required } + params.filterNot { it.required }
        return buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    ordered.forEach { p ->
                        put(
                            p.name,
                            buildJsonObject {
                                put("type", p.type.schemaName)
                                if (p.type == ParamType.STRING_ARRAY) put("items", buildJsonObject { put("type", "string") })
                                // A self-evident argument needs no words: the description is part of the standing prompt, and a small window is dear.
                                if (p.description.isNotBlank()) put("description", p.description)
                                if (p.values.isNotEmpty()) put("enum", JsonArray(p.values.map(::JsonPrimitive)))
                            },
                        )
                    }
                },
            )
            put("required", JsonArray(ordered.filter { it.required }.map { JsonPrimitive(it.name) }))
        }
    }
}

/** An argument read from the call, or null when it is absent or not of that type (the validator has already rejected those). */
fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.strings(name: String): List<String>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
