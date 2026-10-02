package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.EmbeddingService
import com.postsaimanager.core.domain.ai.VectorMath

/**
 * The vocabulary of "asking for help with a form" as data: English content descriptions in the embedding model's own multilingual
 * space (a cheap first gate) and the instruction of the one yes/no score. They are never shown to users and never matched against
 * words, so a message in any language is read through the same descriptions.
 */
object FormIntents {

    val fillRequestExamples: List<String> = listOf(
        "help me fill in this form",
        "fill out this application for me",
        "complete this form",
        "help me with the paperwork of this form",
    )

    const val FILL_REQUEST_SYSTEM = "You read one message that a person wrote to an assistant about a document. " +
        "The message can be in any language. For each question, answer Yes if it is true of the message, otherwise No. " +
        "Answer with the single word Yes or No."
}

/**
 * Decides whether a message in a document chat asks for help filling in the form, which starts the form agent: one yes/no score,
 * "Does the user ask for help filling in this form?". On a document that is not a form the embedding model is asked first
 * (milliseconds) so an ordinary question never costs a model call; without a ready embedder the model is asked directly. Once the
 * agent runs, nothing reads a message for an intent any more: the agent is the one that understands the user.
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
