package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.extraction.candidates.BlockKey
import com.postsaimanager.core.domain.extraction.candidates.LabelValuePairs
import com.postsaimanager.core.domain.extraction.layout.AvatarGlyph
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.v2.BlockZones
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.text.SummaryGate
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.ExtractorCandidateSource
import com.postsaimanager.core.domain.extraction.v2.LayoutReader
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.EnrichmentTicket
import com.postsaimanager.core.model.EventReading
import com.postsaimanager.core.model.OcrBlock
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** What a Gemma reading gave: the understanding in the pipeline's own type, or why the old reading has to run instead. */
sealed interface GemmaReadingOutcome {

    /** [summary] is one line of the fields decided and dropped (counts, never a word of the letter), for the log. */
    class Read(val understanding: DocumentUnderstanding, val summary: String) : GemmaReadingOutcome

    class Unavailable(val reason: String) : GemmaReadingOutcome
}

/**
 * "Gemma reads the letter" (the trial): reads one document with the chat model's schema-constrained answer instead of the zone
 * scorer, and hands back the same [DocumentUnderstanding] the rest of the app already stores and shows.
 *
 * The pieces, each one small and behind a port: the layout and the shape candidates (the pipeline's own), ML Kit's entities merged
 * into them ([EntityAnnotator], [MergedCandidateSource]), the reader ([GemmaDocumentReader], through [GemmaDocumentInterpreter]), code's
 * checks (one verification of its own: [GemmaReadingVerifier], mapped by [GemmaResultVerifier]; the scoring reading's
 * [com.postsaimanager.core.domain.extraction.v2.SelectionVerifier] is not run over a Gemma answer), the
 * actions bound to stored fields ([GemmaActionBinder]) and the same adapter ([ExtractionV2Adapter]).
 *
 * A page the OCR could not read (no line at all) is read from its pictures alone ([GemmaImageOnly]): every value is then marked
 * "to check", because no text of the letter can ground it.
 *
 * It never leaves a document unread by itself: whenever the reader cannot run, runs out of time or answers something unusable, the
 * outcome is [GemmaReadingOutcome.Unavailable] and the caller runs the reading it had before.
 */
