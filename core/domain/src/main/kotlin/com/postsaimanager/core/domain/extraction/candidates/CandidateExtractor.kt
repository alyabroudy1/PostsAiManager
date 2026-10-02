package com.postsaimanager.core.domain.extraction.candidates

import com.postsaimanager.core.model.OcrBlock
import java.time.LocalDate

/**
 * Deterministic candidate finder.
 *
 * Reads OCR blocks (page aware) and proposes every date, amount, IBAN, BIC, reference, phone,
 * e-mail, identifier and name it can see, each with its page, bbox, evidence line, nearest text
 * and a validation verdict. It never decides which candidate fills which slot.
 *
 * Existence is decided by shape, never by a word in any language: an identifier is a token of
 * digits mixed with letters or separators, a name is a short line of words without digits in the
 * top half of page 1 or in a footer (one neutral [CandidateKind.NAME], person or company is the
 * model's call). Labels (Kundennummer, Aktenzeichen ...) and the layout zone only add hints (a
 * subtype, a nearby text, a zone) to a candidate that exists anyway (see [LabelHints]). A period in
 * words ("within a month") is not found here at all: the model quotes it and the verifier checks
 * the quote. Amount triples are found by arithmetic. No letter date is inferred from a label.
 *
 * This object only orchestrates: one [CandidateFinder] per kind does the finding, then the drafts
 * are put in reading order, checked and given their ids ([CandidateIds]).
 *
 * Pure Kotlin: no Android, no I/O. Safe to run in JVM tests and in a worker.
 */
object CandidateExtractor {

    /**
     * The finders in the order they run. The order is part of the output: two candidates that start at
     * the same place on a line keep the order of their finders, which decides their ids. Shape-only
     * BICs come last (see [ShapeBicFinder]).
     */
    private val finders: List<CandidateFinder> = listOf(
        IbanFinder,
        DateFinder,
        BicFinder,
        ReferenceFinder,
        AmountFinder,
        PhoneEmailFinder,
        IdentifierFinder,
        NameFinder,
        ShapeBicFinder,
    )

    /**
     * @param pages OCR blocks per page (page 1 first).
     * @param zones optional layout hints per block; they travel along on name candidates as a hint.
     * @param letterDate the letter's date when the caller knows it, to range-check the other dates;
     *   otherwise dates stay unchecked here and the verifier anchors on the date the model chose.
     */
    fun extract(
        pages: List<List<OcrBlock>>,
        zones: Map<BlockKey, BlockZone> = emptyMap(),
        letterDate: LocalDate? = null,
    ): CandidateSet = run(ExtractionContext.ofPages(pages, zones), letterDate)

    /** Plain-text entry point (no bounds, one page); used by callers that only have text. */
    fun extractFromText(text: String, letterDate: LocalDate? = null): CandidateSet =
        run(ExtractionContext.ofText(text), letterDate)

    private fun run(ctx: ExtractionContext, letterDate: LocalDate?): CandidateSet {
        for (finder in finders) ctx.drafts += finder.find(ctx)
        ctx.drafts.sortWith(compareBy({ it.line.order }, { it.start }))
        AmountEvidence.promote(ctx)
        LabelHints.apply(ctx)
        DateFinder.validate(ctx.drafts, letterDate)
        AmountEvidence.markTriples(ctx.drafts)
        return CandidateSet(CandidateIds.assign(ctx.drafts), letterDate)
    }
}
