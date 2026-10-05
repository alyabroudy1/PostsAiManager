package com.postsaimanager.core.domain.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON convention of Hermes 2/3, Qwen2.5 and Qwen3: the tools are JSON objects inside `<tools>`, a call is
 * `<tool_call>{"name": …, "arguments": {…}}</tool_call>` and a result is a user turn wrapped in `<tool_response>`. The grammar
 * writes every argument in the schema's order (required ones first), so a reply is always parseable.
 */
class HermesToolCallFormat : ToolCallFormat {

    override fun describeTools(tools: List<ToolSpec>): String = buildString {
        append("# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
        append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>")
        tools.forEach { append("\n").append(toolJson(it)) }
        append("\n</tools>\n\nFor each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n")
        append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
    }

    override fun grammar(tools: List<ToolSpec>): String {
        val rules = Gbnf.Rules()
        rules.add("root", "\"<tool_call>\\n\" call \"\\n</tool_call>\"")
        rules.add("call", tools.joinToString(" | ") { Gbnf.ruleName("call", it.name) })
        tools.forEach { tool ->
            val args = Gbnf.argsOf(tool.parameters)
            val argsRule = Gbnf.ruleName("args", tool.name)
            rules.add(argsRule, argumentsBody(args))
            rules.add(Gbnf.ruleName("call", tool.name), Gbnf.literal("{\"name\": \"${tool.name}\", \"arguments\": ") + " $argsRule \"}\"")
        }
        Gbnf.jsonValueRules(rules)
        return rules.render()
    }

    /** The arguments object: the required ones in order, then each optional one that may follow. */
    private fun argumentsBody(args: List<Gbnf.Arg>): String {
        fun pair(arg: Gbnf.Arg) = Gbnf.literal("\"${arg.name}\": ") + " " + Gbnf.jsonValue(arg)
        val required = args.filter { it.required }
        val optional = args.filterNot { it.required }
        val optionalParts = optional.joinToString(" ") { "( ${Gbnf.literal(", ")} ${pair(it)} )?" }
        return when {
            required.isEmpty() && optional.isEmpty() -> "\"{\" \"}\""
            required.isEmpty() ->
                "\"{\" ( ${pair(optional.first())} ${optional.drop(1).joinToString(" ") { "( ${Gbnf.literal(", ")} ${pair(it)} )?" }} )? \"}\""
            else ->
                "\"{\" " + required.joinToString(" ${Gbnf.literal(", ")} ") { pair(it) } + (if (optionalParts.isEmpty()) "" else " $optionalParts") + " \"}\""
        }
    }

    override fun parse(reply: String, tools: List<ToolSpec>): ParsedCall {
        val open = reply.indexOf(CALL_OPEN)
        val text = (if (open >= 0) reply.substring(open + CALL_OPEN.length) else reply).substringBefore("</tool_call>").trim()
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return ParsedCall.Invalid("the reply is not a JSON object inside <tool_call>")
        val name = (element["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ParsedCall.Invalid("the call has no \"name\"")
        if (tools.none { it.name == name }) return ParsedCall.Invalid("unknown function \"$name\" (known: ${tools.joinToString { it.name }})")
        val arguments = when (val raw = element["arguments"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> raw
            // Some templates carry the arguments as a string of JSON.
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(raw.content) }.getOrNull() as? JsonObject
                ?: return ParsedCall.Invalid("\"arguments\" is not a JSON object")
            else -> return ParsedCall.Invalid("\"arguments\" is not a JSON object")
        }
        return ParsedCall.Call(name, arguments)
    }

    override fun renderCall(name: String, args: JsonObject, tools: List<ToolSpec>): String {
        val arguments = args.entries.joinToString(", ", prefix = "{", postfix = "}") { (key, value) -> "\"$key\": $value" }
        return "<tool_call>\n{\"name\": \"$name\", \"arguments\": $arguments}\n</tool_call>"
    }

    override fun renderResult(name: String, resultText: String): String = "<tool_response>\n$resultText\n</tool_response>"

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
    }
}
