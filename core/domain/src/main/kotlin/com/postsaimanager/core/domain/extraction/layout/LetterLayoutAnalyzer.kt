package com.postsaimanager.core.domain.extraction.layout

import com.postsaimanager.core.domain.extraction.candidates.AmountConsistency
import com.postsaimanager.core.domain.extraction.candidates.OcrText
import com.postsaimanager.core.domain.usecase.DocumentLayout
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import kotlin.math.abs

/**
 * Turns OCR blocks into a [LetterLayout]: per-page lines, content-based DIN 5008 zones, and
 * noise flags.
 *
 * ### Geometry and shape, never words
 * The old zoning cut the page at fixed height fractions, so the sender's letterhead and the
 * small return-address line landed in the "address block" and the prompt called them RECIPIENT.
 * Here the zones come from where lines sit and what they are shaped like: columns, vertical
 * stacks and gaps, a font-height proxy (the bbox height), digit-run shapes (a postcode, an
 * account number, an amount), separators and colons, and elements repeated across pages. A
 * return-address line is a small line with a digit run right above the address stack; the address
 * field is a stack that ends in a line with a postcode-shaped digit run; the info block is what
 * sits in the right-hand column beside it; the subject is the short line that stands apart below
 * them; a payment section is a cluster around an account-number shape or an amount triple that
 * adds up. No word in any language decides a zone, so a letter with none of the usual words
 * (or in another language or script) gets the same zones. The fixed page fractions are only a
 * fallback prior when nothing else matches.
 *
 * Pure Kotlin; no Android.
 */
object LetterLayoutAnalyzer {

    /**
     * Lines whose OCR confidence is below this are flagged [NoiseKind.LOW_CONFIDENCE].
     *
     * ML Kit reports well-read printed lines at roughly 0.8-0.99; shadows, fold marks and
     * fragments of a neighbouring page come back well under 0.5. A confidence of exactly
     * `0f` is what OcrService stores when the engine gave no confidence at all, so it means
     * "unknown" and is never flagged.
     */
    const val MIN_LINE_CONFIDENCE = 0.4f

    /** Blocks of a document whose page boundaries are known as counts (the pipeline's shape). */
    fun analyze(blocks: List<OcrBlock>, pageBlockCounts: List<Int>): LetterLayout {
        if (pageBlockCounts.isEmpty() || pageBlockCounts.sum() != blocks.size) {
            return analyze(listOf(blocks))
        }
        var from = 0
        val pages = pageBlockCounts.map { n ->
            blocks.subList(from, from + n).also { from += n }
        }
        return analyze(pages)
    }

    /** One list of blocks per page, in page order. */
    fun analyze(pages: List<List<OcrBlock>>): LetterLayout {
        val work = pages.mapIndexed { i, blocks -> toLines(blocks, i + 1) }
        for (lines in work) if (isRightToLeft(lines)) lines.forEach { it.mirror = true }
        flagNoise(work)
        work.firstOrNull()?.let(::alignCroppedTop)
        work.forEachIndexed { i, lines -> classify(lines, isFirstPage = i == 0) }
        return LetterLayout(
            work.mapIndexed { i, lines ->
                PageLayoutView(
                    i + 1,
                    lines.sortedWith(compareBy({ rank(it.zone) }, { it.order }))
                        .map { LayoutLine(it.text, it.page, it.bounds, it.confidence, it.zone, it.noise) },
                )
            },
        )
    }

    // ── Lines ──

    private class Work(
        val text: String,
        val page: Int,
        val bounds: TextBounds,
        val confidence: Float,
        val order: Int,
    ) {
        var zone: LetterZone = LetterZone.BODY
        var noise: NoiseKind? = null
        var assigned = false

        /** True on a right-to-left page: geometry is read as if the page were flipped, so the address field is on the left again. */
        var mirror = false
        /** Added to the vertical position when a scan was cropped at the top (see [alignCroppedTop]); zones read [cy], the output keeps [bounds]. */
        var shift = 0f
        val cy get() = bounds.centerY + shift
        val left get() = if (mirror) 1f - bounds.right else bounds.left
    }

