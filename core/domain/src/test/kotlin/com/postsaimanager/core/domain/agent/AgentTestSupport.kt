package com.postsaimanager.core.domain.agent

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A "model" that says what a test scripted: each step takes the next reply from [script] (a function of the message the loop sent,
 * so a test can react to a tool result). It records everything the loop asked of it, so a test can assert what the model saw.
 */
class ScriptedAgentModel : AgentModel {

    class Session(val conversationId: String, val system: String, val history: List<AiChatMessage>)

    val script = ArrayDeque<(String) -> String>()
    val sent = mutableListOf<String>()
    val requests = mutableListOf<AiRequest>()
    val sessions = mutableListOf<Session>()
    val committed = mutableListOf<String>()
    var discarded = 0
        private set
    var resets = 0
        private set
    var loaded: PamResult<Unit> = PamResult.Success(Unit)

    private var open: String? = null

    fun reply(text: String) = script.addLast { text }

    fun reply(block: (String) -> String) = script.addLast(block)

    override suspend fun ensureLoaded(): PamResult<Unit> = loaded

    override suspend fun openSession(conversationId: String, system: String, history: List<AiChatMessage>): Boolean {
        if (open == conversationId) return false
        open = conversationId
        sessions += Session(conversationId, system, history)
        return true
    }

    override suspend fun step(message: String, request: AiRequest): PamResult<String> {
        sent += message
        requests += request
        val next = script.removeFirstOrNull() ?: error("the script ran out at step ${sent.size}: $message")
        return PamResult.Success(next(message))
    }

    override suspend fun commit(reply: String) {
        committed += reply
    }

    override suspend fun discard() {
        discarded++
    }

    override suspend fun resetSession() {
        resets++
        open = null
    }
}

/** An [AgentTranscript] in memory. */
class MemoryTranscript(vararg initial: AgentEntry) : AgentTranscript {
    val stored = initial.toMutableList()

    override suspend fun entries(): List<AgentEntry> = stored.toList()

    override suspend fun record(call: AgentEntry.Call, result: AgentEntry.Result?) {
        stored += call
        if (result != null) stored += result
    }
}

/** A tool made of a lambda, for the loop's tests. */
class LambdaTool(
    override val name: String,
    override val parameters: JsonObject = ToolParams.schema(),
    override val endsTurn: Boolean = false,
    override val description: String = "test tool $name",
    private val body: suspend (JsonObject, AgentContext) -> ToolResult = { _, _ -> ToolResult.ok() },
) : AgentTool {
    var calls = 0
        private set

    override suspend fun execute(args: JsonObject, context: AgentContext): ToolResult {
        calls++
        return body(args, context)
    }
}

class TestSpec(
    override val tools: ToolRegistry,
    private val state: String? = null,
    private val hint: String? = null,
) : AgentSpec {
    override val conversationId = "agent-test"
    override suspend fun systemPrompt() = "You are a test agent."
    override suspend fun stateSummary() = state
    override suspend fun stuckHint() = hint
}

fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

fun str(value: String) = JsonPrimitive(value)