class GemmaReadingUseCase @Inject constructor(
    private val reader: GemmaDocumentReader,
    private val entities: EntityAnnotator,
    private val activeModel: ActiveModelProvider,
) {

    private val adapter = ExtractionV2Adapter()
    private val summaryGate = SummaryGate()

    /**
     * @param pages the OCR blocks of every page, page 1 first (empty lists for a page the OCR read nothing of)
     * @param imagePaths the page pictures, already scaled for the model, page 1 first
     */
    suspend operator fun invoke(
        pages: List<List<OcrBlock>>,
        imagePaths: List<String>,
        pageAspect: Float? = null,
        forcedFamily: String? = null,
        onSummary: (suspend (EarlySummary) -> Unit)? = null,
        keepOpenAs: String? = null,
    ): GemmaReadingOutcome = try {
        val layout = LetterLayoutAnalyzer.analyze(pages)
        val lines = GemmaLetterBuilder.linesOf(layout)
        if (lines.isEmpty()) imageOnly(imagePaths, forcedFamily) else fromText(pages, layout, lines.map { it.text }, imagePaths, pageAspect, forcedFamily, onSummary, keepOpenAs)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GemmaReadingOutcome.Unavailable("the reading failed: ${e.javaClass.simpleName}")
    }

    private suspend fun fromText(
        pages: List<List<OcrBlock>>,
        layout: LetterLayout,
        lineTexts: List<String>,
        imagePaths: List<String>,
        pageAspect: Float?,
        forcedFamily: String?,
        onSummary: (suspend (EarlySummary) -> Unit)?,
        keepOpenAs: String?,
    ): GemmaReadingOutcome {
        // The reader's first turn is a short summary: checked by the summary gate against the letter's text (the facts are not read yet), and
        // handed on at once, so the document shows what it is about while the rest is still being read. One that passes is stored as the
        // model's summary and owes the text step nothing; one the gate refuses is handed on as "to check" with the gate's reason (a
        // faithful summary must never be lost to a check), and the text step replaces it with a verified one when it can write one.
        val summaryStored = AtomicBoolean(false)
        val sinkOnce = AtomicBoolean(false)
        val earlyNote = StringBuilder()
        val early: (suspend (String) -> Unit)? = onSummary?.let { sink ->
            { text ->
                val summary = earlySummary(text, layout.plainText())
                earlyNote.append("early summary: ${summary?.verdict ?: "not usable"}")
                if (summary != null && sinkOnce.compareAndSet(false, true)) {
                    if (summary.checked) summaryStored.set(true)
                    sink(summary)
                }
            }
        }
        // ML Kit's models are downloaded on demand: until they are there, the shape candidates are all there is.
        val spans = runCatching { entities.annotate(lineTexts) }.getOrNull()
        val source = MergedCandidateSource(ExtractorCandidateSource(), spans.orEmpty())
        val interpreter = GemmaDocumentInterpreter(
            reader, imagePaths,
            addressLines = { source.lastMerged?.addressLines.orEmpty() },
            letterDate = { source.lastMerged?.set?.letterDate },
            onSummary = early,
            keepOpenAs = keepOpenAs,
            labelPairs = {
                // In reading order: the label/value layout reads a block's lines one after the other, the OCR returns the blocks in any order.
                runCatching {
                    val ordered = BlockZones.inReadingOrder(AvatarGlyph.strip(pages), layout)
                    LabelValuePairs.of(ordered, BlockZones.of(ordered, layout))
                }.getOrDefault(emptyList())
            },
            ocrDump = {
                val stripped = AvatarGlyph.strip(pages)
                val zones = runCatching { BlockZones.of(stripped, layout) }.getOrDefault(emptyMap())
                stripped.flatMapIndexed { pi, blocks ->
                    blocks.mapIndexed { bi, b ->
                        "ocr p${pi + 1} b$bi [${b.bounds.left}, ${b.bounds.top}, ${b.bounds.right}, ${b.bounds.bottom}] zone=${zones[BlockKey(pi + 1, bi)]} " +
                            "text=" + b.text.replace("\n", "<NL>")
                    }
                }
            },
        )
        // The Gemma path's own verification: the checked reading ([GemmaReadingVerifier], in the interpreter) mapped as it is, with no
        // second pass of the scoring reading's caps over an answer the model gave with the page in view.
        val pipeline = ExtractionV2Pipeline(
            layoutReader = LayoutReader { layout }, candidateSource = source,
            verifier = GemmaResultVerifier(reading = { interpreter.decision?.verified }),
        )
        val window = activeModel.activeModelConfig().contextTokens
        val result = pipeline.run(pages, interpreter, window, pageAspect, DocDirection.INCOMING, forcedFamily = forcedFamily)
        val decision = interpreter.decision
        if (!result.diagnostics.modelUsed || decision == null) {
            return GemmaReadingOutcome.Unavailable(result.diagnostics.modelError ?: "the reader gave no usable answer")
        }
        val verified = decision.verified
        val title = TitleComposer.compose(result.documentType?.id, TitleSender.of(result.parties.sender?.name, layout), verified.name, result.freeText.subject?.value)
        // The timeline's event is the reader's own answer (a kind of the event registry, asked in the same call); its title is the document's.
        // The summary and the key facts are not in this answer: the ticket owes them to the text step ([GemmaTextWriter]), which writes them
        // once this reading is stored, knowing whether the document was paid already.
        val read = result.copy(
            actions = GemmaActionBinder.bind(verified.actions, result), composedTitle = title,
            event = verified.eventKind?.let { EventReading(it) },
            enrichment = EnrichmentTicket(oneGo = true, paid = verified.paid?.id, summaryDone = summaryStored.get()),
        )
        val merged = source.lastMerged
        // The header comes first: the data layer logs it always, so a trial reading is told apart from the usual one in the log.
        val trace = listOf(
            "reader=gemma interpreter=${interpreter.name} window=$window",
            "entities=${if (spans == null) "not available yet (shape candidates only)" else "${spans.size} spans, ${merged?.added ?: 0} candidates added"}",
        ) + listOfNotNull(earlyNote.toString().takeIf { it.isNotEmpty() }) + read.diagnostics.trace
        val understanding = adapter.adapt(read.copy(diagnostics = read.diagnostics.copy(trace = trace)))
        return GemmaReadingOutcome.Read(understanding, summaryLine(read, verified))
    }

    private suspend fun imageOnly(imagePaths: List<String>, forcedFamily: String?): GemmaReadingOutcome {
        if (imagePaths.isEmpty()) return GemmaReadingOutcome.Unavailable("the page has no text and no picture")
        val category = forcedFamily?.let { ExtractionSchema.DEFAULT.categoryOf(it)?.phrase }
        val outcome = reader.read(GemmaReaderRequest(GemmaLetter(emptyList(), emptyList()), imagePaths, category))
        val answered = when (outcome) {
            is GemmaReaderOutcome.Unavailable -> return GemmaReadingOutcome.Unavailable(outcome.reason)
            is GemmaReaderOutcome.Answered -> outcome
        }
        val reading = when (val parsed = GemmaReadingParser.parse(answered.json)) {
            is GemmaReadingParser.Parsed.Ok -> parsed.reading
            is GemmaReadingParser.Parsed.Bad -> return GemmaReadingOutcome.Unavailable(parsed.reason)
        }
        val trace = listOf(
            "reader=gemma interpreter=picture-only",
            "t gemma reader (picture only) ms=${answered.ms}",
            "gemma input lines=0 candidates=0 image=yes json=${answered.json.length} chars; every value is to check",
        )
        val result = GemmaImageOnly().result(reading, trace, answered.json, forcedFamily = forcedFamily)
        return GemmaReadingOutcome.Read(
            adapter.adapt(result),
            "picture only: slots=${result.slots.size} parties=${result.parties.all.size} extras=${result.extras.size} dropped=${result.diagnostics.rejections.size}",
        )
    }

    /**
     * The first turn's text as the summary to hand on, with the gate's verdict; null when there is nothing usable (empty, or a runaway
     * far past what was asked). A summary the gate accepts, within the asked length, is [EarlySummary.checked]; any other is "to check".
     */
    private fun earlySummary(text: String, ocrText: String): EarlySummary? {
        val oneLine = text.trim().replace(WHITESPACE, " ")
        return when (val verdict = summaryGate.check(oneLine, ocrText, emptyList())) {
            // The gate holds the one length limit (SummaryLimits) and trims a summary a little over it to its last whole sentence.
            is SummaryGate.Verdict.Accepted ->
                EarlySummary(verdict.text, true, if (verdict.text.length < oneLine.length) "accepted, trimmed from ${oneLine.length} to ${verdict.text.length} chars" else "accepted")
            is SummaryGate.Verdict.Rejected ->
                if (verdict.reason == SummaryGate.Reason.EMPTY || oneLine.length > MAX_UNCHECKED_SUMMARY_CHARS) null
                else EarlySummary(oneLine, false, "rejected ${verdict.reason}, stored to check")
        }
    }

    private companion object {
        /** A summary the gate refused is still stored (to check) unless it is a runaway past this. */
        const val MAX_UNCHECKED_SUMMARY_CHARS = 600
        val WHITESPACE = Regex("\\s+")
    }

    /** The decided fields as counts and keys: what the log shows next to the engine's timing, never a word of the letter. */
    private fun summaryLine(result: ExtractionV2Result, verified: VerifiedReading): String =
        "category=${result.documentType?.id} language=${result.language} sender=${result.parties.sender != null} " +
            "addressees=${result.parties.allAddressees.size} contact=${result.parties.contact != null} " +
            "slots=[${result.slots.keys.joinToString(",") { it.json }}] extras=${result.extras.size} " +
            "actions=[${result.actions.orEmpty().joinToString(",") { it.kind }}] name=${verified.name != null} paid=${verified.paid?.id} " +
            "dropped=${verified.drops.size} needsReview=${result.needsReview}"
}
