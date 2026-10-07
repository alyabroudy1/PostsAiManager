package com.postsaimanager.core.ai.litert

import com.postsaimanager.core.domain.ai.AiChatMessage
import com.postsaimanager.core.domain.ai.AiChatRole

/** One turn of a conversation as LiteRT-LM replays it: who spoke and what, with no template markup. */
internal data class LiteRtTurn(val fromUser: Boolean, val text: String)

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
 */
internal object LiteRtTurns {

    private const val JOINER = "\n\n"

    fun from(history: List<AiChatMessage>): List<LiteRtTurn> {
        val turns = mutableListOf<LiteRtTurn>()
        for (message in history) {
            if (message.role == AiChatRole.SYSTEM || message.content.isBlank()) continue
            val fromUser = message.role == AiChatRole.USER
            val last = turns.lastOrNull()
            when {
                last == null && !fromUser -> Unit
                last != null && last.fromUser == fromUser ->
                    turns[turns.lastIndex] = last.copy(text = last.text + JOINER + message.content)
                else -> turns += LiteRtTurn(fromUser, message.content)
            }
        }
        if (turns.lastOrNull()?.fromUser == true) turns.removeAt(turns.lastIndex)
        return turns
    }
}