    private fun toLines(blocks: List<OcrBlock>, page: Int): List<Work> {
        val out = mutableListOf<Work>()
        for (block in DocumentLayout.readingOrder(blocks)) {
            // Normalised the same way the candidate extractor reads the text (Arabic-Indic digits
            // become western digits, odd spaces become spaces), so every stage sees the same characters.
            val lines = OcrText.normalizeChars(block.text).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val n = lines.size
            val b = block.bounds
            lines.forEachIndexed { i, text ->
                val top = b.top + b.height * i / n
                val bottom = b.top + b.height * (i + 1) / n
                out += Work(text, page, TextBounds(b.left, top, b.right, bottom), block.confidence, out.size)
            }
        }
        return out
    }

    /**
     * A phone scan is often cropped to the paper, which removes the top margin a rendered page has: the letter then starts
     * at the very edge and everything in the header sits higher than the fixed page fractions below expect (measured: the
     * address block of a scanned letter ended at 0.115 of the page where the zone priors start at 0.12, so the real
     * address stack was missed and the subject and the salutation were taken as the address field). When the first line
     * starts above [CROPPED_TOP], the page's vertical positions are read as if that margin were restored to
     * [TYPICAL_TOP] (a shift of what was cropped, geometry only). Pages that start lower are read exactly as before.
     */
    private fun alignCroppedTop(lines: List<Work>) {
        val top = lines.filter { it.noise == null }.minOfOrNull { it.bounds.top } ?: return
        if (top >= CROPPED_TOP) return
        val shift = TYPICAL_TOP - top
        lines.forEach { it.shift = shift }
    }

    private fun rank(zone: LetterZone) = when (zone) {
        LetterZone.LETTERHEAD -> 0
        LetterZone.RETURN_ADDRESS_LINE -> 1
        LetterZone.ADDRESS_FIELD -> 2
        LetterZone.INFO_BLOCK -> 3
        LetterZone.SUBJECT -> 4
        LetterZone.BODY, LetterZone.PAYMENT_SECTION -> 5
        LetterZone.FOOTER -> 6
    }

    // ── Noise ──

    private fun flagNoise(pages: List<List<Work>>) {
        for (line in pages.flatten()) {
            line.noise = when {
                line.confidence > 0f && line.confidence < MIN_LINE_CONFIDENCE -> NoiseKind.LOW_CONFIDENCE
                isBarcode(line.text) -> NoiseKind.BARCODE
                isRandomTokenLine(line.text) -> NoiseKind.TOKEN
                else -> null
            }
        }
        if (pages.size < 2) return
        // Repeated running header / footer: keep the earliest copy.
        val seen = mutableMapOf<String, Int>()
        for (page in pages) {
            for (line in page) {
                if (line.noise != null) continue
                val region = when {
                    line.cy < HEADER_REGION -> "H"
                    line.cy > FOOTER_REGION -> "F"
                    else -> continue
                }
                val key = region + normalise(line.text)
                val first = seen.getOrPut(key) { line.page }
                if (first != line.page) line.noise = NoiseKind.REPEATED
            }
        }
    }

    private fun normalise(text: String) =
        text.lowercase().replace(Regex("\\d"), "#").replace(Regex("\\s+"), " ").trim()

    private fun isBarcode(text: String): Boolean {
        val compact = text.filter { !it.isWhitespace() }
        if (compact.length >= 12 && compact.all { it.isDigit() }) return true
        if (compact.length >= 6) {
            val alnum = compact.count { it.isLetterOrDigit() }
            if (alnum * 10 < compact.length * 3) return true
        }
        return false
    }

    private fun isRandomTokenLine(text: String): Boolean {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val total = words.sumOf { it.length }
        if (total == 0) return false
        val bad = words.filter { isRandomToken(it) }.sumOf { it.length }
        return bad > 0 && bad * 2 >= total
    }

    private fun isRandomToken(w: String): Boolean {
        if (IBAN_TOKEN.matches(w) || w.contains('@') || w.contains("://") || w.startsWith("www.")) return false
        val hasDigit = w.any { it.isDigit() }
        val hasLetter = w.any { it.isLetter() }
        if (w.length >= 16 && w.all { it in HEX } && hasDigit && hasLetter) return true
        if (w.length >= 20 && w.all { it.isDigit() }) return true
        if (w.length >= 20 && BASE64.matches(w) && hasDigit && hasLetter) {
            val mixedCase = w.any { it.isUpperCase() } && w.any { it.isLowerCase() }
            if (mixedCase || w.any { it == '+' || it == '/' || it == '=' }) return true
        }
        return false
    }

