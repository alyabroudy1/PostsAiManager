package com.postsaimanager.core.ai.litert

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import com.postsaimanager.core.ai.litert.tools.AgentToolCalls
import com.postsaimanager.core.model.ToolExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The replayed turns as LiteRT-LM's own messages (`ConversationConfig.initialMessages`).
 *
 * A model turn that made tool calls is replayed the way the library itself records them while it runs the tools: a model message
 * carrying the call, then a tool message carrying the response, once per call, and last the model's text. The chat template of the
 * model file renders them, so nothing here knows a turn marker.
 */
internal object LiteRtMessages {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param withTools false when the conversation being built has no tools declared: a call to a tool the model does not have
     *   cannot be replayed, so those turns are replayed as their text only.
     */
    fun of(turns: List<LiteRtTurn>, withTools: Boolean): List<Message> = buildList {
        for (turn in turns) {
            if (turn.fromUser) {
                add(Message.user(turn.text))
                continue
            }
            if (withTools) {
                for (exchange in turn.tools) {
                    add(Message.model(Contents.of(emptyList<Content>()), listOf(toolCall(exchange)), emptyMap()))
                    add(Message.tool(Contents.of(Content.ToolResponse(exchange.name, replayedResult(exchange)))))
                }
            }
            add(Message.model(turn.text))
        }
    }

    /**
     * What a replayed tool response says. A `load_skill` response carries the whole text of the skill, and every rebuild of the
     * conversation (the warm-up when a chat opens, a new day, compaction, a restart) would prefill one copy of it per earlier request.
     * Replayed, it is a one-line stub: the call and its response keep their shape, so the model still sees the pattern, and the skill
     * can be loaded again. What is stored with the message is untouched. `run_intent` and `run_js` results are short and stay as stored.
     */
    internal fun replayedResult(exchange: ToolExchange): Map<String, String> {
        val result = fields(exchange.resultJson)
        if (exchange.name != AgentToolCalls.LOAD_SKILL || INSTRUCTIONS !in result) return result
        val skill = result["skill_name"].orEmpty()
        return result + (INSTRUCTIONS to "(instructions of $skill were loaded earlier; call load_skill again to read them)")
    }

    private const val INSTRUCTIONS = "skill_instructions"

    private fun toolCall(exchange: ToolExchange) = ToolCall(exchange.name, fields(exchange.argumentsJson))

    /** A stored JSON object as the plain map LiteRT-LM takes for arguments and results (all values strings here). */
    private fun fields(objectJson: String): Map<String, String> {
        val parsed = runCatching { json.parseToJsonElement(objectJson) as? JsonObject }.getOrNull() ?: return emptyMap()
        return parsed.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull ?: value.toString() }
    }
}
