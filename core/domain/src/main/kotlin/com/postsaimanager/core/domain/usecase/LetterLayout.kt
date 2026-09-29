package com.postsaimanager.core.domain.usecase

import com.postsaimanager.core.model.TextBounds

/**
 * What a line of a German business letter is *for*, in DIN 5008 terms.
 *
 * Assigned from the line's content first and its position second — see [LetterLayoutAnalyzer].
 * The distinction that matters most to extraction is [LETTERHEAD] and [RETURN_ADDRESS_LINE]
 * (both name the **sender**) versus [ADDRESS_FIELD] (the **addressee**): a fixed-height band
 * cannot tell them apart, which is how a sender used to be filed as the recipient.
 */
enum class LetterZone(val tag: String) {
    /** Sender's logo line, name and contact details above the address field. */
    LETTERHEAD("letterhead"),

    /** The small "Sender · Street 1 · 12345 Town" line above the window (Rücksendeangabe). Names the sender. */
    RETURN_ADDRESS_LINE("return-address-line"),

    /** The window-envelope block: addressee name(s), street, PLZ Ort. */
    ADDRESS_FIELD("address-field"),

    /** Right-hand reference block: Ihr Zeichen, Ansprechpartner, Telefon, Datum, Kundennummer. */
    INFO_BLOCK("info-block"),

    /** Subject line ("Betreff:" or the bold line before the salutation). */
    SUBJECT("subject"),

    BODY("body"),

    /** Bottom-of-page small print: management, register court, bank, page number. Evidence for the sender. */
    FOOTER("footer"),

    /** Lines about amounts, IBAN, due dates and objection rights, wherever they sit. */
    PAYMENT_SECTION("payment"),
}

/** Why a line is flagged as not real content. Flagged, never deleted: extraction may still want to see it. */
enum class NoiseKind {
    /** A long random-looking token: base64 / hex signature, serial number. */
    TOKEN,

    /** A barcode / QR digit run or symbol soup. */
    BARCODE,

    /** Same header or footer as on an earlier page; the earlier copy is kept. */
    REPEATED,

    /** OCR confidence below [LetterLayoutAnalyzer.MIN_LINE_CONFIDENCE]. */
    LOW_CONFIDENCE,
}

/**
 * One recognised text line with its page, position and classification.
 *
 * @property bounds normalised to the page (0..1) it sits on. Lines cut out of a multi-line
 *   OCR block get an equal share of the block's height.
 */
data class LayoutLine(
    val text: String,
    val page: Int,
    val bounds: TextBounds,
    val confidence: Float,
    val zone: LetterZone,
    val noise: NoiseKind? = null,
) {
    val isNoise: Boolean get() = noise != null
}

/** One page: lines in logical reading order (see [LetterLayoutAnalyzer]). Pages are never merged. */
data class PageLayoutView(
    val pageNumber: Int,
    val lines: List<LayoutLine>,
) {
    fun zone(zone: LetterZone): List<LayoutLine> = lines.filter { it.zone == zone && !it.isNoise }

    /** Text of the non-noise lines in [zone], one per line. */
    fun zoneText(zone: LetterZone): List<String> = zone(zone).map { it.text }
}

/** A part of the letter that [LetterLayout.describe] left out to stay within the budget. */
data class OmittedPart(val page: Int, val zone: LetterZone, val lines: Int, val chars: Int)

/**
 * The result of [LetterLayout.describe].
 *
 * @property text the prompt-ready description.
 * @property omitted what did not fit, aggregated per page and zone, in page order.
 * @property ignoredNoiseLines lines flagged as noise and therefore not printed.
 * @property pagesRead pages of which at least one line was printed.
 */
data class LayoutDescription(
    val text: String,
    val omitted: List<OmittedPart>,
    val ignoredNoiseLines: Int,
    val pagesRead: Int,
    val totalPages: Int,
) {
    val isComplete: Boolean get() = omitted.isEmpty()

    /** "page 3: body (14 lines); page 2: footer (3 lines)" for the partial-read notice, or null when nothing was omitted. */
    fun omittedSummary(): String? =
        if (omitted.isEmpty()) {
            null
        } else {
            omitted.joinToString("; ") { "page ${it.page}: ${it.zone.tag} (${it.lines} lines)" }
        }
}

/**
 * A letter as structured zones per page instead of one flat, interleaved list of blocks.
 *
 * Each page is normalised 0..1 on its own, so lines from different pages must never be sorted
 * together; this type keeps them apart by construction.
 */
data class LetterLayout(val pages: List<PageLayoutView>) {

    val allLines: List<LayoutLine> get() = pages.flatMap { it.lines }

    fun page(number: Int): PageLayoutView? = pages.firstOrNull { it.pageNumber == number }