    // ── Zones ──

    private fun classify(all: List<Work>, isFirstPage: Boolean) {
        val clean = all.filter { it.noise == null }
        val medianHeight = median(clean.map { it.bounds.height })

        // Footer: the bottom strip, plus small print just above it (a font-height proxy: clearly
        // smaller than the page's usual line, low on the page).
        for (l in clean) {
            val smallPrint = medianHeight > 0f && l.bounds.height <= SMALL_FONT_RATIO * medianHeight
            if (l.cy > FOOTER_START || (l.cy > LOWER_REGION_START && smallPrint)) set(l, LetterZone.FOOTER)
        }
        if (isFirstPage) classifyFirstPage(clean, medianHeight) else {
            clean.filter { !it.assigned && it.cy < HEADER_REGION }.forEach { set(it, LetterZone.LETTERHEAD) }
        }
        markPayment(clean)

        for (l in all) {
            if (l.noise != null) l.zone = if (l.cy > FOOTER_START) LetterZone.FOOTER else LetterZone.BODY
        }
    }

    private fun set(l: Work, zone: LetterZone) {
        l.zone = zone
        l.assigned = true
    }

    private fun classifyFirstPage(clean: List<Work>, medianHeight: Float) {
        val pool = clean.filter { !it.assigned && it.left < LEFT_COLUMN_MAX }.sortedBy { it.cy }

        // 1. Return-address line + address field.
        var returnLine: Work? = null
        var field: List<Work> = emptyList()
        for (cand in pool.filter { isReturnAddressLine(it, medianHeight) }) {
            val below = collectDown(cand, pool)
            // A real address stack ends in a "PLZ Ort" line; a subject line that happens to look like a
            // return line has body text under it, which never does.
            if (below.size >= 2 && below.any { isPostcodeLine(it.text) }) {
                returnLine = cand
                field = below
                break
            }
        }
        if (returnLine == null) {
            field = fallbackField(pool)
            // A small line with a digit run directly above the stack is the return-address line, even
            // when OCR dropped its separators or fused the street and the postcode. So is a first line
            // that holds a postcode-like run when the stack ends in a postcode line of its own: an
            // addressee block has one, so the first line is the sender's address (font size is a weak
            // signal on a page full of small print).
            val first = field.firstOrNull()
            val secondPostcode = first != null && (DIGIT_RUN.containsMatchIn(first.text) || ALNUM_POSTCODE.containsMatchIn(first.text)) && field.drop(1).any { isPostcodeLine(it.text) }
            if (first != null && field.size >= 3 && first.text.length in 15..120 && (isSmallWithDigitRun(first, medianHeight) || secondPostcode)) {
                // the stack walk already took it in: it is the small first line of the stack
                returnLine = first
                field = field.drop(1)
            } else if (first != null) {
                returnLine = returnLineAbove(first, pool, medianHeight)
            }
        }
        var usedPrior = false
        if (field.isEmpty()) {
            field = pool.filter { it.cy in PRIOR_ADDRESS_START..PRIOR_ADDRESS_END }
            usedPrior = true
        }
        returnLine?.let { set(it, LetterZone.RETURN_ADDRESS_LINE) }
        field.forEach { set(it, LetterZone.ADDRESS_FIELD) }
        val fieldTop = (returnLine ?: field.firstOrNull())?.let { it.bounds.top + it.shift }
        val fieldBottom = field.maxOfOrNull { it.bounds.bottom + it.shift }

        // 2. Info block: whatever stands in the right-hand column beside the address field, labels and
        //    values alike (a label in one OCR block and its value in the next are both in the column).
        val yLo = if (fieldTop != null && !usedPrior) fieldTop - 0.02f else INFO_FALLBACK_START
        val inColumn = clean.filter { !it.assigned && it.left >= INFO_COLUMN_MIN && it.cy in yLo..INFO_END }
        var infoBottom: Float? = null
        if (inColumn.isNotEmpty()) {
            inColumn.forEach { set(it, LetterZone.INFO_BLOCK) }
            infoBottom = inColumn.maxOf { it.bounds.bottom + it.shift } + 0.01f
        }
        // A line that is only a date (a place and date line on the left) belongs to the info block too.
        clean.filter { !it.assigned && it.cy < INFO_END && DATE_ONLY.matches(it.text) }.forEach {
            set(it, LetterZone.INFO_BLOCK)
            infoBottom = maxOf(infoBottom ?: 0f, it.bounds.bottom + it.shift)
        }

        // 3. Letterhead: everything above the return line / address field.
        val headLimit = fieldTop ?: LETTERHEAD_PRIOR_END
        clean.filter { !it.assigned && it.cy < headLimit }.forEach { set(it, LetterZone.LETTERHEAD) }

        // 4. Subject.
        val refTop = maxOf(fieldBottom ?: LETTERHEAD_PRIOR_END, infoBottom ?: 0f)
        classifySubject(clean.filter { !it.assigned && it.cy > refTop }.sortedBy { it.cy })
    }

