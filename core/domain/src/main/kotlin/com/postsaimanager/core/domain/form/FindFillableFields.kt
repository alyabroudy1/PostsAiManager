package com.postsaimanager.core.domain.form

import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.v2.QuoteVerifier
import com.postsaimanager.core.model.FormFieldKind
import com.postsaimanager.core.model.OcrBlock

/**
 * The candidate generator of form assist: finds the blanks of a scanned form from the stored OCR layout, by geometry and shape
 * only (no word is read, so any language works). The cues are fill runs (underscores, dots), box glyphs and their option groups,
 * empty table cells, a label followed by empty space, and an empty line under a label; see [FormPageScanner].
 *
 * Each candidate carries its label and fill boxes, a kind hint, the section (the nearest heading-like line above it: a line set
 * larger than the page's text; a section with no heading on this page carries over from the earlier page, see
 * [FieldCandidate.continuesSection]), its quote-verified options, the text already written in it, and its order (page, then
 * reading position). Whether a weak candidate is a field at all is for [ConfirmFields]; what it asks for is for [ClassifyFields].
 */
class FindFillableFields {

    /** The OCR blocks of each page, in page order. */
    fun find(pages: List<List<OcrBlock>>): List<FieldCandidate> = findInLines(pageLines(pages))

    /** The page layout lines of each page (see [pageLines]). */
    fun findInLines(pages: List<List<LayoutLine>>): List<FieldCandidate> {
        val all = ArrayList<FieldCandidate>()
        var carried: Pair<String, Int>? = null
        val scanned = pages.mapIndexed { i, lines ->
            val page = FormPage.build(i + 1, lines)
            val scanner = FormPageScanner(page)
            Triple(page, scanner, scanner.scan())
        }
        val repeated = repeatedLabels(scanned.map { (_, _, raws) -> raws })
        scanned.forEach { (page, scanner, scan) ->
            val raws = scan.filterNot { !it.evidence.strong && repeated(it) }.sortedWith(compareBy({ it.rowIndex }, { it.uLeft }))
            val headings = scanner.headings()
            for (raw in raws) {
                val heading = headings.lastOrNull { it.first < raw.rowIndex }?.let { it.second to page.number } ?: carried
                toCandidate(raw, page, heading)?.let { all += it }
            }
            carried = headings.lastOrNull()?.let { it.second to page.number } ?: carried
        }
        return all.mapIndexed { i, c -> c.copy(orderIndex = i) }
    }

    /**
     * A weak blank whose text stands in the same place on another page is a repeated header or footer (a letterhead, an address
     * line), not a field: a field of one form is not printed twice at one height.
     */
    private fun repeatedLabels(perPage: List<List<RawField>>): (RawField) -> Boolean {
        val seen = perPage.map { raws -> raws.filter { !it.evidence.strong }.mapNotNull { r -> r.labelBox?.let { norm(r.label) to it.top } } }
        return { raw ->
            val top = raw.labelBox?.top
            top != null && perPage.indices.count { p ->
                seen[p].any { (label, y) -> label == norm(raw.label) && kotlin.math.abs(y - top) <= REPEAT_TOLERANCE }
            } >= 2
        }
    }

    private fun norm(label: String) = label.lowercase().filter { it.isLetter() }

    private fun toCandidate(raw: RawField, page: FormPage, heading: Pair<String, Int>?): FieldCandidate? {
        val options = raw.options.filter { QuoteVerifier.verify(it, page.text) != null }
        var kind = raw.kind
        var label = raw.label
        if (kind == FormFieldKind.CHOICE && options.size < 2) {
            // A group that did not survive verification is not a choice: it is a checkbox about what is left.
            val only = options.firstOrNull() ?: return null
            kind = FormFieldKind.CHECKBOX
            label = only
        }
        return FieldCandidate(
            page = page.number,
            labelText = label,
            labelBox = raw.labelBox,
            fillBox = raw.fillBox,
            kind = kind,
            evidence = raw.evidence,
            section = heading?.first,
            sectionPage = heading?.second,
            options = if (kind == FormFieldKind.CHOICE) options else emptyList(),
            alreadyFilled = raw.alreadyFilled,
        )
    }

    companion object {
        private const val REPEAT_TOLERANCE = 0.03f

        /** The layout lines of each page (the analyzer's normalised, split lines), noise flags kept but not applied. */
        fun pageLines(pages: List<List<OcrBlock>>): List<List<LayoutLine>> =
            LetterLayoutAnalyzer.analyze(pages).pages.map { it.lines }
    }
}
