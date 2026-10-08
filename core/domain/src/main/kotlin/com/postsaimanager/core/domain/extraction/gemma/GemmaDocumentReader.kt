package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.common.result.PamResult
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.ChatImagePolicy
import com.postsaimanager.core.domain.ai.ModelUse
import com.postsaimanager.core.domain.ai.loadForUse
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.model.ModelRuntime
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * What the reader is given: the letter's lines and candidates (none for a page the OCR could not read), the pictures of its pages
 * (files, already scaled), and the category a person said it is, when they did.
 */
class GemmaReaderRequest(
    val letter: GemmaLetter,
    val imagePaths: List<String>,
    /** The category as a phrase ("a bill or an invoice"): context for the answer, never a switch. */
    val forcedCategory: String? = null,
    /**
     * Asked for, the reader starts with a short plain-text summary of the letter and hands it here the moment it is written, while the
     * structured answer is still being generated (never for a letter with no text: nothing could verify the summary).
     */
    val onSummary: (suspend (String) -> Unit)? = null,
)

sealed interface GemmaReaderOutcome {

    /** The model's answer, constrained to [schema]; [prompt] and [schema] are what was sent (for the trace and the tests). */
    class Answered(val json: String, val schema: String, val prompt: String, val ms: Long, val usedImage: Boolean) : GemmaReaderOutcome

    /** The reader could not run: no LiteRT chat model installed, the model busy, the run failed or ran out of time. */
    class Unavailable(val reason: String) : GemmaReaderOutcome
}

/** The port "Gemma reads the letter" goes through; the implementation is the LiteRT-LM chat model ([ChatEngineGemmaReader]). */
interface GemmaDocumentReader {
    suspend fun read(request: GemmaReaderRequest): GemmaReaderOutcome
}

/**
 * [GemmaDocumentReader] over the chat model: the LiteRT-LM Gemma the app chats with, loaded through the same [ChatEngine] (so the one-model
 * rule and the chat's gate hold: loading it replaces whatever model is resident, and a reply or another reading in flight makes the
 * answer "unavailable" at once, never a queue). It asks for one answer constrained to the letter's schema ([GemmaSchema]): thinking
 * off, low temperature, the pictures in front of the text.
 *
 * Unavailable, with the reason: no chat model installed, one that is not a LiteRT-LM model, one that cannot take pictures when the
 * letter has no text (an image-only reading), a load that failed, a model that is busy, an answer that did not come within
 * [TIMEOUT_MS] or at all.
 */
class ChatEngineGemmaReader @Inject constructor(
    private val engine: ChatEngine,
    private val activeModel: ActiveModelProvider,
) : GemmaDocumentReader {

    override suspend fun read(request: GemmaReaderRequest): GemmaReaderOutcome {
        val started = System.nanoTime()
        val path = activeModel.activeModelPath() ?: return GemmaReaderOutcome.Unavailable("no chat model is installed")
        val config = activeModel.readingModelConfig()
        if (config.runtime != ModelRuntime.LITERT_LM) return GemmaReaderOutcome.Unavailable("the chat model is not a LiteRT-LM model")
        val images = if (ChatImagePolicy.enabledFor(config)) request.imagePaths else emptyList()
        if (request.letter.isImageOnly && images.isEmpty()) return GemmaReaderOutcome.Unavailable("no text and no picture the model can take")

        if (engine.loadForUse(ModelUse.READING, path, config) is PamResult.Error) {
            return GemmaReaderOutcome.Unavailable("the chat model could not be loaded")
        }

        val imageOnly = request.letter.isImageOnly
        val schema = GemmaSchema.build(request.letter)
        val maxChars = promptChars(config.contextTokens, images.size)
        // With a summary wanted first, one conversation holds two turns: the letter and the summary question, then the field guide.
        val turns = if (request.onSummary != null && !imageOnly) {
            GemmaPrompt.turns(request.letter, forcedCategory = request.forcedCategory, maxChars = maxChars)
        } else {
            null
        }
        val prompt = turns?.second ?: GemmaPrompt.user(request.letter, forcedCategory = request.forcedCategory, maxChars = maxChars)
        val structured = StructuredRequest(
            system = GemmaPrompt.system(imageOnly, summaryFirst = turns != null), prompt = prompt, schema = schema, imagePaths = images,
            maxTokens = ANSWER_TOKENS.coerceAtMost((config.contextTokens / 2).coerceAtLeast(MIN_ANSWER_TOKENS)),
            timeoutMs = TIMEOUT_MS,
            leadPrompt = turns?.first, onLead = turns?.let { request.onSummary },
        )
        // The service enforces the timeout itself; this one only keeps a binder call that never returns from holding the reading.
        val json = withTimeoutOrNull(TIMEOUT_MS + GRACE_MS) { engine.generateStructured(structured) }
            ?: return GemmaReaderOutcome.Unavailable("no answer (the model is busy, the run failed or it took longer than ${TIMEOUT_MS / MS_PER_S} s)")
        return GemmaReaderOutcome.Answered(json, schema, (turns?.first?.let { "$it\n---\n" } ?: "") + prompt,(System.nanoTime() - started) / NANOS_PER_MS, images.isNotEmpty())
    }

    /** The characters of lines and candidates the window holds next to the picture, the instructions and the answer. */
    private fun promptChars(contextTokens: Int, pictures: Int): Int {
        val room = contextTokens - ANSWER_TOKENS - RESERVED_TOKENS - pictures * IMAGE_TOKENS
        return (room * CHARS_PER_TOKEN).toInt().coerceIn(GemmaPrompt.MIN_CHARS, GemmaPrompt.MAX_CHARS)
    }

    companion object {
        /** A reading that takes longer than this is given up (the service stops the generation) and the letter is read the old way. */
        const val TIMEOUT_MS = 120_000L

        private const val GRACE_MS = 15_000L
        /** The short answer ([GemmaSchema]) is about 150 tokens; this is the ceiling that stops a runaway one. */
        private const val ANSWER_TOKENS = 512
        private const val MIN_ANSWER_TOKENS = 256

        /** The system text, the field guide and the registries' sentences. */
        private const val RESERVED_TOKENS = 900

        /** What one page picture costs the window (the model's own budget is 280 to 560). */
        private const val IMAGE_TOKENS = 560

        /** The conservative estimate the pipeline budgets with as well (German compounds and numbers tokenise badly). */
        private const val CHARS_PER_TOKEN = 2.5
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_S = 1_000L
    }
}
