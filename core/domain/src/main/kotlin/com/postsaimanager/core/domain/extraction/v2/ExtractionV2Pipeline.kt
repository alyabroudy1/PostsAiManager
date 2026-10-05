package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.candidates.OcrText
import com.postsaimanager.core.domain.extraction.layout.LayoutDescription
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.text.SummaryFacts
import com.postsaimanager.core.domain.extraction.text.SummaryFactsReader
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.model.EnrichmentTicket
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
     * @param pageAspect width over height of page 1 when the caller knows it (only the layout template match uses it).
     * @param direction whose document it is; narrows the types the interpreter can choose from (incoming until P3 stores it).
     * @param traceContent adds page 1's lines (zone, position, text) and the name candidates to the trace. Only for a document
     *   its owner listed for diagnostics (a synthetic test letter): the trace is otherwise structure only.
     * @param forcedFamily a family a person chose: the first stage reads the letter as this one instead of deciding it.
     */
    suspend fun run(
        pages: List<List<OcrBlock>>,
        interpreter: DocumentInterpreter?,
        contextTokens: Int,
        pageAspect: Float? = null,
        direction: DocDirection = DocDirection.INCOMING,
        traceContent: Boolean = false,
        stages: Stages = Stages.ALL,
        ticket: EnrichmentTicket? = null,
        forcedFamily: String? = null,
    ): ExtractionV2Result {
        // Milliseconds per stage, as `t ...` lines of the trace (no letter text): the data layer logs them under one tag.
        val timings = mutableListOf<String>()
        var mark = System.nanoTime()
        fun lap(stage: String) {
            val now = System.nanoTime()
            timings += "t $stage ms=${(now - mark) / NANOS_PER_MS}"
            mark = now
        }
        val layout = layoutReader.read(pages)
        lap("layout")
        val candidates = candidateSource.find(pages, layout)
        val offered = CandidateTable.build(candidates)
        lap("candidates")
        if (interpreter == null) return foundOnly(candidates, offered, modelCalled = false, error = "no model is available")

        if (stages == Stages.SECOND) {
            return secondStage(pages, layout, candidates, offered, interpreter, pageAspect, direction, ticket ?: EnrichmentTicket(), timings)
        }

        val description = fitLayout(layout, interpreter, offered, contextTokens)
        val total = if (description.isComplete) description.text.length else layout.describe().text.length
        lap("fitLayout")

        val outcome = interpreter.interpret(InterpretationRequest(description.text, offered, layout, pageAspect, direction, forcedFamily))
        lap("interpret (everything the model decides: sessions, scoring, decoding)")
        if (outcome is InterpretationOutcome.Failed) {
            return foundOnly(
                candidates, offered, modelCalled = true, error = outcome.reason,
                raw = outcome.rawText, prompt = outcome.prompt, grammar = outcome.grammar,
                sent = description.text.length, total = total, pagesRead = description.pagesRead, totalPages = description.totalPages,
            )
        }
        outcome as InterpretationOutcome.Answered

        // What the model wrote besides the reading: a staged interpreter's second stage, or the one writeText call.
        var raw = outcome.raw
        var text: RawText? = null
        var rawText: String? = null
        var textError: String? = null
        var ticket: EnrichmentTicket? = null
        var summary: SummaryResult? = null
        val pageTexts = pages.map { blocks -> blocks.joinToString("\n") { OcrText.normalizeChars(it.text) } }
        fun context(rawText: String?, textError: String?) = VerificationContext(
            candidates = candidates,
            offered = offered,
            pageTexts = pageTexts,
            layoutCharsSent = description.text.length,
            layoutCharsTotal = total,
            pagesRead = description.pagesRead,
            totalPages = description.totalPages,
            prompt = outcome.prompt,
            grammar = outcome.grammar,
            rawAnswer = outcome.rawText,
            rawText = rawText,
            textError = textError,
        )
        if (interpreter.staged) {
            // The verified first reading is what the second stage's summary and title are built from (the facts), and what a ticket carries.
            val firstReading = verifier.verify(raw, null, context(null, null))
            val first = EnrichmentTicket(
                typeId = raw.type, takenIds = takenIds(raw), established = raw.established, topics = firstReading.topics,
                facts = SummaryFactsReader.of(firstReading).carried(),
            )
            if (stages == Stages.FIRST) {
                ticket = first
            } else {
                val enriched = interpreter.enrich(enrichmentRequest(layout, offered, pageAspect, direction, first, pageTexts.joinToString("\n")))
                lap("enrich (language, extras, summary, free text)")
                when (enriched) {
                    is EnrichmentOutcome.Done -> {
                        raw = raw.copy(
                            language = enriched.enrichment.language ?: raw.language, extras = enriched.enrichment.extras,
                            topics = raw.topics + enriched.enrichment.topics.orEmpty(),
                        )
                        text = enriched.enrichment.text
                        rawText = enriched.enrichment.rawText
                        textError = enriched.enrichment.textError
                        summary = enriched.enrichment.summary
                    }
                    is EnrichmentOutcome.Failed -> textError = enriched.reason
                }
            }
        } else {
            val textBudget = budgetChars(contextTokens, interpreter.maxTextTokens, interpreter.textOverheadChars())
            val textOutcome = interpreter.writeText(TextRequest(layout.describe(textBudget).text, raw.type))
            text = (textOutcome as? TextOutcome.Written)?.text
            rawText = (textOutcome as? TextOutcome.Written)?.rawText
            textError = (textOutcome as? TextOutcome.Failed)?.reason
            lap("writeText (language excluded: title, subject, summary, questions)")
        }

        val verified = verifier.verify(raw, text, context(rawText, textError))
        lap("verify")
        return verified.copy(
            enrichment = ticket, summary = summary,
            composedTitle = composeTitle(verified, verified.parties.sender?.name),
        ).withReading(
            layoutTrace(pages, layout, candidates, offered, description, traceContent) + timings + interpreter.trace,
            interpreter.unread, pages.size,
        )
    }

    /**
     * The second stage on its own (a later call, possibly another process than the first): the layout and candidates are found again from
     * the same pages, so the ids are the first stage's, and only what the first stage left is written. The result holds the language,
     * the extras and the free text (the first stage's slots and parties are not repeated).
     */
    private suspend fun secondStage(
        pages: List<List<OcrBlock>>,
        layout: LetterLayout,
        candidates: CandidateSet,
        offered: OfferedCandidates,
        interpreter: DocumentInterpreter,
        pageAspect: Float?,
        direction: DocDirection,
        ticket: EnrichmentTicket,
        timings: MutableList<String>,
    ): ExtractionV2Result {
        val started = System.nanoTime()
        val pageTexts = pages.map { blocks -> blocks.joinToString("\n") { OcrText.normalizeChars(it.text) } }
        // A ticket rebuilt from the stored document knows the values its fields hold but not their candidate ids: what reads as one of
        // them is as taken as an id the first stage listed.
        val storedValues = ticket.takenValues.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val takenByValue = if (storedValues.isEmpty()) emptyList() else candidates.candidates.filter { it.raw.trim() in storedValues || it.normalized in storedValues }.map { it.id }
        val asked = if (takenByValue.isEmpty()) ticket else ticket.copy(takenIds = (ticket.takenIds + takenByValue).distinct())
        val enriched = interpreter.enrich(enrichmentRequest(layout, offered, pageAspect, direction, asked, pageTexts.joinToString("\n")))
        timings += "t enrich (language, extras, summary, free text) ms=${(System.nanoTime() - started) / NANOS_PER_MS}"
        val done = (enriched as? EnrichmentOutcome.Done)?.enrichment
        val raw = RawInterpretation(
            type = ticket.typeId ?: ExtractionSchema.FREE_FORM.id, language = done?.language, parties = emptyList(), slots = emptyMap(),
            extras = done?.extras.orEmpty(), topics = ticket.topics + done?.topics.orEmpty(),
        )
        val verified = verifier.verify(
            raw, done?.text,
            VerificationContext(
                candidates = candidates, offered = offered, pageTexts = pageTexts,
                layoutCharsSent = 0, layoutCharsTotal = 0, pagesRead = pages.size, totalPages = pages.size,
                rawText = done?.rawText, textError = done?.textError ?: (enriched as? EnrichmentOutcome.Failed)?.reason,
            ),
        )
        return verified.copy(
            summary = done?.summary,
            composedTitle = composeTitle(verified, ticket.facts[SummaryFacts.SENDER]),
            diagnostics = verified.diagnostics.copy(modelCalled = true, modelUsed = done != null, trace = timings + interpreter.trace),
        )
    }

    /** The title from the family, the sender and the verified subject line; null when there is no family (no model read the letter) or nothing to say. */
    private fun composeTitle(result: ExtractionV2Result, sender: String?): TitleComposer.Composed? {
        val family = result.documentType?.id ?: return null
        return TitleComposer.compose(family, sender, result.freeText.subject?.value)
    }

    private fun enrichmentRequest(
        layout: LetterLayout, offered: OfferedCandidates, pageAspect: Float?, direction: DocDirection, ticket: EnrichmentTicket, ocrText: String,
    ) = EnrichmentRequest(
        offered, layout, pageAspect, direction, ticket.takenIds.toSet(), ticket.typeId, ticket.established,
        topics = ticket.topics, facts = ticket.facts, ocrText = ocrText,
    )

    /** The candidate ids the reading's slots and parties took (before verification: the ids the model's scores chose). */
    private fun takenIds(raw: RawInterpretation): List<String> =
        (raw.parties.map { it.id } + raw.slots.values.flatMap { listOfNotNull(it.id) + it.ids }).distinct()

    /** Which stages of the reading a run does: both in one go ([ALL]), or the first now and the second later, from the first's [EnrichmentTicket]. */
    enum class Stages { ALL, FIRST, SECOND }

    /**
     * The reading's trace, and what the interpreter left unread to fit its own window: it renders the letter itself, so its cut
     * is not the layout text's ([fitLayout]). A cut makes the reading partial, so the notice shows.
     */
    private fun ExtractionV2Result.withReading(lines: List<String>, unread: UnreadText?, pageCount: Int): ExtractionV2Result {
        val d = diagnostics
        if (unread == null) return copy(diagnostics = d.copy(trace = lines))
        val read = (unread.firstCutPage - 1).coerceIn(1, pageCount)
        return copy(
            diagnostics = d.copy(
                trace = lines, unreadLines = unread.lines,
                pagesRead = if (d.totalPages > 0) minOf(d.pagesRead, read) else read, totalPages = pageCount,
            ),
        )
    }

    /** The letter's shape, counts only: pages and their blocks and lines per zone, candidates per kind, what was sent. */
    private fun layoutTrace(
        pages: List<List<OcrBlock>>,
        layout: LetterLayout,
        candidates: CandidateSet,
        offered: OfferedCandidates,
        description: LayoutDescription,
        withContent: Boolean,
    ): List<String> {
        val perPage = layout.pages.joinToString(",") { p ->
            "p${p.pageNumber}:${pages.getOrNull(p.pageNumber - 1)?.size ?: 0}blocks/${p.lines.size}lines/${p.lines.count { it.isNoise }}noise"
        }
        val perZone = layout.pages.firstOrNull()?.lines.orEmpty().filter { !it.isNoise }.groupingBy { it.zone.tag }.eachCount()
        val perKind = candidates.candidates.groupingBy { it.kind.name }.eachCount()
        val structure = listOf(
            "layout pages=[$perPage] page1Zones=$perZone",
            "candidates found=${candidates.candidates.size} offered=${offered.size} byKind=$perKind dropped=${offered.dropped.mapKeys { it.key.name }}",
            "layoutText sent=${description.text.length} complete=${description.isComplete} pagesRead=${description.pagesRead}/${description.totalPages}",
        )
        if (!withContent) return structure
        val lines = layout.pages.firstOrNull()?.lines.orEmpty().map { l ->
            "line ${l.zone.tag}${if (l.isNoise) " NOISE" else ""} x=%.2f-%.2f y=%.2f-%.2f '%s'".format(
                java.util.Locale.ROOT, l.bounds.left, l.bounds.right, l.bounds.top, l.bounds.bottom, l.text.take(48),
            )
        }
        val names = offered.rows.filter { it.candidate.kind == CandidateKind.NAME }
            .map { "name ${it.candidate.id} zoneAttr=${it.candidate.attrs["zone"]} '${it.candidate.raw.take(48)}'" }
        return structure + lines + names
    }

    /**
     * The letter as the model reads it, within the window. From the tokenizer's own count when the
     * interpreter offers one (see [DocumentInterpreter.countTokens]): the description is measured and
     * the character budget corrected a few times until it fits without wasting room. Otherwise from
     * the [CHARS_PER_TOKEN] estimate.
     */
    private suspend fun fitLayout(
        layout: LetterLayout,
        interpreter: DocumentInterpreter,
        offered: OfferedCandidates,
        contextTokens: Int,
    ): LayoutDescription {
        val estimate = budgetChars(contextTokens, interpreter.maxAnswerTokens, interpreter.promptOverheadChars(offered))
        val overhead = interpreter.promptOverheadTokens(offered)
        if (overhead == null || interpreter.countTokens("") == null) return layout.describe(estimate)

        val available = contextTokens - interpreter.maxAnswerTokens - overhead
        var chars = estimate
        var best: LayoutDescription? = null
        var bestTokens = 0
        repeat(FIT_ROUNDS) {
            val description = layout.describe(chars)
            val tokens = interpreter.countTokens(description.text) ?: return layout.describe(estimate)
            if (tokens <= available) {
                if (best == null || tokens > bestTokens) {
                    best = description
                    bestTokens = tokens
                }
                // Complete, or close enough to the limit that another round cannot gain a whole run.
                if (description.isComplete || tokens >= available * FIT_ENOUGH) return description
                chars = (chars * available.toDouble() / tokens.coerceAtLeast(1)).toInt() + 1
            } else {
                chars = (chars * available.toDouble() / tokens * FIT_SHRINK).toInt()
            }
            chars = chars.coerceAtLeast(MIN_LAYOUT_CHARS)
        }
        return best ?: layout.describe(MIN_LAYOUT_CHARS)
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
         * A deliberately conservative 2.5 characters per token, used only when the interpreter cannot
         * count tokens itself ([DocumentInterpreter.countTokens]; the questionnaire can, through
         * `PromptSession.countTokens`). German compounds, numbers and Arabic script tokenise worse
         * than English, and an overflow silently drops the start of the prompt (the instructions).
         */
        const val CHARS_PER_TOKEN = 2.5
        const val MIN_LAYOUT_CHARS = 1024
        private const val NANOS_PER_MS = 1_000_000L

        /** How many times the measured fit corrects the character budget, and how close to the limit is close enough. */
        private const val FIT_ROUNDS = 4
        private const val FIT_ENOUGH = 0.92
        private const val FIT_SHRINK = 0.97

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

    private const val MAX_FOUND = 24

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
