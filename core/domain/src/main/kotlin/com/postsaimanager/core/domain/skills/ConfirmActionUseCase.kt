package com.postsaimanager.core.domain.skills

import com.postsaimanager.core.domain.applock.ExternalFlowGuard
import com.postsaimanager.core.domain.timeline.RecordActionEventUseCase
import java.time.LocalDateTime
import javax.inject.Inject

/** What confirming a card gave. */
sealed interface ConfirmOutcome {
    /** The action was handed to the executor; [result] says whether it worked. */
    data class Executed(val result: ActionResult) : ConfirmOutcome

    /** The (edited) fields cannot make an action; nothing ran. The card shows each error at its field. */
    data class Invalid(val errors: Map<ActionField, InvalidReason>) : ConfirmOutcome
}

/**
 * Step two of acting, called only when the user pressed Open: reads the card's fields (as the user left them) back into an action
 * and runs it through [AgentActionExecutor]. The one way to the executor, so nothing fires without a confirmation.
 *
 * Opening another app is an app-initiated trip out of the activity, announced to the app lock like the share sheet or the scanner
 * ([ExternalFlowGuard]); it is ended again when nothing was opened.
 */
class ConfirmActionUseCase @Inject constructor(
    private val executor: AgentActionExecutor,
    private val externalFlows: ExternalFlowGuard,
    private val recordEvent: RecordActionEventUseCase,
) {
    /** [edited] holds the fields the user changed (all fields may be passed; one missing keeps the proposal's own text). */
    suspend operator fun invoke(proposed: ProposedAction, edited: Map<ActionField, String>, now: LocalDateTime): ConfirmOutcome {
        val action = when (val built = ActionForm.build(proposed.action, edited, now)) {
            is FormResult.Invalid -> return ConfirmOutcome.Invalid(built.errors)
            is FormResult.Built -> built.action
        }
        val opensAnotherApp = AgentIntentSpecs.of(action) != null
        val token = if (opensAnotherApp) externalFlows.expect("agent-action") else null
        val result = executor.execute(action)
        if (result is ActionResult.Failed) externalFlows.finish(token)
        // What was done is a fact on the letter's timeline (written by code); a failure to write it never undoes or hides the action.
        if (result is ActionResult.Succeeded && proposed.documentId != null) {
            try {
                recordEvent(proposed.documentId, action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The timeline is a record, not part of the action.
            }
        }
        return ConfirmOutcome.Executed(result)
    }
}
