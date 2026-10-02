package com.postsaimanager.core.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Qwen3.5's NATIVE tool-calling convention, read from the model's own chat template (llama.cpp's `Qwen3.5-4B.jinja`, which
 * llama.cpp parses as "Qwen3-Coder XML"): the tools are JSON objects inside `<tools>` in the system turn, a call is
 *
 * ```
 * <tool_call>
 * <function=name>
 * <parameter=arg>
 * value
 * </parameter>
 * </function>
 * </tool_call>
 * ```
 *
 * (a string value raw, any other type as JSON) and a result is a user turn wrapped in `<tool_response>`. The template does NOT
 * describe tools when the engine applies it without a tools list, which is how the on-device engine renders a chat, so
 * [describeTools] writes the section itself, word for word as the template would. The grammar leaves a value no `<`, so a
 * value can never close its own tag.
 */
class QwenToolCallFormat : ToolCallFormat {

    override fun describeTools(tools: List<ToolSpec>): String = buildString {
        append("# Tools\n\nYou have access to the following functions:\n\n<tools>")
        tools.forEach { append("\n").append(toolJson(it)) }
        append("\n</tools>\n\n")
        append("If you choose to call a function ONLY reply in the following format with NO suffix:\n\n")
        append("<tool_call>\n<function=example_function_name>\n<parameter=example_parameter_1>\nvalue_1\n</parameter>\n")
        append("<parameter=example_parameter_2>\nvalue_2\n</parameter>\n</function>\n</tool_call>\n\n")
        append("Reminder: reply with exactly one function call and nothing else; required parameters MUST be specified.")
    }

    override fun grammar(tools: List<ToolSpec>): String {
        val rules = Gbnf.Rules()
        rules.add("root", "\"<tool_call>\\n\" call \"\\n</tool_call>\"")
        rules.add("call", tools.joinToString(" | ") { Gbnf.ruleName("call", it.name) })
        tools.forEach { tool ->
            val args = Gbnf.argsOf(tool.parameters)
            val parts = args.map { arg ->
                val param = rules.add(
                    Gbnf.ruleName("param-" + tool.name, arg.name),
                    "\"<parameter=${arg.name}>\\n\" ${value(arg)} \"\\n</parameter>\\n\"",
                )
                if (arg.required) param else "$param?"
            }
            val body = listOf(Gbnf.literal("<function=${tool.name}>\n")) + parts + Gbnf.literal("</function>")
            rules.add(Gbnf.ruleName("call", tool.name), body.joinToString(" "))
        }
        rules.add("xtext", "[^<\\x00-\\x1F]+")
        Gbnf.jsonValueRules(rules)
        return rules.render()
    }

    /** A string argument is free text with no `<`; every other type is a JSON value; an enum is its literals. */
    private fun value(arg: Gbnf.Arg): String = when {
        arg.values.isNotEmpty() -> arg.values.joinToString(" | ", prefix = "( ", postfix = " )") { Gbnf.literal(it) }
        arg.type == "string" -> "xtext"
        else -> Gbnf.jsonValue(arg)
    }

    override fun parse(reply: String, tools: List<ToolSpec>): ParsedCall {
        val open = reply.indexOf(CALL_OPEN)
        // The Qwen3-Coder family sometimes leaves the outer tag out: the function block alone is still a call.
        val body = if (open >= 0) reply.substring(open + CALL_OPEN.length) else reply
        val function = FUNCTION.find(body) ?: return ParsedCall.Invalid("the reply has no <function=name> block")
        val name = function.groupValues[1].trim()
        val tool = tools.firstOrNull { it.name == name }
            ?: return ParsedCall.Invalid("unknown function \"$name\" (known: ${tools.joinToString { it.name }})")
        val rest = body.substring(function.range.last + 1).substringBefore("</function>")
        val types = Gbnf.argsOf(tool.parameters).associate { it.name to it.type }
        val args = LinkedHashMap<String, JsonElement>()
        for (match in PARAMETER.findAll(rest)) {
            val argName = match.groupValues[1]
            val raw = match.groupValues[2]
            args[argName] = typed(types[argName] ?: "string", raw)
                ?: return ParsedCall.Invalid("the value of \"$argName\" is not a ${types[argName]}")
        }
        return ParsedCall.Call(name, JsonObject(args))
    }

    override fun renderCall(name: String, args: JsonObject, tools: List<ToolSpec>): String = buildString {
        append("<tool_call>\n<function=").append(name).append(">\n")
        args.forEach { (key, value) ->
            val text = if (value is JsonPrimitive && value.isString) value.content else value.toString()
            append("<parameter=").append(key).append(">\n").append(text).append("\n</parameter>\n")
        }
        append("</function>\n</tool_call>")
    }

    override fun renderResult(name: String, resultText: String): String = "<tool_response>\n$resultText\n</tool_response>"

    private fun typed(type: String, raw: String): JsonElement? = when (type) {
        "string" -> JsonPrimitive(raw)
        "integer" -> raw.trim().toLongOrNull()?.let(::JsonPrimitive)
        "number" -> raw.trim().toDoubleOrNull()?.let(::JsonPrimitive)
        "boolean" -> raw.trim().lowercase().let { if (it == "true" || it == "false") JsonPrimitive(it.toBoolean()) else null }
        "array" -> runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull()?.takeIf { it is JsonArray }
        else -> JsonPrimitive(raw)
    }

    private fun toolJson(tool: ToolSpec): String = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            },
        )
    }.toString()

    private companion object {
        const val CALL_OPEN = "<tool_call>"
        val FUNCTION = Regex("<function=([^>]+)>")
        val PARAMETER = Regex("<parameter=([^>\\s]+)>\\n?(.*?)\\n?</parameter>", RegexOption.DOT_MATCHES_ALL)
    }
}