    /**
     * A return-address line by shape: in the upper left, one line, 15 to 120 characters, and either
     * separators around a digit run (two strong separators and a digit, or a separator, a postcode-like
     * digit run and a street number) or, when OCR dropped the separators, a clearly smaller font
     * than the page's usual line together with a digit run.
     */
    private fun isReturnAddressLine(l: Work, medianHeight: Float): Boolean {
        val t = l.text
        if (!(l.cy in RETURN_MIN_Y..RETURN_MAX_Y && l.left < RETURN_MAX_LEFT && l.bounds.width <= 0.65f && t.length in 15..120)) {
            return false
        }
        val separated = (STRONG_SEPARATOR.findAll(t).count() >= 2 && t.any { it.isDigit() }) ||
            (RETURN_SEPARATOR.containsMatchIn(t) && DIGIT_RUN.containsMatchIn(t) && STREET_NUMBER.containsMatchIn(t))
        return separated || isSmallWithDigitRun(l, medianHeight)
    }

    private fun isSmallWithDigitRun(l: Work, medianHeight: Float) =
        medianHeight > 0f && l.bounds.height <= SMALL_FONT_RATIO * medianHeight && DIGIT_RUN.containsMatchIn(l.text)

    /**
     * The single line directly above the address stack that is small (font-height proxy), has a
     * digit run and is left-aligned with the stack; null when there is none.
     */
    private fun returnLineAbove(top: Work, pool: List<Work>, medianHeight: Float): Work? {
        val above = pool.filter { it.cy < top.cy && top.cy - it.cy <= RETURN_GAP && abs(it.left - top.left) <= MAX_LEFT_DRIFT }
            .maxByOrNull { it.cy } ?: return null
        val t = above.text
        return above.takeIf { t.length in 15..120 && isSmallWithDigitRun(it, medianHeight) && !isColonLabel(t) }
    }

    /** Lines stacked under [anchor] (same left edge, small gaps) up to and including "PLZ Ort" (+ country). */
    private fun collectDown(anchor: Work, pool: List<Work>): List<Work> {
        val out = mutableListOf<Work>()
        var prev = anchor
        var sawPlz = false
        for (l in pool.filter { it !== anchor && it.cy > anchor.cy }.sortedBy { it.cy }) {
            if (out.size >= MAX_ADDRESS_LINES) break
            if (l.cy - prev.cy > MAX_LINE_GAP || abs(l.left - anchor.left) > MAX_LEFT_DRIFT) break
            if (isColonLabel(l.text)) break
            if (sawPlz) {
                if (isCountryShape(l, prev)) out += l
                break
            }
            out += l
            prev = l
            if (isPostcodeLine(l.text)) sawPlz = true
        }
        return out
    }

    /** No Rücksendeangabe: find a "PLZ Ort" line on the left and walk up over the lines stacked above it. */
    private fun fallbackField(pool: List<Work>): List<Work> {
        for (p in pool.filter { it.cy in FALLBACK_PLZ_MIN_Y..FALLBACK_PLZ_MAX_Y && isPostcodeLine(it.text) }) {
            val chain = mutableListOf(p)
            var prev = p
            for (l in pool.filter { it.cy < p.cy }.sortedByDescending { it.cy }) {
                if (chain.size >= MAX_ADDRESS_LINES) break
                if (prev.cy - l.cy > MAX_LINE_GAP || abs(l.left - p.left) > MAX_LEFT_DRIFT) break
                if (isColonLabel(l.text)) break
                // a change of font size ends the stack (letterhead above, or a heading)
                val h = p.bounds.height
                if (h > 0f && (l.bounds.height > FONT_JUMP * h || l.bounds.height < h / FONT_JUMP)) break
                chain.add(0, l)
                prev = l
            }
            if (chain.size < 2) continue
            val country = pool.firstOrNull { it.cy > p.cy && it.cy - p.cy <= MAX_LINE_GAP && abs(it.left - p.left) <= MAX_LEFT_DRIFT && isCountryShape(it, p) }
            return chain + listOfNotNull(country)
        }
        return emptyList()
    }

