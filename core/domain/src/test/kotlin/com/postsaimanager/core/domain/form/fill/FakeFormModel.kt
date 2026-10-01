package com.postsaimanager.core.domain.form.fill

import com.postsaimanager.core.common.result.PamError
import com.postsaimanager.core.common.result.PamResult

/**
 * A [FormModel] that "understands" what a test tells it: which intent a message has, which candidate a free answer means, whether
 * a message asks for a fill. Scores are +4 (Yes) or -3 (No), recognised by the text of the statement the code sent. It also records
 * everything it was asked, so a test can assert what the model saw and never wrote into a value.
 */
class FakeFormModel : FormModel {

    var load: PamResult<Unit> = PamResult.Success(Unit)

    /** A message to the intent it has; any other message is a plain answer. */
    val intents = mutableMapOf<String, FormIntent>()

    /** A free answer to the text its true statement contains (`means «Mittwoch 15:00 Uhr»`, `means yes`, `names the user's child Ahmad`). */
    val meanings = mutableMapOf<String, String>()

    /** Messages that ask for help filling in the form. */
    val fillRequests = mutableSetOf<String>()

    /** The question the model writes for a prompt; null makes it fail, so the conversation uses its template. */
    var question: (user: String) -> String? = { null }

    /** Every statement batch scored, as (system, context). */
    val scored = mutableListOf<Pair<String, String>>()

    /** Every prompt the model was asked to write for. */
    val written = mutableListOf<String>()

    override suspend fun ensureLoaded(): PamResult<Unit> = load

    override suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>> {
        scored += system to context
        val scores = when (system) {
            FormIntents.SYSTEM -> {
                val intent = intents[context.substringAfter("USER'S MESSAGE: ")] ?: FormIntent.ANSWER
                statements.map { if (it.contains(FormIntents.descriptions.getValue(intent))) YES else NO }
            }
            FormIntents.FILL_REQUEST_SYSTEM -> {
                val asks = context.substringAfter("USER'S MESSAGE: ") in fillRequests
                statements.map { if (asks) YES else NO }
            }
            else -> {
                val target = meanings[context.substringAfter("USER'S ANSWER: ")]
                statements.map { if (target != null && it.contains(target)) YES else NO }
            }
        }
        return PamResult.Success(scores)
    }

    override suspend fun write(system: String, user: String, maxTokens: Int): String? {
        written += user
        return question(user)
    }

    /** A model whose engine fails every score. */
    fun failScoring(): FormModel = object : FormModel {
        override suspend fun ensureLoaded() = load
        override suspend fun score(system: String, context: String, statements: List<String>): PamResult<List<Double>> =
            PamResult.Error(PamError.InferenceError("scoring failed"))

        override suspend fun write(system: String, user: String, maxTokens: Int): String? = null
    }

    companion object {
        const val YES = 4.0
        const val NO = -3.0
    }
}
