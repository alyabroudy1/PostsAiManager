package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.candidates.OcrText
import com.postsaimanager.core.model.OcrBlock

/**
 * Runs the stages in order; every stage is a port, so this class only sequences them.
 *
 * layout → candidates → call 1 (structured) → call 2 (free text) → verify. Without an interpreter, or
 * when call 1's answer is unusable, the result is only the values code found, with no type, no roles
 * and no guesses ([ExtractionV2Result.foundValues]), marked for review. When only call 2 fails the
 * structured reading stands and the result has no free text.
 */
class ExtractionV2Pipeline(
    private val layoutReader: LayoutReader = AnalyzerLayoutReader(),
    private val candidateSource: CandidateSource = ExtractorCandidateSource(),
    private val verifier: ResultVerifier = SelectionVerifier(),
) {

    /**
     * @param pages OCR blocks per page, page 1 first.
     * @param interpreter the model, or null when no model is available.
     * @param contextTokens the window the model is loaded with; the letter is budgeted against it.
     */
    suspend fun run(
        pages: List<List<OcrBlock>>,
        interpreter: DocumentInterpreter?,
        contextTokens: Int,
    ): ExtractionV2Result {
        val layout = layoutReader.read(pages)
        val candidates = candidateSource.find(pages, layout)
        val offered = CandidateTable.build(candidates)
        if (interpreter == null) return foundOnly(candidates, offered, modelCalled = false, error = "no model is available")

        val budget = budgetChars(contextTokens, interpreter.maxAnswerTokens, interpreter.promptOverheadChars(offered))
        val description = layout.describe(budget)
        val total = if (description.isComplete) description.text.length else layout.describe().text.length

        val outcome = interpreter.interpret(InterpretationRequest(description.text, offered))
        if (outcome is InterpretationOutcome.Failed) {
            return foundOnly(
                candidates, offered, modelCalled = true, error = outcome.reason,
                raw = outcome.rawText, prompt = outcome.prompt, grammar = outcome.grammar,
                sent = description.text.length, total = total, pagesRead = description.pagesRead, totalPages = description.totalPages,
            )
        }
        outcome as InterpretationOutcome.Answered

        val textBudget = budgetChars(contextTokens, interpreter.maxTextTokens, interpreter.textOverheadChars())
        val textOutcome = interpreter.writeText(TextRequest(layout.describe(textBudget).text, outcome.raw.type))
        val written = textOutcome as? TextOutcome.Written

        return verifier.verify(
            outcome.raw,
            written?.text,
            VerificationContext(
                candidates = candidates,
                offered = offered,
                pageTexts = pages.map { blocks -> blocks.joinToString("\n") { OcrText.normalizeChars(it.text) } },
                layoutCharsSent = description.text.length,
                layoutCharsTotal = total,
                pagesRead = description.pagesRead,
                totalPages = description.totalPages,
                prompt = outcome.prompt,
                grammar = outcome.grammar,
                rawAnswer = outcome.rawText,
                rawText = written?.rawText,
                textError = (textOutcome as? TextOutcome.Failed)?.reason,
            ),
        )
    }

    private fun foundOnly(
        candidates: CandidateSet,
        offered: OfferedCandidates,
        modelCalled: Boolean,
        error: String,
        raw: String? = null,
        prompt: String? = null,
        grammar: String? = null,
        sent: Int = 0,
        total: Int = 0,
        pagesRead: Int = 0,
        totalPages: Int = 0,
    ) = ExtractionV2Result(
        documentType = null,
        language = null,
        slots = emptyMap(),
        foundValues = FoundValues.of(candidates),
        letterDate = candidates.letterDate,
        diagnostics = Diagnostics(
            candidateCount = candidates.candidates.size,
            offeredCount = offered.size,
            offeredDropped = offered.dropped.mapKeys { it.key.name },
            modelCalled = modelCalled,
            modelUsed = false,
            modelError = error,
            rawAnswer = raw,
            layoutCharsSent = sent,
            layoutCharsTotal = total,
            pagesRead = pagesRead,
            totalPages = totalPages,
            grammar = grammar,
            prompt = prompt,
        ),
    )

    companion object {
        /**
         * A deliberately conservative 2.5 characters per token. The engine does not expose a token
         * count, so this is an estimate; German compounds, numbers and Arabic script tokenise worse
         * than English, and an overflow silently drops the start of the prompt (the instructions).
         * Replace with a measured count if the AiEngine port ever offers one.
         */
        const val CHARS_PER_TOKEN = 2.5
        const val MIN_LAYOUT_CHARS = 1024

        /** Characters left for the letter once the answer and the fixed prompt have taken their share of the window. */
        fun budgetChars(contextTokens: Int, answerTokens: Int, overheadChars: Int): Int =
            ((contextTokens - answerTokens) * CHARS_PER_TOKEN).toInt().minus(overheadChars).coerceAtLeast(MIN_LAYOUT_CHARS)
    }
}

/**
 * The values code found, reduced to their shape: no label, no role, no zone, no type.
 * What the fallback shows when there is no model, so nothing is guessed about what a value means.
 */
object FoundValues {

    private const val MAX_FOUND = 16

    private val KINDS = listOf(
        CandidateKind.DATE, CandidateKind.DATETIME, CandidateKind.AMOUNT, CandidateKind.IBAN,
        CandidateKind.REFERENCE, CandidateKind.PHONE, CandidateKind.EMAIL,
    )

    fun of(set: CandidateSet): List<Candidate> {
        val seen = HashSet<String>()
        return KINDS.flatMap { kind ->
            set.candidates.filter { it.kind == kind && it.attrs["timeOnly"] == null && !it.validation.isInvalid }
        }.filter { seen.add("${it.kind}:${it.normalized}") }.take(MAX_FOUND)
    }
}
