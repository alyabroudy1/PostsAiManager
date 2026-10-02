package com.postsaimanager.core.domain.agent

import kotlinx.serialization.json.JsonObject

/** What the model's reply was read as. */
sealed interface ParsedCall {
    data class Call(val name: String, val args: JsonObject) : ParsedCall

    /** Not a call the format can read ([reason] is for the model, to say what to fix). */
    data class Invalid(val reason: String) : ParsedCall
}

/**
 * The port between the loop and a model family's tool-calling convention: how the tools are described to the model, how a call
 * and a result are written into a conversation, how a reply is read back and which grammar forces a reply to be a valid call.
 * The loop knows none of it, so another model family is one more implementation.
 */
interface ToolCallFormat {

    /** The text that tells the model which functions it has and how to call them (goes into the system prompt). */
    fun describeTools(tools: List<ToolSpec>): String

    /** A GBNF grammar whose every sentence is exactly one call of one of [tools], in this format. */
    fun grammar(tools: List<ToolSpec>): String

    /** Reads the model's reply; [tools] give the argument types. */
    fun parse(reply: String, tools: List<ToolSpec>): ParsedCall

    /** A call as the assistant's turn in a conversation (what the grammar would have generated). */
    fun renderCall(name: String, args: JsonObject, tools: List<ToolSpec>): String

    /** A tool's result as the next turn the model reads. */
    fun renderResult(name: String, resultText: String): String
}

/** Which [ToolCallFormat] a model uses, as data of its profile. */
enum class ToolFormatId {
    /** Qwen3.5's native template: `<tool_call><function=name><parameter=arg>value</parameter></function></tool_call>`. */
    QWEN,

    /** The Hermes/Qwen2.5/Qwen3 JSON convention: `<tool_call>{"name": …, "arguments": {…}}</tool_call>`. */
    HERMES_JSON,
    ;

    fun create(): ToolCallFormat = when (this) {
        QWEN -> QwenToolCallFormat()
        HERMES_JSON -> HermesToolCallFormat()
    }
}
