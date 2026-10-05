package com.postsaimanager.core.domain.form

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.PromptSession

/** The engine could not score a batch (no model, or a question did not fit). */
class FormScoringException(val error: PamError) : RuntimeException(error.userMessage)

/**
 * Label-free yes/no scoring of questions about a form, over an open [PromptSession]: each question is decoded after the
 * session's prefix and answered as `logit(Yes) - logit(No)`. The one place the form steps talk to the engine, and the
 * one counter of the scores spent ([scoreCount]).
 *
 * @param tail what closes the user turn and opens the assistant's, from [PromptFraming].
 */
class FormScorer(private val session: PromptSession, private val tail: String = "") {

    /** How many scores were asked since this scorer was made. */
    var scoreCount: Int = 0
        private set

    /** One score per question, in order. Throws [FormScoringException] when the engine fails the batch. */
    suspend fun yesNo(questions: List<String>, shared: String = ""): List<Double> {
        if (questions.isEmpty()) return emptyList()
        val out = ArrayList<Double>(questions.size)
        for (chunk in questions.chunked(CHUNK)) {
            when (val r = session.score(chunk.map { "\n\n$it$tail" }, YES, NO, shared)) {
                is PamResult.Error -> throw FormScoringException(r.error)
                is PamResult.Success -> {
                    if (r.data.size != chunk.size) throw FormScoringException(PamError.InferenceError("the engine scored ${r.data.size} of ${chunk.size} questions"))
                    out += r.data
                }
            }
            scoreCount += chunk.size
        }
        return out
    }

    companion object {
        const val YES = "Yes"
        const val NO = "No"
        private const val CHUNK = 48
    }
}
