package com.postsaimanager.core.domain.agent

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.common.util.UuidGenerator
import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import kotlinx.coroutines.CancellationException

/** What one agent is: its conversation, its tools and what it tells the model about itself. */
interface AgentSpec {
    /** The engine session the agent talks in (one per agent conversation, apart from the plain chat's). */
    val conversationId: String

    val tools: ToolRegistry

    /** The standing instructions (the tool descriptions are added by the loop). */
    suspend fun systemPrompt(): String

    /** A compact summary of where things stand, kept by the tools' own state: added to every result and to a rebuilt history. */
    suspend fun stateSummary(): String?

    /** A nudge the model gets after two failed steps in a row (for example the next open item). Null when there is none. */
    suspend fun stuckHint(): String?

    /**
     * What the user's [text] means as the result of the turn-ending [call] it answers (for a question: the answer and what it matched), so the
     * model sees that its call was answered instead of just reading a new user message. Null keeps the plain user message.
     */
    suspend fun replyResult(call: AgentEntry.Call, text: String): ToolResult? = null

    /** A remark for the debug log about [call] (never a value of the user's); null for none. */
    fun traceNote(call: AgentEntry.Call): String? = null
}

/** How a run of the loop ended. */
sealed interface AgentOutcome {
    /** Nothing stored to continue from. */
    data object Idle : AgentOutcome

    /** The conversation already waits for the user (its last step handed the turn back). */
    data class Waiting(val call: AgentEntry.Call) : AgentOutcome

    /** A turn-ending tool ran: the reply comes back as the next user message. */
    data class EndedTurn(val call: AgentEntry.Call, val result: ToolResult) : AgentOutcome

    /** The step limit was reached without handing back to the user. */
    data object StepLimit : AgentOutcome

    /** The model failed or kept answering with something that is not a call. */
    data class Failed(val reason: String, val error: PamError? = null) : AgentOutcome
}

/**
 * The agent loop, as modern assistants do function calling, but on the device: the model reads the conversation and picks
 * ONE tool call per step (a grammar built from the tools' schemas makes every reply a valid call); the loop validates the
 * arguments, runs the tool, stores the call and its result and goes on, until a turn-ending tool hands the conversation back
 * to the user. The AI decides every step; code only provides the tools, checks their inputs and renders their results.
 *
 * Guards for a small model: a step limit per user turn; a call identical to one already made this turn is answered with
 * "already done" instead of running again; after two failed steps in a row the result carries the spec's hint; a reply that is
 * not a call is discarded and asked for again; and the standing session is rebuilt from a compact history (whole recent turns
 * plus the spec's state summary) when it has grown too large.
 */
