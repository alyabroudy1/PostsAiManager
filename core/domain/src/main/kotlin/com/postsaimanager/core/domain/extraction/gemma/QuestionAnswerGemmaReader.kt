package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.common.util.TimingLog
import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.ai.ChatEngine
import com.postsaimanager.core.domain.ai.RepetitionGuard
import com.postsaimanager.core.domain.ai.StructuredRequest
import com.postsaimanager.core.domain.ai.samplingFor
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * The "Questions" reader style: reads a letter by asking, the way the chat does, instead of forcing a JSON schema over the model.
 *
 * One message per letter: the letter as plain lines, the page picture only when the OCR text is weak ([QaImageDecision]), and every
 * question once, answered in one terse labelled line each ([QuestionPrompt], [QuestionAnswerParser]). With a summary wanted, it is the
 * first line of that same answer: the engine streams the answer, and the summary is handed on the moment its line ends
 * ([SummaryLineWatcher]), a few seconds after the letter is read and long before the rest is decoded. No response format constrains the
 * answer, and nothing is checked afterwards: the answers are stored as the model gave them and the person confirms them
 * ([QuestionReadingBuilder]).
 *
 * Sampling: greedy everywhere ([QaSampling]), so the same letter gives the same answer; a decode that starts repeating itself is stopped by
 * the engine ([RepetitionGuard]) and the repeat is cut here, the answer so far is kept. The model is loaded for a
 * reading ([ReadingModelGate]), so the "Reading: CPU / GPU" setting decides where it runs.
 * A letter with no text lines (a picture only) has nothing to ask about: the JSON reader takes it ([StyleSwitchedGemmaReader]).
 */
class QuestionAnswerGemmaReader @Inject constructor(
    private val engine: ChatEngine,
    activeModel: ActiveModelProvider,
    private val style: GemmaReaderStyle,
) : GemmaDocumentReader {

    private val gate = ReadingModelGate(engine, activeModel)

    override suspend fun read(request: GemmaReaderRequest): GemmaReaderOutcome {
        val started = System.nanoTime()
        if (request.letter.isImageOnly) return GemmaReaderOutcome.Unavailable("the Questions reader needs the letter's text")
        val (config, offeredImages) = when (val opened = gate.open(request.imagePaths, imageOnly = false)) {
            is ReadingModelGate.Opened.Unavailable -> return GemmaReaderOutcome.Unavailable(opened.reason)
            is ReadingModelGate.Opened.Ready -> opened.config to opened.images
        }
        val imageDecision = QaImageDecision.decide(request.letter, alwaysSend = style.alwaysSendImage())
        val images = if (imageDecision.send) offeredImages else emptyList()
        val room = config.contextTokens - ANSWER_TOKENS - RESERVED_TOKENS - images.size * IMAGE_TOKENS
        val letterText = QuestionPrompt.letterText(request.letter, (room * CHARS_PER_TOKEN).toInt().coerceIn(GemmaPrompt.MIN_CHARS, GemmaPrompt.MAX_CHARS))
        val withSummary = request.onSummary != null
        val prompt = QuestionPrompt.message(letterText, withSummary, forcedCategory = request.forcedCategory, summaryLanguage = request.summaryLanguage)
        val sampling = samplingFor(QaSampling.PURPOSE)
        val watcher = SummaryLineWatcher()
        var summaryMs = -1L
        val handOn: suspend (String) -> Unit = { summary ->
            summaryMs = (System.nanoTime() - started) / NANOS_PER_MS
            request.onSummary?.invoke(summary)
        }
        val partial: (suspend (String) -> Unit)? = if (withSummary) ({ soFar: String -> watcher.feed(soFar)?.let { handOn(it) }; Unit }) else null
        val structured = StructuredRequest(
            system = QuestionPrompt.system(), prompt = prompt, schema = "", imagePaths = images, maxTokens = ANSWER_TOKENS,
            temperature = sampling.temperature, topK = sampling.topK, topP = sampling.topP, timeoutMs = TIMEOUT_MS,
            keepOpenAs = request.keepOpenAs,
            onPartial = partial,
        )
        val decoded = withTimeoutOrNull(TIMEOUT_MS + GRACE_MS) { engine.generateStructured(structured) }
            ?: return GemmaReaderOutcome.Unavailable("no answer (the model is busy, the run failed or it took longer than ${TIMEOUT_MS / MS_PER_S} s)")
        // A decode that looped was stopped by the engine; what came before the loop is the answer, and it is still stored.
        val answer = RepetitionGuard.trimmed(decoded)
        val loopNote = if (answer.length < decoded.length) "qa note: loop guard cut the answer at ${answer.length} of ${decoded.length} chars" else null
        // A stream that showed no finished summary line (the engine does not stream, or the line was the last): the final answer has it.
        if (withSummary) watcher.finish(answer)?.let { handOn(it) }
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val answers = QuestionAnswerParser.parse(answer)
        val asked = QuestionPrompt.asked(withSummary)
        val notes = listOf(
            "qa asked ${asked.size} labels in one message, answered=${answers.answered.size} text=${answer.length} chars " +
                "missing=[${(asked - answers.answered).joinToString(",")}]",
            "qa image=${if (images.isNotEmpty()) "yes" else "no"} (${imageDecision.reason}) backend=${config.accelerator} " +
                "sampling=topK${sampling.topK} summaryAt=${if (summaryMs >= 0) "${summaryMs}ms" else "none"}",
        ) + listOfNotNull(loopNote, "qa note: no summary line in the answer".takeIf { withSummary && QaLabel.SUMMARY !in answers.answered })
        TimingLog.log(
            "reader: qa answer total=${ms}ms summaryAt=${summaryMs}ms image=${if (images.isNotEmpty()) "yes" else "no"} reason=\"${imageDecision.reason}\" " +
                "backend=${config.accelerator} answered=${answers.answered.size}/${asked.size} chars=${answer.length}",
        )
        return GemmaReaderOutcome.Stated(answer, prompt, ms, images.isNotEmpty(), notes)
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L
        const val GRACE_MS = 15_000L

        /** One answer of a summary line and about a dozen terse ones: the ceiling that stops a runaway one (decoding is the slow part). */
        const val ANSWER_TOKENS = 300
        const val RESERVED_TOKENS = 1_400
        const val IMAGE_TOKENS = 560
        const val CHARS_PER_TOKEN = 2.5
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_S = 1_000L
    }
}
