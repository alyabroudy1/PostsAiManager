package com.postsaimanager.core.domain.extraction.candidates

import com.postsaimanager.core.model.OcrBlock

/** One non-empty text line of an OCR block, with the block it came from and the layout zone of that block. */
internal class SourceLine(
    val page: Int,
    val blockIndex: Int,
    /** Position in [ExtractionContext.lines] (reading order over all pages). */
    val order: Int,
    val text: String,
    val block: OcrBlock?,
    val zone: BlockZone?,
    val nextInBlock: Boolean, // there is another line after this one in the same block
    /** A machine-shaped line (signature, hash, barcode digits): no finder looks at it. */
    val noise: Boolean,
)

/** A candidate before its id is assigned, with the span of [line] it was read from. */
internal class Draft(var c: Candidate, val line: SourceLine, val start: Int, val end: Int)

/** The character ranges of one line that a finder has already claimed. */
internal class Mask {
    private val r = ArrayList<IntRange>()
    fun free(range: IntRange) = r.none { it.first <= range.last && range.first <= it.last }
    fun add(range: IntRange) {
        r.add(range)
    }
}

/**
 * One kind of candidate. A finder reads the shared [ExtractionContext] and returns the drafts it found,
 * in the order it found them; the orchestrator ([CandidateExtractor]) runs the finders in a fixed order,
 * which decides how ties are broken and so which id a candidate gets.
 */
internal interface CandidateFinder {
    fun find(ctx: ExtractionContext): List<Draft>
}

/**
 * What every finder shares: the text lines of the document in reading order, the OCR pages they came
 * from (for geometry), the layout zone per block, which lines are noise, the spans each finder has
 * already claimed on a line, and the drafts found so far.
 */
internal class ExtractionContext private constructor(
    val pages: List<List<OcrBlock>>,
    val lines: List<SourceLine>,
    /** True for the plain-text entry point: no geometry, every following line continues the previous one. */
    private val plainText: Boolean,
) {
    /** Everything found so far, in the order the finders returned it. */
    val drafts = ArrayList<Draft>()

    /** The lines the finders look at (noise lines are left out). */
    val activeLines: List<SourceLine> = lines.filterNot { it.noise }

    private val masks = HashMap<SourceLine, Mask>()

    /** BIC-shaped tokens without a label, resolved once every IBAN is known (see [ShapeBicFinder]). */
    val bicShapes = ArrayList<Pair<SourceLine, MatchResult>>()

    fun mask(line: SourceLine): Mask = masks.getOrPut(line) { Mask() }

    /** A draft for [range] of [line]; the caller decides when to record it. */
    fun draft(
        line: SourceLine,
        range: IntRange,
        kind: CandidateKind,
        raw: String,
        normalized: String,
        evidence: String = line.text,
        label: String = "",
        labelKind: LabelKind? = null,
        subtype: ReferenceSubtype? = null,
        validation: Validation = Validation.Unchecked,
        attrs: Map<String, String> = emptyMap(),
    ): Draft {
        val c = Candidate(
            id = "",
            kind = kind,
            raw = raw,
            normalized = normalized,
            page = line.page,
            bbox = line.block?.bounds,
            evidence = evidence,
            label = label,
            labelKind = labelKind,
            subtype = subtype,
            validation = validation,
            attrs = attrs,
        )
        return Draft(c, line, range.first, range.last + 1)
    }

    /** The text of the block beside [line] on its row (left or right), or null. */
    fun rowNeighbour(line: SourceLine, left: Boolean): String? {
        val b = line.block ?: return null
        val blocks = pages.getOrNull(line.page - 1) ?: return null
        var best: OcrBlock? = null
        for ((i, o) in blocks.withIndex()) {
            if (i == line.blockIndex) continue
            val ob = o.bounds
            val overlap = minOf(b.bounds.bottom, ob.bottom) - maxOf(b.bounds.top, ob.top)
            val minH = minOf(b.bounds.height, ob.height)
            if (minH <= 0f || overlap < 0.5f * minH) continue
            if (left) {
                if (ob.right > b.bounds.left + 0.01f || b.bounds.left - ob.right > 0.6f) continue
                if (best == null || ob.right > best.bounds.right) best = o
            } else {
                if (ob.left < b.bounds.right - 0.01f || ob.left - b.bounds.right > 0.6f) continue
                if (best == null || ob.left < best.bounds.left) best = o
            }
        }
        return best?.let { OcrText.normalizeChars(it.text).replace('\n', ' ').trim() }
            ?.takeIf { it.isNotEmpty() && !NoiseFilter.isNoiseLine(it) }
    }

    /** The line that follows [line] when it is really its continuation (same block, or right below). */
    fun continuation(line: SourceLine): SourceLine? {
        val next = lines.getOrNull(line.order + 1)
        if (next == null || next.page != line.page) return null
        if (line.nextInBlock && next.blockIndex == line.blockIndex) return next
        val a = line.block?.bounds
        val b = next.block?.bounds
        if (a == null || b == null) return if (plainText) next else null
        return if (b.top - a.bottom in -0.01f..0.04f && kotlin.math.abs(b.left - a.left) < 0.15f) next else null
    }

    companion object {
        fun ofPages(pages: List<List<OcrBlock>>, zones: Map<BlockKey, BlockZone>): ExtractionContext {
            val lines = ArrayList<SourceLine>()
            var order = 0
            for ((pi, blocks) in pages.withIndex()) {
                for ((bi, block) in blocks.withIndex()) {
                    val zone = zones[BlockKey(pi + 1, bi)]
                    val texts = OcrText.normalizeChars(block.text).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    texts.forEachIndexed { li, t ->
                        lines.add(SourceLine(pi + 1, bi, order++, t, block, zone, li < texts.size - 1, NoiseFilter.isNoiseLine(t)))
                    }
                }
            }
            return ExtractionContext(pages, lines, plainText = false)
        }

        fun ofText(text: String): ExtractionContext {
            val texts = text.split('\n').map { OcrText.normalizeChars(it).trim() }.filter { it.isNotEmpty() }
            val lines = texts.mapIndexed { i, t -> SourceLine(1, i, i, t, null, null, false, NoiseFilter.isNoiseLine(t)) }
            return ExtractionContext(emptyList(), lines, plainText = true)
        }
    }
}

/** Trims and collapses runs of white space. */
internal fun collapseSpaces(s: String): String = s.trim().replace(Regex("\\s+"), " ")