class AgentLoop(
    private val model: AgentModel,
    private val format: ToolCallFormat,
    private val profile: AgentProfile = AgentProfile(),
    private val newId: () -> String = UuidGenerator::generate,
    private val trace: AgentTrace = AgentTrace.NONE,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun run(spec: AgentSpec, transcript: AgentTranscript): AgentOutcome {
        val entries = transcript.entries().toMutableList()
        val last = entries.lastOrNull() ?: return AgentOutcome.Idle
        if (last is AgentEntry.Call && spec.tools[last.name]?.endsTurn == true) return AgentOutcome.Waiting(last)

        val tools = spec.tools.specs()
        val grammar = format.grammar(tools)
        val request = profile.request(grammar)
        val system = spec.systemPrompt() + "\n\n" + format.describeTools(tools)
        // What the conversation may grow to beyond the system prompt, and how much of it a rebuilt session starts with.
        val room = profile.conversationRoom(system.length)
        val historyBudget = minOf(profile.historyChars, room / 2)
        var steps = 0
        var unreadable = 0
        var failedInARow = 0
        var correction: String? = null
        var sessionChars = 0
        // The user's replies to turn-ending calls, as the results of those calls (the model sees that its call was answered).
        val answers = answersOf(spec, entries)
        val turn = entries.count { it is AgentEntry.UserText && !it.isStart }.coerceAtLeast(1)

        while (true) {
            if (steps >= profile.maxStepsPerTurn) return AgentOutcome.StepLimit
            steps++

            // The newest entry is what the model answers; everything before it is the history a rebuilt session starts from.
            val pending = correction ?: renderPending(spec, entries, answers)
            val history = AgentHistory.build(if (correction != null) entries else entries.dropLast(1), format, tools, historyBudget, answers) {
                spec.stateSummary()
            }
            if (sessionChars > room) {
                model.resetSession()
                sessionChars = 0
            }
            val rebuilt = model.openSession(spec.conversationId, system, history)
            if (rebuilt) sessionChars = history.sumOf { it.content.length }

            val modelStart = now()
            val reply = when (val stepped = model.step(pending, request)) {
                is PamResult.Error -> return AgentOutcome.Failed("the model failed", stepped.error)
                is PamResult.Success -> stepped.data
            }
            val modelMs = now() - modelStart
            sessionChars += pending.length + reply.length
            val contextTokens = (system.length + sessionChars) / profile.charsPerToken

            val parsed = format.parse(reply, tools)
            if (parsed is ParsedCall.Invalid) {
                model.discard()
                trace.step(AgentStepTrace(turn, steps, "-", emptyList(), "unreadable", "unreadable", modelMs, 0, contextTokens, rebuilt))
                if (++unreadable > profile.maxInvalidRetries) return AgentOutcome.Failed("the model's reply was not a tool call: ${parsed.reason}")
                correction = "That was not a valid tool call (${parsed.reason}). Reply with exactly one tool call."
                continue
            }
            parsed as ParsedCall.Call
            model.commit(reply)
            correction = null
            unreadable = 0

            val call = AgentEntry.Call(newId(), parsed.name, parsed.args)
            val tool = spec.tools[parsed.name]
            val invalid = tool?.let { ArgumentValidator.validate(it.parameters, call.args) }
            val toolStart = now()
            var result = execute(tool, call, entries, invalid)
            val toolMs = now() - toolStart
            if (result.ok) failedInARow = 0 else failedInARow++
            if (!result.ok && failedInARow >= 2) spec.stuckHint()?.let { result = result.with("hint", it) }
            val ends = tool?.endsTurn == true && result.ok
            trace.step(
                AgentStepTrace(
                    turn, steps, parsed.name, parsed.args.keys.toList(),
                    validation = when {
                        tool == null -> "unknown_tool"
                        invalid != null -> "invalid"
                        else -> "ok"
                    },
                    outcome = if (ends) "ended_turn" else if (result.ok) "ok" else "error",
                    modelMs = modelMs, toolMs = toolMs, contextTokens = contextTokens, rebuilt = rebuilt, note = spec.traceNote(call),
                ),
            )
            if (ends) {
                transcript.record(call, null)
                return AgentOutcome.EndedTurn(call, result)
            }
            spec.stateSummary()?.let { result = result.with("state", it) }
            val recorded = AgentEntry.Result(call.id, call.name, result)
            transcript.record(call, recorded)
            entries += call
            entries += recorded
        }
    }

    /** The result each user reply stands for, by the entry's index: only a reply that comes right after a turn-ending call has one. */
    private suspend fun answersOf(spec: AgentSpec, entries: List<AgentEntry>): Map<Int, ToolResult> {
        val answers = HashMap<Int, ToolResult>()
        entries.forEachIndexed { index, entry ->
            val call = entries.getOrNull(index - 1) as? AgentEntry.Call ?: return@forEachIndexed
            if (entry !is AgentEntry.UserText || entry.isStart || spec.tools[call.name]?.endsTurn != true) return@forEachIndexed
            spec.replyResult(call, entry.text)?.let { answers[index] = it }
        }
        return answers
    }

    /** Validates and runs one call; whatever goes wrong becomes an error result the model can read. */
    private suspend fun execute(tool: AgentTool?, call: AgentEntry.Call, entries: List<AgentEntry>, invalid: String?): ToolResult {
        if (tool == null) return ToolResult.error("unknown function \"${call.name}\"")
        invalid?.let { return ToolResult.error("invalid arguments: $it") }
        val context = AgentContext.of(entries)
        if (context.turnCalls.any { it.name == call.name && it.args == call.args && succeeded(entries, it) }) {
            return ToolResult.error("already done: ${call.name} ${call.args}. Do the next step instead.")
        }
        return try {
            tool.execute(call.args, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("${call.name} failed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun succeeded(entries: List<AgentEntry>, call: AgentEntry.Call): Boolean =
        entries.filterIsInstance<AgentEntry.Result>().any { it.callId == call.id && it.result.ok }

    private suspend fun renderPending(spec: AgentSpec, entries: List<AgentEntry>, answers: Map<Int, ToolResult>): String = when (val entry = entries.last()) {
        is AgentEntry.UserText -> {
            val answer = answers[entries.lastIndex]
            val call = entries.getOrNull(entries.lastIndex - 1) as? AgentEntry.Call
            if (answer == null || call == null) {
                entry.text
            } else {
                // The newest answer carries the running state, like every result the model reads.
                val withState = if (answer.text("state") != null) answer else spec.stateSummary()?.let { answer.with("state", it) } ?: answer
                format.renderResult(call.name, withState.toModelText())
            }
        }
        is AgentEntry.Result -> format.renderResult(entry.name, entry.result.toModelText())
        // A call whose result was never stored (the run was interrupted while it ran): the model is told so and calls again.
        is AgentEntry.Call -> format.renderResult(entry.name, ToolResult.error("interrupted before it finished; call it again if it is still needed").toModelText())
    }
}

/** The compact history a (re)built session starts from: whole recent turns within a size budget, the older ones summarised. */
internal object AgentHistory {

    suspend fun build(
        entries: List<AgentEntry>,
        format: ToolCallFormat,
        tools: List<ToolSpec>,
        budgetChars: Int,
        answers: Map<Int, ToolResult> = emptyMap(),
        summary: suspend () -> String?,
    ): List<AiChatMessage> {
        val messages = entries.mapIndexed { index, entry -> message(entry, format, tools, answers[index], entries.getOrNull(index - 1)) }
        val boundaries = entries.indices.filter { entries[it] is AgentEntry.UserText }
        var first = 0
        while (first < boundaries.size - 1 && messages.drop(boundaries[first]).sumOf { it.content.length } > budgetChars) first++
        val from = boundaries.getOrNull(first) ?: 0
        // The call a kept reply answered may have been cut off: then the reply is just what the user said.
        val cutAnswer = from > 0 && answers.containsKey(from)
        val kept = messages.drop(from).let { list ->
            if (cutAnswer) listOf(AiChatMessage(AiChatRole.USER, (entries[from] as AgentEntry.UserText).text)) + list.drop(1) else list
        }
        val state = if (from > 0) summary() else null
        return if (state == null) kept else listOf(AiChatMessage(AiChatRole.USER, "Earlier steps are summarised here.\n$state")) + kept
    }

    private fun message(entry: AgentEntry, format: ToolCallFormat, tools: List<ToolSpec>, answer: ToolResult?, before: AgentEntry?): AiChatMessage = when (entry) {
        is AgentEntry.UserText ->
            if (answer != null && before is AgentEntry.Call) AiChatMessage(AiChatRole.USER, format.renderResult(before.name, answer.without("state").toModelText()))
            else AiChatMessage(AiChatRole.USER, entry.text)
        is AgentEntry.Call -> AiChatMessage(AiChatRole.ASSISTANT, format.renderCall(entry.name, entry.args, tools))
        is AgentEntry.Result -> AiChatMessage(AiChatRole.USER, format.renderResult(entry.name, entry.result.toModelText()))
    }
}