    /** The line after "postcode place": short, no digit, one to three words, close under it. Any language. */
    private fun isCountryShape(l: Work, prev: Work): Boolean {
        val t = l.text.trim()
        if (t.length !in 3..25 || t.any { it.isDigit() } || t.last() in ",:;.") return false
        if (t.split(Regex("\\s+")).size > 3 || !t.all { it.isLetter() || it in " -'" }) return false
        return l.cy - prev.cy <= COUNTRY_GAP
    }

    /**
     * The subject is the first short line, or two to three tightly spaced short lines, below the
     * address and info blocks that stands apart from what follows (a clear gap after it) and is
     * not shaped like a sentence (no closing full stop, `!`, `?`) or a greeting (no closing comma,
     * colon or semicolon). Pure geometry and shape: a "Betreff" word, a salutation or any other
     * word plays no part, so a letter in any language or script gets the same subject.
     */
    private fun classifySubject(cands: List<Work>) {
        for (i in cands.indices) {
            if (cands[i].cy >= SUBJECT_MAX_Y) return
            val chain = mutableListOf(cands[i])
            while (chain.size < 3) {
                val next = cands.getOrNull(i + chain.size) ?: break
                if (next.cy - chain.last().cy > pitchLimit(chain.last(), next)) break
                chain += next
            }
            val after = cands.getOrNull(i + chain.size) ?: return
            val gap = after.cy - chain.last().cy
            if (gap < ISOLATED_GAP || gap <= pitchLimit(chain.last(), after)) continue
            if (chain.any { it.text.length > 120 } || chain.last().text.trim().last() in NOT_A_SUBJECT_END) continue
            chain.forEach { set(it, LetterZone.SUBJECT) }
            return
        }
    }

    /** The largest vertical gap between two lines of one text block: 1.7 times the taller line, at least [MIN_PITCH]. */
    private fun pitchLimit(a: Work, b: Work) = maxOf(MIN_PITCH, PITCH_FACTOR * maxOf(a.bounds.height, b.bounds.height))

    // ── payment ──

