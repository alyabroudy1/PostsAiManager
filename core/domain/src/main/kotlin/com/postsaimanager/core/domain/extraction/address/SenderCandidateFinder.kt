package com.postsaimanager.core.domain.extraction.address

import com.postsaimanager.core.domain.extraction.layout.AddressShapes.STRONG_SEPARATOR
import com.postsaimanager.core.domain.extraction.layout.LetterLayout
import com.postsaimanager.core.domain.extraction.layout.LetterZone

/** The lines of a page-1 place where a sender's address may be printed, before any reading. */
class SenderLines(val source: SenderSource, val lines: List<AddressLine>)

/**
 * Finds where a letter prints its sender's address, by geometry and shape alone (0 scores):
 * - the **return-address line** is one small line; it is cut at its separators (a middle dot, a bar, a spaced dash, else commas), so
 *   each piece is a line;
 * - the **letterhead** and the **footer** hold a stack of left-aligned lines that ends in a "postcode place" line, found with the
 *   formats' postcode shapes (so a phone number is not taken for one).
 * A place without such a line is not an address and gives no candidate.
 */
class SenderCandidateFinder(
    private val formats: AddressFormatRegistry = AddressFormats.default,
) {

    fun find(layout: LetterLayout): List<SenderLines> = buildList {
        for (source in SenderSource.entries) {
            val lines = when (source) {
                SenderSource.RETURN_ADDRESS_LINE -> returnLine(layout)
                SenderSource.LETTERHEAD -> stack(layout, LetterZone.LETTERHEAD)
                SenderSource.FOOTER -> stack(layout, LetterZone.FOOTER)
            }
            if (lines.any { formats.byPostcodeShape(it.text).isNotEmpty() }) add(SenderLines(source, lines))
        }
    }

    private fun returnLine(layout: LetterLayout): List<AddressLine> =
        AddressLines.of(layout.zone(LetterZone.RETURN_ADDRESS_LINE)).flatMap { line ->
            cut(line.text).map { AddressLine(it, line.page, line.bounds) }
        }

    /** The pieces of a return-address line: at strong separators, else at commas. */
    private fun cut(text: String): List<String> {
        val pieces = text.split(STRONG_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (pieces.size > 1) return pieces
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The stack around the lowest "postcode place" line of [zone] on page 1. */
    private fun stack(layout: LetterLayout, zone: LetterZone): List<AddressLine> {
        val lines = AddressLines.splitCombined(AddressLines.of(layout.zone(zone)), formats)
        val anchor = lines.indexOfLast { formats.byPostcodeShape(it.text).isNotEmpty() }
        return if (anchor < 0) emptyList() else AddressLines.stackEndingAt(lines, anchor)
    }
}
