package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.BlockKey
import com.postsaimanager.core.domain.extraction.candidates.BlockZone
import com.postsaimanager.core.domain.extraction.candidates.CandidateExtractor
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet
import com.postsaimanager.core.domain.extraction.layout.AvatarGlyph
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterLayoutAnalyzer
import com.postsaimanager.core.domain.extraction.layout.LetterZone
import com.postsaimanager.core.model.OcrBlock
import kotlin.math.abs

/** [LayoutReader] over workstream B's [LetterLayoutAnalyzer]. */
class AnalyzerLayoutReader : LayoutReader {
    override fun read(pages: List<List<OcrBlock>>): LetterLayout = LetterLayoutAnalyzer.analyze(pages)
}

/**
 * [CandidateSource] over workstream C's [CandidateExtractor]. Passes the layout's zones so name
 * candidates can be found in the address field and the letterhead; the zone is carried on the
 * candidate as a hint for the model and for the position check, not as a decision.
 */
class ExtractorCandidateSource : CandidateSource {
    override fun find(pages: List<List<OcrBlock>>, layout: LetterLayout): CandidateSet {
        // The layout was read from the pages without their avatar glyphs ([AvatarGlyph]): the candidates are found in the same text.
        val letter = AvatarGlyph.strip(pages)
        return CandidateExtractor.extract(letter, BlockZones.of(letter, layout))
    }
}

/**
 * Carries the layout's per-line zones over to the OCR blocks the candidate extractor reads.
 *
 * The analyzer splits blocks into lines and reorders them, so a block is matched to its lines by
 * text and by the slice of the block's bounds each line was given.
 */
object BlockZones {

    fun of(pages: List<List<OcrBlock>>, layout: LetterLayout): Map<BlockKey, BlockZone> {
        val out = HashMap<BlockKey, BlockZone>()
        for ((pi, blocks) in pages.withIndex()) {
            val lines = layout.page(pi + 1)?.lines.orEmpty().groupBy { it.text }
            for ((bi, block) in blocks.withIndex()) {
                val texts = com.postsaimanager.core.domain.extraction.candidates.OcrText.normalizeChars(block.text).lines().map { it.trim() }.filter { it.isNotEmpty() }
                val zones = texts.mapIndexedNotNull { j, text ->
                    val b = block.bounds
                    val top = b.top + b.height * j / texts.size
                    lines[text]
                        ?.minByOrNull { abs(it.bounds.top - top) + abs(it.bounds.left - b.left) }
                        ?.takeIf { abs(it.bounds.top - top) < TOLERANCE && abs(it.bounds.left - b.left) < TOLERANCE }
                        ?.zone
                }
                val zone = zones.mapNotNull { blockZone(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                if (zone != null) out[BlockKey(pi + 1, bi)] = zone
            }
        }
        return out
    }

    private const val TOLERANCE = 0.002f

    private fun blockZone(zone: LetterZone): BlockZone? = when (zone) {
        LetterZone.LETTERHEAD -> BlockZone.LETTERHEAD
        LetterZone.RETURN_ADDRESS_LINE -> BlockZone.RETURN_ADDRESS
        LetterZone.ADDRESS_FIELD -> BlockZone.ADDRESS_FIELD
        LetterZone.FOOTER -> BlockZone.FOOTER
        else -> null
    }
}
