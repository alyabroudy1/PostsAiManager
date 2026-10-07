package com.postsaimanager.core.domain.form

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The log-odds of Yes against No per candidate (in the listed order) and for the made-up distractor asked beside them. */
data class BaselineScores(val candidates: List<Double>, val baseline: Double) {

    /** The indexes of [candidates] that beat [baseline] by at least [margin]. */
    fun beating(margin: Double): List<Int> = candidates.indices.filter { candidates[it] - baseline >= margin }
}

/**
 * The shared "scored yes/no per candidate against a made-up distractor" step (person chips, same-contact): the prefix is decoded
 * once in a [PromptSession], each statement and the distractor's statement is scored separately by `logit(Yes) - logit(No)`
 * ([FormScorer]), each rolled back to the prefix, and the session is always closed. The caller keeps what its question means and
 * which margin it uses; a small model leans Yes on everything, so only the distance to the distractor says anything.
 */
class BaselineYesNo @Inject constructor(
    private val session: PromptSession,
    private val framing: PromptFraming,
) {

    /**
     * @param system the system prompt.
     * @param user what is decoded once after it.
     * @param statements one question per candidate.
     * @param baselineStatement the same question about the made-up distractor.
     * @param shared context decoded once after the prefix.
     */
    suspend fun score(
        system: String,
        user: String,
        statements: List<String>,
        baselineStatement: String,
        shared: String,
    ): PamResult<BaselineScores> {
        val (head, tail) = framing.frame(system, user)
        when (val opened = session.open(head)) {
            is PamResult.Error -> return opened
            is PamResult.Success -> Unit
        }
        return try {
            val all = FormScorer(session, tail).yesNo(statements + baselineStatement, shared)
            PamResult.Success(BaselineScores(all.dropLast(1), all.last()))
        } catch (e: FormScoringException) {
            PamResult.Error(e.error)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }
}