    /**
     * A payment section by shape: a cluster around an account-number shape (two letters, two check
     * digits, groups) or around three currency-marked lines whose amounts add up (a + b = c within one
     * cent, on a few consecutive lines). Neighbouring lines that carry an amount or a date shape join
     * the cluster, and on a page that has such a cluster the amount lines that are not part of a long
     * list (at most three in a row) join too. No word decides; a page without an account number or a
     * consistent sum has no payment section.
     */
    private fun markPayment(clean: List<Work>) {
        val sorted = clean.sortedBy { it.cy }
        fun free(i: Int) = sorted.getOrNull(i)?.assigned == false
        val seeds = sorted.indices.filter { free(it) && PAYMENT_SHAPE.containsMatchIn(sorted[it].text) }.toMutableSet()
        seeds += sumSeeds(sorted).filter { free(it) }
        if (seeds.isEmpty()) return

        fun near(i: Int, j: Int, gap: Float) =
            free(j) && abs(sorted[j].cy - sorted[i].cy) <= gap && abs(sorted[j].left - sorted[i].left) < NEIGHBOUR_DRIFT

        // the core: the seeds and, along consecutive lines, everything that carries an amount, a date
        // or an account number
        val core = seeds.toMutableSet()
        val queue = ArrayDeque(core)
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for (j in listOf(i - 1, i + 1)) {
                if (j in core || !near(i, j, CHAIN_GAP) || !hasValueShape(sorted[j].text)) continue
                core += j
                queue += j
            }
        }
        // plus one line on each side of the core, whatever it holds: the sentence next to a due date
        // or an amount is usually the one that explains it
        val members = core.toMutableSet()
        for (i in core) for (j in listOf(i - 1, i + 1)) if (near(i, j, PAYMENT_NEIGHBOUR_GAP)) members += j
        // amount lines that stand alone or in a short run, on a page that has a payment cluster
        var i = 0
        while (i < sorted.size) {
            if (!free(i) || !hasAmountShape(sorted[i].text)) { i++; continue }
            var j = i
            while (j + 1 < sorted.size && free(j + 1) && hasAmountShape(sorted[j + 1].text)) j++
            if (j - i + 1 <= SHORT_RUN) for (k in i..j) members += k
            i = j + 1
        }
        members.forEach { set(sorted[it], LetterZone.PAYMENT_SECTION) }
    }

    /** Lines that take part in three currency amounts on consecutive lines that add up (a + b = c). */
    private fun sumSeeds(sorted: List<Work>): Set<Int> {
        val out = mutableSetOf<Int>()
        val values = sorted.map { w -> if (CURRENCY_MARK.containsMatchIn(w.text)) amountCents(w.text) else emptyList() }
        for (start in sorted.indices) {
            val window = (start until minOf(sorted.size, start + SUM_WINDOW)).filter { values[it].isNotEmpty() }
            for (a in window) for (b in window) for (c in window) {
                if (a == b || a == c || b == c) continue
                for (x in values[a]) for (y in values[b]) for (z in values[c]) {
                    if (AmountConsistency.isNetVatGross(x, y, z)) out += listOf(a, b, c)
                }
            }
        }
        return out
    }

    /** All decimal amounts of a line in cents. */
    private fun amountCents(text: String): List<Long> = AMOUNT_NUMBER.findAll(text).mapNotNull { m ->
        val whole = m.groupValues[1].filter { it.isDigit() }.toLongOrNull() ?: return@mapNotNull null
        val cents = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
        whole * 100 + cents
    }.toList()

    private fun hasAmountShape(t: String) = AMOUNT_NUMBER.containsMatchIn(t) || CURRENCY_MARK.containsMatchIn(t) && t.any { it.isDigit() }

    private fun hasValueShape(t: String) = hasAmountShape(t) || NUMERIC_DATE.containsMatchIn(t) || PAYMENT_SHAPE.containsMatchIn(t)

    // ── Patterns and thresholds ──

    private val IGNORE = RegexOption.IGNORE_CASE
    private val HEX = "0123456789abcdefABCDEF".toSet()
    private val BASE64 = Regex("[A-Za-z0-9+/=_-]+")
    private val IBAN_TOKEN = Regex("[A-Z]{2}\\d{2}[A-Z0-9]{10,30}")

    // Shapes first; the German patterns below are extra hints, never gates.
    private val DIGIT_RUN = Regex("(?<!\\d)\\d{4,6}(?!\\d)")
    private val ALNUM_POSTCODE = Regex("\\b[A-Z]{1,2}\\d[A-Z\\d]?\\s?\\d[A-Z]{2}\\b")
    private val STRONG_SEPARATOR = Regex("[·•|]|\\s[-–—]\\s")
    private val COLON_LABEL = Regex("^\\s*\\p{L}[\\p{L} .\\-/]{1,28}:(\\s.*)?$")
    /** An account number in any language: two letters, two digits, then groups. Amounts alone are not payment sections. */
    private val PAYMENT_SHAPE = Regex(
        // the check characters may carry an OCR confusion (o/O for 0, I/l for 1), but at least one is a real digit
        "(?<![A-Za-z0-9])[A-Z]{2}(?:[0-9][0-9OoIl]|[OoIl][0-9])(?:\\s?[A-Za-z0-9]{2,4}){3,}",
    )

    /** A short line holding a 4 to 6 digit run (postcode) or an alphanumeric postcode, with letters. */
    private fun isPostcodeLine(t: String) =
        t.length in 4..45 && t.any { it.isLetter() } && (DIGIT_RUN.containsMatchIn(t) || ALNUM_POSTCODE.containsMatchIn(t))

    /** More than half of the letters are in a right-to-left script. */
    private fun isRightToLeft(lines: List<Work>): Boolean {
        var rtl = 0
        var letters = 0
        for (l in lines) for (ch in l.text) if (ch.isLetter()) {
            letters++
            if (ch in '֐'..'ࣿ' || ch in 'יִ'..'﷿' || ch in 'ﹰ'..'﻿') rtl++
        }
        return letters > 0 && rtl * 2 > letters
    }
    private val STREET_NUMBER = Regex("\\p{L}[\\p{L}.\\-]*\\s*\\d{1,4}\\s?[a-zA-Z]?(?![\\d\\p{L}])")
    private val RETURN_SEPARATOR = Regex("[·•|]|\\s[-–]\\s|,")
    private val NUMERIC_DATE = Regex("(?<!\\d)(?:\\d{1,2}[./]\\s?\\d{1,2}[./]\\s?\\d{2,4}|\\d{4}-\\d{2}-\\d{2})(?!\\d)")

    /** A line that is only a date, optionally after a short label and a colon or comma (any language). */
    private val DATE_ONLY = Regex(
        "(?:\\p{L}[\\p{L} .\\-]{0,25}[:,]?\\s*)?(?:\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|" +
            "\\d{1,2}(?:st|nd|rd|th)?\\s+\\p{L}{3,10}\\.?,?\\s+\\d{4}|\\p{L}{3,10}\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4})",
        IGNORE,
    )

    /** A label of a label/value pair by shape: a short run of letters that ends in a colon. */
    private fun isColonLabel(t: String) = COLON_LABEL.matches(t)

    /** A decimal amount with two decimals, thousands separators allowed: `1.284,50`, `64,98`, `250.00`. */
    private val AMOUNT_NUMBER = Regex("(?<![\\d.,])(\\d{1,3}(?:[.,' ]\\d{3})+|\\d+)[.,](\\d{2})(?![\\d])")

    /** A currency mark: a symbol, or a three-letter ISO code in capitals. */
    private val CURRENCY_MARK = Regex("[€$£¥]|(?<![A-Za-z])[A-Z]{3}(?![A-Za-z])|يورو")

    /** A line ending like this is a sentence, a greeting or a label, not a subject. */
    private const val NOT_A_SUBJECT_END = ".!?؟,:;،"

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val s = values.sorted()
        return s[s.size / 2]
    }

    // Fractions of page height/width. Priors and tolerances, not a template.
    /** The first line of a rendered letter starts at 0.042 to 0.063 of the page (the 16 benchmark letters); a scan whose first line starts above 0.035 was cropped. */
    private const val CROPPED_TOP = 0.035f
    private const val TYPICAL_TOP = 0.05f
    private const val HEADER_REGION = 0.10f
    private const val FOOTER_REGION = 0.88f
    private const val FOOTER_START = 0.86f
    private const val LOWER_REGION_START = 0.78f
    private const val SMALL_FONT_RATIO = 0.8f
    private const val FONT_JUMP = 1.5f
    private const val RETURN_GAP = 0.05f
    private const val COUNTRY_GAP = 0.03f
    private const val MIN_PITCH = 0.02f
    private const val PITCH_FACTOR = 1.7f
    private const val NEIGHBOUR_DRIFT = 0.15f
    private const val CHAIN_GAP = 0.02f
    private const val SHORT_RUN = 3
    private const val SUM_WINDOW = 5
    private const val LEFT_COLUMN_MAX = 0.45f
    private const val INFO_COLUMN_MIN = 0.40f
    private const val INFO_END = 0.45f
    private const val INFO_FALLBACK_START = 0.12f
    private const val LETTERHEAD_PRIOR_END = 0.14f
    private const val PRIOR_ADDRESS_START = 0.14f
    private const val PRIOR_ADDRESS_END = 0.34f
    private const val RETURN_MIN_Y = 0.08f
    private const val RETURN_MAX_Y = 0.42f
    private const val RETURN_MAX_LEFT = 0.40f
    private const val FALLBACK_PLZ_MIN_Y = 0.12f
    private const val FALLBACK_PLZ_MAX_Y = 0.42f
    private const val MAX_ADDRESS_LINES = 6
    private const val MAX_LINE_GAP = 0.045f
    private const val MAX_LEFT_DRIFT = 0.06f
    private const val SUBJECT_MAX_Y = 0.60f
    private const val ISOLATED_GAP = 0.025f
    private const val PAYMENT_NEIGHBOUR_GAP = 0.035f
}
