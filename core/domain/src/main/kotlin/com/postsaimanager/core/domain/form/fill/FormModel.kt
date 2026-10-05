package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import com.postsaimanager.core.domain.form.FormScorer
import com.postsaimanager.core.domain.form.FormScoringException
import com.postsaimanager.core.domain.form.PromptFraming
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the form feature asks of the on-device model outside the agent: label-free yes/no SCORES (the model decides what a message
 * means). The agent itself talks through [com.postsaimanager.core.domain.agent.AgentModel]. A port so the request detector is
 * testable without a model; [EngineFormModel] binds it to the loaded chat model.
 */
interface FormModel {

    /**
     * For each of [statements], the log-odds that it is true of [context] (read by a model told [system]); higher is more
     * likely. The model reads [context] once, whatever the number of statements.
     */
    suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>>
}

/** [FormModel] over the standing engine: the same loaded model and the same [PromptSession] the form understanding uses. */
class EngineFormModel @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
) : FormModel {

    override suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>> {
        if (statements.isEmpty()) return PamResult.Success(emptyList())
        val (head, tail) = framing.frame(system, context)
        when (val opened = session.open(head)) {
            is PamResult.Error -> return opened
            is PamResult.Success -> Unit
        }
        return try {
            PamResult.Success(FormScorer(session, tail).yesNo(statements))
        } catch (e: FormScoringException) {
            PamResult.Error(e.error)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
}
