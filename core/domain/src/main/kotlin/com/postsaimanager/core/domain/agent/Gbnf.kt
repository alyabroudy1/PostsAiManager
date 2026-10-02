package com.postsaimanager.core.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Helpers that write GBNF grammar text from a tool's JSON schema, shared by the [ToolCallFormat]s. A grammar is a list of
 * rules; [Rules] keeps them in the order added so the output is stable (a golden test pins it).
 */
internal object Gbnf {

    /** One argument of a schema, in declaration order. */
    data class Arg(val name: String, val type: String, val required: Boolean, val values: List<String>)

    fun argsOf(schema: JsonObject): List<Arg> {
        val properties = schema["properties"] as? JsonObject ?: return emptyList()
        val required = (schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
        return properties.map { (name, value) ->
            val property = value as? JsonObject
            val type = (property?.get("type") as? JsonPrimitive)?.content ?: "string"
            val values = (property?.get("enum") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            Arg(name, type, name in required, values)
        }
    }

    /** A rule name from a tool name: lower-case letters, digits and dashes only. */
    fun ruleName(prefix: String, name: String): String = "$prefix-$name".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** [text] as a GBNF string literal. */
    fun literal(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    /** The rules of a grammar, in the order added. */
    class Rules {
        private val rules = LinkedHashMap<String, String>()

        fun add(name: String, body: String): String {
            rules[name] = body
            return name
        }

        fun render(): String = rules.entries.joinToString("\n") { (name, body) -> "$name ::= $body" } + "\n"
    }

    /** The JSON string, integer, number and boolean rules (the json.gbnf of llama.cpp, trimmed). */
    fun jsonValueRules(rules: Rules) {
        rules.add("jstring", "\"\\\"\" ( [^\"\\\\\\x7F\\x00-\\x1F] | \"\\\\\" ([\"\\\\bfnrt] | \"u\" [0-9a-fA-F]{4}) )* \"\\\"\"")
        rules.add("jinteger", "\"-\"? ([0-9] | [1-9] [0-9]{0,15})")
        rules.add("jnumber", "jinteger (\".\" [0-9]{1,16})?")
        rules.add("jboolean", "\"true\" | \"false\"")
        rules.add("jarray", "\"[\" ( jstring ( \", \" jstring )* )? \"]\"")
    }

    /** The JSON rule of an argument: its type, or the alternatives of an enum. */
    fun jsonValue(arg: Arg): String = when {
        arg.values.isNotEmpty() -> arg.values.joinToString(" | ", prefix = "( ", postfix = " )") { literal("\"" + escapeJson(it) + "\"") }
        else -> when (arg.type) {
            "integer" -> "jinteger"
            "number" -> "jnumber"
            "boolean" -> "jboolean"
            "array" -> "jarray"
            else -> "jstring"
        }
    }

    private fun escapeJson(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")
}
