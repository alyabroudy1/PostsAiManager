package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.model.FormField

/** A person a free message may name, with a plain English description for the model ("the user's child Ahmad"). */
data class PersonChoice(val id: String, val name: String, val description: String)

/**
 * Maps a free message to one of a fixed set of candidates: an option printed on the form, yes or no, a person, a field. The model
 * only SCORES the candidates ("Does the user's answer mean «option»?"); code takes the best one that clears the threshold and the
 * margin, so what comes back is always one of the candidates handed in, never text the model made up.
 */
class AnswerInterpreter(
    private val model: FormModel,
    private val profile: FormFillProfile = FormFillProfile(),
) {

    /** The index of the option [answer] means, or null when none clearly does. */
    suspend fun pickOption(question: String, answer: String, options: List<String>): Int? =
        pick(question, answer, options.map { "means «$it»" })

    /** True for yes, false for no, null when the answer is neither. */
    suspend fun yesNo(question: String, answer: String): Boolean? =
        when (pick(question, answer, listOf("means yes", "means no"))) {
            0 -> true
            1 -> false
            else -> null
        }

    /** The id of the person [text] means, or null. */
    suspend fun pickPerson(question: String, text: String, people: List<PersonChoice>): String? =
        pick(question, text, people.map { "names ${it.description}" })?.let { people[it].id }

    /** The id of the field [text] asks to change, or null. At most [FormFillProfile.maxFieldScores] fields are scored. */
    suspend fun pickField(text: String, fields: List<FormField>): String? {
        val candidates = fields.take(profile.maxFieldScores)
        return pick("Which part of the form does the user want to change?", text, candidates.map { "is about the form field «${it.labelText}»" })
            ?.let { candidates[it].id }
    }

    private suspend fun pick(question: String, answer: String, statements: List<String>): Int? {
        if (statements.isEmpty()) return null
        val context = "ASSISTANT'S QUESTION: $question\nUSER'S ANSWER: $answer"
        val scores = (model.score(SYSTEM, context, statements.map { "Does the user's answer ${it}? Answer:" }) as? PamResult.Success)?.data
            ?: return null
        val order = scores.indices.sortedByDescending { scores[it] }
        val best = order.firstOrNull() ?: return null
        if (scores[best] <= profile.choiceThreshold) return null
        val runnerUp = order.getOrNull(1)?.let { scores[it] }
        return if (runnerUp == null || scores[best] - runnerUp >= profile.choiceMargin) best else null
    }

    private companion object {
        const val SYSTEM = "You read one answer that a person gave to an assistant's question while filling in a form. " +
            "The answer can be in any language. For each question, answer Yes if it is true of the answer, otherwise No. " +
            "Answer with the single word Yes or No."
    }
}
