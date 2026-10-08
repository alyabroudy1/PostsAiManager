package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.ai.ActiveModelProvider
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.text.SummaryResult
import com.postsaimanager.core.domain.extraction.text.TitleComposer
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Adapter
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Pipeline
import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.v2.ExtractorCandidateSource
import com.postsaimanager.core.domain.extraction.v2.LayoutReader
import com.postsaimanager.core.model.DocumentUnderstanding
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.SummarySource
import kotlinx.coroutines.CancellationException
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
 * checks ([GemmaReadingVerifier], then the pipeline's own [com.postsaimanager.core.domain.extraction.v2.SelectionVerifier]), the
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

    /**
     * @param pages the OCR blocks of every page, page 1 first (empty lists for a page the OCR read nothing of)
     * @param imagePaths the page pictures, already scaled for the model, page 1 first
     */
    suspend operator fun invoke(
        pages: List<List<OcrBlock>>,
        imagePaths: List<String>,
        pageAspect: Float? = null,
        forcedFamily: String? = null,
    ): GemmaReadingOutcome = try {
        val layout = LetterLayoutAnalyzer.analyze(pages)
        val lines = GemmaLetterBuilder.linesOf(layout)
        if (lines.isEmpty()) imageOnly(imagePaths, forcedFamily) else fromText(pages, layout, lines.map { it.text }, imagePaths, pageAspect, forcedFamily)
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
    ): GemmaReadingOutcome {
        // ML Kit's models are downloaded on demand: until they are there, the shape candidates are all there is.
        val spans = runCatching { entities.annotate(lineTexts) }.getOrNull()
        val source = MergedCandidateSource(ExtractorCandidateSource(), spans.orEmpty())
        val interpreter = GemmaDocumentInterpreter(
            reader, imagePaths,
            addressLines = { source.lastMerged?.addressLines.orEmpty() },
            letterDate = { source.lastMerged?.set?.letterDate },
        )
        val pipeline = ExtractionV2Pipeline(layoutReader = LayoutReader { layout }, candidateSource = source)
        val window = activeModel.activeModelConfig().contextTokens
        val result = pipeline.run(pages, interpreter, window, pageAspect, DocDirection.INCOMING, forcedFamily = forcedFamily)
        val decision = interpreter.decision
        if (!result.diagnostics.modelUsed || decision == null) {
            return GemmaReadingOutcome.Unavailable(result.diagnostics.modelError ?: "the reader gave no usable answer")
        }
        val verified = decision.verified
        val summary = verified.summary?.let { SummaryResult(it, SummarySource.MODEL, null, emptyList()) }
        val title = TitleComposer.compose(result.documentType?.id, result.parties.sender?.name, verified.name, result.freeText.subject?.value)
        val read = result.copy(summary = summary, actions = GemmaActionBinder.bind(verified.actions, result), composedTitle = title)
        val merged = source.lastMerged
        // The header comes first: the data layer logs it always, so a trial reading is told apart from the usual one in the log.
        val trace = listOf(
            "reader=gemma-trial interpreter=${interpreter.name} window=$window",
            "entities=${if (spans == null) "not available yet (shape candidates only)" else "${spans.size} spans, ${merged?.added ?: 0} candidates added"}",
        ) + read.diagnostics.trace
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
            "reader=gemma-trial interpreter=picture-only",
            "t gemma reader (picture only) ms=${answered.ms}",
            "gemma input lines=0 candidates=0 image=yes json=${answered.json.length} chars; every value is to check",
        )
        val result = GemmaImageOnly().result(reading, trace, answered.json, forcedFamily = forcedFamily)
        return GemmaReadingOutcome.Read(
            adapter.adapt(result),
            "picture only: slots=${result.slots.size} parties=${result.parties.all.size} extras=${result.extras.size} dropped=${result.diagnostics.rejections.size}",
        )
    }

    /** The decided fields as counts and keys: what the log shows next to the engine's timing, never a word of the letter. */
    private fun summaryLine(result: ExtractionV2Result, verified: VerifiedReading): String =
        "category=${result.documentType?.id} language=${result.language} sender=${result.parties.sender != null} " +
            "addressees=${result.parties.allAddressees.size} contact=${result.parties.contact != null} " +
            "slots=[${result.slots.keys.joinToString(",") { it.json }}] extras=${result.extras.size} " +
            "actions=[${result.actions.orEmpty().joinToString(",") { it.kind }}] name=${verified.name != null} summary=${verified.summary != null} " +
            "dropped=${verified.drops.size} needsReview=${result.needsReview}"
}
