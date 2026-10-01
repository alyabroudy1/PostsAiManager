package com.postsaimanager.core.domain.agent

import kotlinx.serialization.json.JsonObject

/**
 * One step of an agent conversation as the loop reads it back from storage (see [AgentTranscript]): what the user said, a
 * call the model made and what the tool returned. The model's own raw text is never stored; a call is stored as name and
 * arguments and written again by the [ToolCallFormat] when a session has to be rebuilt.
 */
sealed interface AgentEntry {

    /** [isStart] marks the instruction that opens a run when the user typed nothing: it is not something the user said. */
    data class UserText(val text: String, val isStart: Boolean = false) : AgentEntry

    data class Call(val id: String, val name: String, val args: JsonObject) : AgentEntry

    data class Result(val callId: String, val name: String, val result: ToolResult) : AgentEntry
}

/** Where the loop reads the conversation so far and records each step, so a stopped run resumes from what is stored. */
interface AgentTranscript {

    /** Everything of the current run, oldest first. */
    suspend fun entries(): List<AgentEntry>

    /** Stores [call] and, unless it handed the turn back to the user, its [result]. */
    suspend fun record(call: AgentEntry.Call, result: AgentEntry.Result?)
}

/**
 * What a tool may know about the conversation around its call. The framework fills it from the entries; a tool uses it to check
 * where a value came from ("the user's literal reply") and whether the user has had a say.
 */
class AgentContext(
    /** Every message the user wrote in this run, oldest first (not the opening instruction). */
    val userReplies: List<String>,
    /** The calls made so far in the current turn (since the user's last message), oldest first. */
    val turnCalls: List<AgentEntry.Call>,
    /** The call that handed the previous turn back to the user (the question the user's last message answers), if any. */
    val previousTurnEnd: AgentEntry.Call?,
    /** Whether the current turn began with a message the user wrote (not the opening instruction or a continue). */
    val turnStartedByUser: Boolean,
) {
    val hasUserReply: Boolean get() = userReplies.isNotEmpty()

    companion object {
        fun of(entries: List<AgentEntry>): AgentContext {
            val lastUser = entries.indexOfLast { it is AgentEntry.UserText }
            val user = entries.getOrNull(lastUser) as? AgentEntry.UserText
            return AgentContext(
                userReplies = entries.filterIsInstance<AgentEntry.UserText>().filterNot { it.isStart }.map { it.text },
                turnCalls = if (lastUser < 0) entries.filterIsInstance<AgentEntry.Call>() else entries.drop(lastUser + 1).filterIsInstance<AgentEntry.Call>(),
                previousTurnEnd = entries.getOrNull(lastUser - 1) as? AgentEntry.Call,
                turnStartedByUser = user != null && !user.isStart,
            )
        }
    }
}
