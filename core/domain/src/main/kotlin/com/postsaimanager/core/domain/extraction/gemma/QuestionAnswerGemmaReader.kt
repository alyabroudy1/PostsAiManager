package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.common.util.TimingLog
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.SamplingPurpose
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.domain.ai.samplingFor
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The "Questions" reader style: reads a letter by asking, the way the chat does, instead of forcing a JSON schema over the model.
 *
 * One conversation per letter: the letter as plain lines and one page picture, with the question for a short summary (answered in free
 * text and handed on at once, so the document shows what it is about first); then every other question once, in one message, answered
 * in one labelled line each ([QuestionPrompt], [QuestionAnswerParser]). The letter and the picture are prefilled once. No response format
 * constrains the answer, and nothing is checked afterwards: the answers are stored as the model gave them and the person confirms them
 * ([QuestionReadingBuilder]).
 *
 * Sampling is the chat's own ([SamplingPurpose.FREE_TEXT]): the question is answered in plain words, as a person would be answered.
 * A letter with no text lines (a picture only) has nothing to ask about: the JSON reader takes it ([StyleSwitchedGemmaReader]).
 */
class QuestionAnswerGemmaReader @Inject constructor(
    private val engine: ChatEngine,
    activeModel: ActiveModelProvider,
) : GemmaDocumentReader {

    private val gate = ReadingModelGate(engine, activeModel)

    override suspend fun read(request: GemmaReaderRequest): GemmaReaderOutcome {
        val started = System.nanoTime()
        if (request.letter.isImageOnly) return GemmaReaderOutcome.Unavailable("the Questions reader needs the letter's text")
        val (config, images) = when (val opened = gate.open(request.imagePaths, imageOnly = false)) {
            is ReadingModelGate.Opened.Unavailable -> return GemmaReaderOutcome.Unavailable(opened.reason)
            is ReadingModelGate.Opened.Ready -> opened.config to opened.images
        }
        val room = config.contextTokens - ANSWER_TOKENS - RESERVED_TOKENS - images.size * IMAGE_TOKENS
        val letterText = QuestionPrompt.letterText(request.letter, (room * CHARS_PER_TOKEN).toInt().coerceIn(GemmaPrompt.MIN_CHARS, GemmaPrompt.MAX_CHARS))
        val questions = QuestionPrompt.questions(forcedCategory = request.forcedCategory)
        val withSummary = request.onSummary != null
        val sampling = samplingFor(SamplingPurpose.FREE_TEXT)
        val lead = if (withSummary) QuestionPrompt.summaryTurn(letterText) else null
        val prompt = if (withSummary) questions else letterText + "\n" + questions
        val structured = StructuredRequest(
            system = QuestionPrompt.system(), prompt = prompt, schema = "", imagePaths = images, maxTokens = ANSWER_TOKENS,
            temperature = sampling.temperature, topK = sampling.topK, topP = sampling.topP, timeoutMs = TIMEOUT_MS,
            leadPrompt = lead, onLead = request.onSummary, keepOpenAs = request.keepOpenAs,
        )
        val answer = withTimeoutOrNull(TIMEOUT_MS + GRACE_MS) { engine.generateStructured(structured) }
            ?: return GemmaReaderOutcome.Unavailable("no answer (the model is busy, the run failed or it took longer than ${TIMEOUT_MS / MS_PER_S} s)")
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val answers = QuestionAnswerParser.parse(answer)
        val notes = listOf(
            "qa asked ${QaLabel.entries.size} labels in one message, answered=${answers.answered.size} text=${answer.length} chars " +
                "missing=[${(QaLabel.entries - answers.answered).joinToString(",")}]",
        )
        TimingLog.log("reader: qa answer total=${ms}ms answered=${answers.answered.size}/${QaLabel.entries.size} chars=${answer.length}")
        return GemmaReaderOutcome.Stated(answer, (lead?.let { "$it\n---\n" } ?: "") + prompt, ms, images.isNotEmpty(), notes)
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L
        const val GRACE_MS = 15_000L

        /** One answer of about a dozen short lines: the ceiling that stops a runaway one. */
        const val ANSWER_TOKENS = 448
        const val RESERVED_TOKENS = 1_400
        const val IMAGE_TOKENS = 560
        const val CHARS_PER_TOKEN = 2.5
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L
    }
}
