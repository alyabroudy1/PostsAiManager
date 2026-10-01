package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.ai.VectorMath

/** What a message of the user means in the middle of filling a form. */
enum class FormIntent { ANSWER, CHANGE_SUBJECT, CHANGE_VALUE, SKIP, ASK_ABOUT_FORM, STOP }

/**
 * The vocabulary of the intents as data: English content descriptions the model scores a message against ("Does the user's
 * message <description>?"). They are never shown to users and never matched against words, so a message in any language is
 * read through the same descriptions.
 */
object FormIntents {

    val descriptions: Map<FormIntent, String> = linkedMapOf(
        FormIntent.ANSWER to "gives the answer to the assistant's question",
        FormIntent.CHANGE_SUBJECT to "asks to fill in the form for a different person",
        FormIntent.CHANGE_VALUE to "asks to change or correct something that was already filled in",
        FormIntent.SKIP to "says they do not know the answer or want to skip this question",
        FormIntent.ASK_ABOUT_FORM to "asks a question about the form, its wording or what it means",
        FormIntent.STOP to "wants to stop filling in the form",
    )

    /** What it means to ask for help with a form, in the embedding model's own multilingual space (a cheap first gate). */
    val fillRequestExamples: List<String> = listOf(
        "help me fill in this form",
        "fill out this application for me",
        "complete this form",
        "help me with the paperwork of this form",
    )

    const val SYSTEM = "You read one message that a person wrote while filling in a form together with an assistant. " +
        "The message can be in any language. For each question, answer Yes if it is true of the message, otherwise No. " +
        "Answer with the single word Yes or No."

    const val FILL_REQUEST_SYSTEM = "You read one message that a person wrote to an assistant about a document. " +
        "The message can be in any language. For each question, answer Yes if it is true of the message, otherwise No. " +
        "Answer with the single word Yes or No."
}

/**
 * Reads a message as one [FormIntent] by scoring each intent's description (the model decides; code takes the best).
 * Nothing above the threshold means a plain answer.
 */
class FormIntentClassifier(
    private val model: FormModel,
    private val profile: FormFillProfile = FormFillProfile(),
) {

    /** [question] is what the assistant last asked, in plain words (any language). */
    suspend fun classify(question: String, message: String): FormIntent {
        val intents = FormIntents.descriptions.keys.toList()
        val context = "ASSISTANT'S QUESTION: $question\nUSER'S MESSAGE: $message"
        val statements = intents.map { "Does the user's message ${FormIntents.descriptions.getValue(it)}? Answer:" }
        val scores = (model.score(FormIntents.SYSTEM, context, statements) as? PamResult.Success)?.data
            ?: return FormIntent.ANSWER
        // A plain answer is the likeliest thing typed to a question: another intent needs an explicit meaning, clearly above the
        // score of "gives the answer" (a name like "Mia" was once read as "skip").
        val answer = intents.indexOf(FormIntent.ANSWER)
        val answerScore = scores.getOrNull(answer) ?: Double.NEGATIVE_INFINITY
        val best = scores.indices.filter { it != answer }.maxByOrNull { scores[it] } ?: return FormIntent.ANSWER
        val clear = scores[best] > profile.intentThreshold && scores[best] > answerScore + profile.answerPrior
        return if (clear) intents[best] else FormIntent.ANSWER
    }
}

/**
 * Decides whether a message in a document chat asks for help filling in the form: one yes/no score, "Does the user ask for
 * help filling in this form?". On a document that is not a form the embedding model is asked first (milliseconds) so an
 * ordinary question never costs a model call; without a ready embedder the model is asked directly.
 */
class FillRequestDetector(
    private val model: FormModel,
    private val embedder: EmbeddingService,
    private val profile: FormFillProfile = FormFillProfile(),
) {

    private var exampleVectors: List<FloatArray>? = null

    suspend fun asksForFill(message: String, documentIsForm: Boolean): Boolean {
        if (message.isBlank()) return false
        if (!documentIsForm && !closeToFillRequest(message)) return false
        val statements = listOf("Does the user ask for help filling in this form? Answer:")
        val scores = (model.score(FormIntents.FILL_REQUEST_SYSTEM, "USER'S MESSAGE: $message", statements) as? PamResult.Success)?.data
        return scores?.firstOrNull()?.let { it > profile.fillRequestThreshold } ?: false
    }

    private suspend fun closeToFillRequest(message: String): Boolean {
        if (!embedder.checkReady()) return true
        val examples = exampleVectors ?: (embedder.embedAll(FormIntents.fillRequestExamples) as? PamResult.Success)?.data
            ?.also { exampleVectors = it } ?: return true
        val vector = (embedder.embed(message) as? PamResult.Success)?.data ?: return true
        return examples.maxOf { VectorMath.cosineSimilarity(vector, it) } >= profile.fillRequestEmbeddingGate
    }
}
