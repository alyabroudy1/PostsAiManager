package com.postsaimanager.core.ai.litert

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole
import com.postsaimanager.core.model.ToolExchange

/**
 * One turn of a conversation as LiteRT-LM replays it: who spoke and what, with no template markup.
 *
 * @param tools for a model turn: the tool calls it made before [text], with their results, oldest first. A replay that dropped
 *   them would show the model a turn that says "I prepared the reminder" with no call in front of it, and the model copies what
 *   its history shows.
 */
internal data class LiteRtTurn(val fromUser: Boolean, val text: String, val tools: List<ToolExchange> = emptyList()) {

    /** Characters this turn adds to a prompt (text and tool calls), for the budget. */
    val promptChars: Int get() = text.length + tools.sumOf { it.promptChars }
}

/**
 * Turns the app's persisted history into the alternating user/model turns a LiteRT-LM conversation starts from.
 *
 * The model file's chat template applies the format (the turn markers), so this only shapes the sequence. A Gemma-family
 * template refuses a conversation that does not alternate, and the app's history can break that in two ways: a question the
 * model never answered (stopped, failed or cut off: the reply is not kept, the question is) leaves two user turns in a row, and
 * a history that starts after a deleted turn can open with the model. So consecutive turns of one speaker are joined into one,
 * and turns before the first user turn are dropped. A system-role turn is not part of the replay: the system instruction is
 * given to the conversation separately. A trailing user turn is dropped as well: the message about to be sent is the user's
 * next turn, and an unanswered question before it would put two user turns in a row again.
 *
 * A model turn keeps the tool calls it made ([AiChatMessage.toolTrace]); the conversation replays them as tool-call turns.
 */
internal object LiteRtTurns {

    private const val JOINER = "\n\n"

    /**
     * After a rebuild the conversation may hold at most this share of the window, so there is room for the system instruction, the
     * tools, a skill's text and the reply. The Gallery compacts when the conversation passes 75% of its window
     * (`SummarizationContextCompactor`), and then restarts from a short summary; here it restarts from the newest turns that fit.
     */
    const val REBUILD_WINDOW_SHARE = 0.4

    /** The same estimate the app's history budget uses (`BuildChatContextUseCase.CHARS_PER_TOKEN`). */
    const val CHARS_PER_TOKEN = 3

    fun from(history: List<AiChatMessage>): List<LiteRtTurn> {
        val turns = mutableListOf<LiteRtTurn>()
        for (message in history) {
            if (message.role == AiChatRole.SYSTEM || message.content.isBlank()) continue
            val fromUser = message.role == AiChatRole.USER
            val tools = if (fromUser) emptyList() else message.toolTrace
            val last = turns.lastOrNull()
            when {
                last == null && !fromUser -> Unit
                last != null && last.fromUser == fromUser ->
                    turns[turns.lastIndex] = last.copy(text = last.text + JOINER + message.content, tools = last.tools + tools)
                else -> turns += LiteRtTurn(fromUser, message.content, tools)
            }
        }
        if (turns.lastOrNull()?.fromUser == true) turns.removeAt(turns.lastIndex)
        return turns
    }

    /**
     * The newest whole turns of [turns] that fit [budgetChars], for a conversation that grew past its window. Oldest turns go
     * first and a model turn never stays without the user turn that asked it (the template needs the alternation), so what is
     * kept starts with a user turn. At least the newest exchange stays, however large: dropping it would leave nothing to continue.
     */
    fun compact(turns: List<LiteRtTurn>, budgetChars: Int): List<LiteRtTurn> {
        val kept = ArrayDeque(turns)
        var total = kept.sumOf { it.promptChars }
        while (kept.size > 2 && total > budgetChars) total -= kept.removeFirst().promptChars
        while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeFirst()
        return kept.toList()
    }

    /**
     * The last exchange of [turns]: the newest model turn and the user turn before it, nothing older. What a conversation that is
     * built again (a new day, a restart of the engine after a GPU failure) continues from, the same rule the app applies to a
     * conversation it builds from the stored transcript (`ContinuityTail`): the model reads the document, not the old chat.
     */
    fun lastExchange(turns: List<LiteRtTurn>): List<LiteRtTurn> {
        val reply = turns.indexOfLast { !it.fromUser }
        if (reply < 1) return emptyList()
        val question = turns.subList(0, reply).indexOfLast { it.fromUser }
        if (question < 0) return emptyList()
        return listOf(turns[question], turns[reply])
    }

    /** The budget [compact] works to for a window of [windowTokens]. */
    fun rebuildBudgetChars(windowTokens: Int): Int = (windowTokens * REBUILD_WINDOW_SHARE).toInt() * CHARS_PER_TOKEN
}