    /** Non-noise lines of [zone] on one page (default the first). */
    fun zone(zone: LetterZone, page: Int = 1): List<LayoutLine> = page(page)?.zone(zone).orEmpty()

    /** Non-noise text, page by page, no tags: for grounding checks and chunking. */
    fun plainText(): String =
        pages.joinToString("\n") { p -> p.lines.filter { !it.isNoise }.joinToString("\n") { it.text } }

    /**
     * The letter as the model should see it, within [budgetChars].
     *
     * Format: a `=== PAGE n ===` header per page, then zone-tagged runs such as
     * `[address-field] Frau / Erika Mustermann / Musterstraße 12 / 54321 Beispielort`.
     * Lines on one row (a label and its value) are joined with a space. Noise lines are not printed.
     *
     * Priority when the budget is short (each tier keeps document order):
     * 1. page-1 letterhead, return-address line, address field, info block, subject;
     * 2. payment sections on every page;
     * 3. footers (sender evidence);
     * 4. body of the last page;
     * 5. everything else.
     * A run that does not fit is skipped whole (smaller ones after it may still fit) and reported in
     * [LayoutDescription.omitted].
     */
    fun describe(budgetChars: Int = Int.MAX_VALUE): LayoutDescription {
        val runs = buildRuns()
        val lastPage = pages.lastOrNull()?.pageNumber ?: 0
        fun tier(r: Run): Int = when {
            r.page == 1 && r.zone in HEAD_ZONES -> 1
            r.zone == LetterZone.PAYMENT_SECTION -> 2
            r.zone == LetterZone.FOOTER -> 3
            r.zone == LetterZone.BODY && r.page == lastPage -> 4
            else -> 5
        }

        val chosen = mutableSetOf<Run>()
        val openPages = mutableSetOf<Int>()
        var used = 0
        val omitted = mutableListOf<Run>()
        for (run in runs.sortedWith(compareBy({ tier(it) }, { it.page }, { it.index }))) {
            val header = if (run.page in openPages) 0 else pageHeader(run.page).length + 1
            val cost = header + run.text.length + 1
            if (used + cost <= budgetChars) {
                used += cost
                chosen += run
                openPages += run.page
            } else {
                omitted += run
            }
        }

        val out = mutableListOf<String>()
        for (page in pages) {
            val mine = runs.filter { it.page == page.pageNumber && it in chosen }
            if (mine.isEmpty()) continue
            out += pageHeader(page.pageNumber)
            mine.forEach { out += it.text }
        }

        val omittedParts = omitted
            .groupBy { it.page to it.zone }
            .map { (key, rs) -> OmittedPart(key.first, key.second, rs.sumOf { it.lineCount }, rs.sumOf { it.text.length }) }
            .sortedWith(compareBy({ it.page }, { it.zone.ordinal }))

        return LayoutDescription(
            text = out.joinToString("\n"),
            omitted = omittedParts,
            ignoredNoiseLines = allLines.count { it.isNoise },
            pagesRead = openPages.size,
            totalPages = pages.size,
        )
    }

    private class Run(val page: Int, val index: Int, val zone: LetterZone, val text: String, val lineCount: Int)

    private fun pageHeader(n: Int) = "=== PAGE $n ==="

    private fun buildRuns(): List<Run> {
        val runs = mutableListOf<Run>()
        for (page in pages) {
            val lines = page.lines.filter { !it.isNoise }
            var i = 0
            var index = 0
            while (i < lines.size) {
                val zone = lines[i].zone
                val items = mutableListOf<StringBuilder>()
                var count = 0
                var prev: LayoutLine? = null
                while (i < lines.size && lines[i].zone == zone && count < MAX_RUN_LINES) {
                    val l = lines[i]
                    if (zone == LetterZone.INFO_BLOCK && prev != null && sameRow(prev, l)) {
                        items.last().append(' ').append(l.text.trim())
                    } else {
                        items += StringBuilder(l.text.trim())
                    }
                    count++
                    prev = l
                    i++
                }
                runs += Run(
                    page.pageNumber, index++, zone,
                    "[${zone.tag}] " + items.joinToString(" / "), items.size,
                )
            }
        }
        return runs
    }

    private fun sameRow(a: LayoutLine, b: LayoutLine): Boolean {
        val tol = maxOf(0.008f, 0.5f * minOf(a.bounds.height, b.bounds.height))
        return kotlin.math.abs(a.bounds.centerY - b.bounds.centerY) <= tol && b.bounds.left > a.bounds.left + 0.05f
    }

    private companion object {
        const val MAX_RUN_LINES = 8
        val HEAD_ZONES = setOf(
            LetterZone.LETTERHEAD,
            LetterZone.RETURN_ADDRESS_LINE,
            LetterZone.ADDRESS_FIELD,
            LetterZone.INFO_BLOCK,
            LetterZone.SUBJECT,
        )
    }
}
