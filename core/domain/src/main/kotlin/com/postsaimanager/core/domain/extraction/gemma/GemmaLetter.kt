package com.postsaimanager.core.domain.extraction.gemma

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.LabelValuePair
import com.postsaimanager.core.domain.extraction.layout.LayoutLine
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.v2.OfferedCandidates

/**
 * One line of the letter as the reader is shown it: a stable id (`L1`, `L2`, ... in reading order, across the pages), where the layout
 * put it ([zone], the position on its page) and its text. The zone and the position are context for the model, never a rule.
 *
 * @property tags what ML Kit's entity extraction says about the line (`address`): context as well
 * @property isLabel the layout says this line is the label of a label/value pair ("Ansprechpartnerin" over a name): never a party itself
 * @property valueLineId the line right below a label that is its value (the party the label announces), when the letter has one
 */
data class GemmaLine(
    val id: String,
    val page: Int,
    val zone: String,
    val x: Float,
    val y: Float,
    val text: String,
    val tags: List<String> = emptyList(),
    val isLabel: Boolean = false,
    val valueLineId: String? = null,
)

/**
 * One value the reader may choose, with the line it was found on. The id is the candidate's own id (`D3`, `A1`, `M2`, `KD1` for a value
 * only ML Kit found), so the answer maps straight back onto the candidate the pipeline holds.
 */
data class GemmaCandidate(
    val id: String,
    val kind: CandidateKind,
    val raw: String,
    val normalized: String,
    val label: String,
    val lineId: String?,
)

/** The letter as the reader sees it: its lines and the candidates it may choose from, each with an id. */
class GemmaLetter(val lines: List<GemmaLine>, val candidates: List<GemmaCandidate>) {

    private val candidatesById = candidates.associateBy { it.id }
    private val linesById = lines.associateBy { it.id }

    fun candidate(id: String): GemmaCandidate? = candidatesById[id]

    fun line(id: String): GemmaLine? = linesById[id]

    fun candidatesOf(vararg kinds: CandidateKind): List<GemmaCandidate> = candidates.filter { it.kind in kinds }

    /** True when the letter has no text lines at all (an Arabic page the OCR could not read): the reader has only the picture. */
    val isImageOnly: Boolean get() = lines.isEmpty()
}

/**
 * Builds the [GemmaLetter]: the layout's lines with ids and the offered candidates linked to their lines.
 *
 * Lines are capped ([MAX_LINES], [MAX_LINE_CHARS]) so the prompt fits the window with the picture; a candidate whose line was cut is
 * still offered, only without a line id. Noise lines (barcodes, repeated headers) are left out, as everywhere else.
 */
object GemmaLetterBuilder {

    const val MAX_LINES = 140
    const val MAX_LINE_CHARS = 120

    /** The tag [GemmaLine.tags] carries for a line ML Kit found an address in. */
    const val TAG_ADDRESS = "address"

    /** The layout's lines the reader and the entity annotator share: non-noise, in reading order, page after page. */
    fun linesOf(layout: LetterLayout): List<LayoutLine> = layout.allLines.filterNot { it.isNoise }

    /**
     * @param addressLines indexes into [linesOf] of the lines ML Kit's entity extraction says hold an address
     * @param labelPairs the label/value pairs the layout found ([com.postsaimanager.core.domain.extraction.candidates.LabelValuePairs])
     */
    fun build(
        layout: LetterLayout,
        offered: OfferedCandidates,
        addressLines: Set<Int> = emptySet(),
        labelPairs: List<LabelValuePair> = emptyList(),
    ): GemmaLetter {
        val source = linesOf(layout)
        val plain = source.take(MAX_LINES).mapIndexed { i, l ->
            GemmaLine(
                id = "L${i + 1}",
                page = l.page,
                zone = l.zone.tag,
                x = l.bounds.left,
                y = l.bounds.top,
                text = l.text.replace('\n', ' ').trim().take(MAX_LINE_CHARS),
                tags = if (i in addressLines) listOf(TAG_ADDRESS) else emptyList(),
            )
        }
        val lines = plain.mapIndexed { i, line ->
            val pair = labelPairs.firstOrNull { it.page == line.page && same(it.label, line.text) } ?: return@mapIndexed line
            val value = pair.value?.let { v -> plain.drop(i + 1).firstOrNull { it.page == line.page && same(v, it.text) }?.id }
            line.copy(isLabel = true, valueLineId = value)
        }
        val candidates = offered.rows.map { row ->
            val c = row.candidate
            GemmaCandidate(c.id, c.kind, c.raw.trim(), c.normalized, c.label.ifBlank { row.nearLabels.firstOrNull().orEmpty() }, lineIdOf(c, source, lines.size))
        }
        return GemmaLetter(lines, candidates)
    }

    private fun same(a: String, b: String) = a.trim().replace(WHITE, " ") == b.trim().replace(WHITE, " ")

    private val WHITE = Regex("\\s+")

    /**
     * The line [c] was found on: on its page, the nearest line (to the candidate's box) whose text holds the printed value, or else its
     * source line's text. Null when that line is not among the first [kept] lines.
     */
    private fun lineIdOf(c: Candidate, source: List<LayoutLine>, kept: Int): String? {
        fun best(match: (LayoutLine) -> Boolean): Int? = source.withIndex()
            .filter { (_, l) -> l.page == c.page && match(l) }
            .minByOrNull { (_, l) -> c.bbox?.let { b -> kotlin.math.abs(l.bounds.top - b.top) + kotlin.math.abs(l.bounds.left - b.left) } ?: 0f }
            ?.index
        val raw = c.raw.trim()
        val at = (if (raw.isNotEmpty()) best { raw in it.text } else null) ?: best { it.text.isNotBlank() && it.text in c.evidence }
        return at?.takeIf { it < kept }?.let { "L${it + 1}" }
    }
}
