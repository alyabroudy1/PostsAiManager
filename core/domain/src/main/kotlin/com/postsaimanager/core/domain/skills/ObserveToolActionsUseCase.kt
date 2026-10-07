package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.domain.ai.ChatEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import javax.inject.Inject

/** An action the model proposed through `run_intent`, rebuilt in the main process, and the letter it was grounded on. */
data class ToolProposal(val action: AgentAction, val documentId: String?)

/**
 * The tool calls of the chat model, as proposals for cards: the engine delivers each `run_intent` call as the intent name and the
 * model's parameters JSON ([ChatEngine.toolActions]); this rebuilds the [AgentAction] with the pure [AgentActionParser], the same
 * parse the tool made in the `:inference` process. Nothing runs here: the proposal goes to `ProposeActionUseCase` (the grounding
 * check) and then to a card, and only the user's Open reaches `ConfirmActionUseCase`.
 *
 * A call that does not parse here is dropped (the tool already told the model why in its own process); an action that needs no
 * card (the clock) is dropped too.
 */
class ObserveToolActionsUseCase @Inject constructor(
    private val engine: ChatEngine,
) {
    operator fun invoke(): Flow<ToolProposal> = engine.toolActions.mapNotNull { call ->
        when (val parsed = call.parse()) {
            is ActionParse.Parsed -> parsed.action.takeIf { it.requiresConfirmation }?.let { ToolProposal(it, call.documentId) }
            is ActionParse.Rejected -> null
        }
    }
}
