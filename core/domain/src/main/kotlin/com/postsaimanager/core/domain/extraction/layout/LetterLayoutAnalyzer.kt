package com.postsaimanager.core.domain.extraction.layout

import com.postsaimanager.core.domain.extraction.candidates.OcrText
import com.postsaimanager.core.domain.usecase.DocumentLayout
import com.postsaimanager.core.model.OcrBlock
import com.postsaimanager.core.model.TextBounds
import kotlin.math.abs

/**
 * Turns OCR blocks into a [LetterLayout]: per-page lines, content-based DIN 5008 zones, and
 * noise flags.
 *
 * ### Why content first
 * The old zoning cut the page at fixed height fractions, so the sender's letterhead and the
 * small Rücksendeangabe landed in the "address block" and the prompt called them RECIPIENT.
 * Here the zones are found from what the lines *say* (a Rücksendeangabe has separators, a
 * street and a PLZ; an address field ends in "PLZ Ort"; an info block has labels such as
 * "Ihr Zeichen") and the fixed fractions are only a fallback prior when nothing matches.
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
        val cy get() = bounds.centerY
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

        for (l in clean) {
            if (l.cy > FOOTER_START || (l.cy > FOOTER_KEYWORD_START && FOOTER_KEYWORDS.containsMatchIn(l.text))) {
                set(l, LetterZone.FOOTER)
            }
        }
        if (isFirstPage) classifyFirstPage(clean) else {
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

    private fun classifyFirstPage(clean: List<Work>) {
        val pool = clean.filter { !it.assigned && it.left < LEFT_COLUMN_MAX }.sortedBy { it.cy }

        // 1. Rücksendeangabe + address field.
        var returnLine: Work? = null
        var field: List<Work> = emptyList()
        for (cand in pool.filter { isReturnAddressLine(it) }) {
            val below = collectDown(cand, pool)
            if (below.size >= 2) {
                returnLine = cand
                field = below
                break
            }
        }
        if (returnLine == null) {
            field = fallbackField(pool)
        }
        var usedPrior = false
        if (field.isEmpty()) {
            field = pool.filter { it.cy in PRIOR_ADDRESS_START..PRIOR_ADDRESS_END }
            usedPrior = true
        }
        returnLine?.let { set(it, LetterZone.RETURN_ADDRESS_LINE) }
        field.forEach { set(it, LetterZone.ADDRESS_FIELD) }
        val fieldTop = (returnLine ?: field.firstOrNull())?.bounds?.top
        val fieldBottom = field.maxOfOrNull { it.bounds.bottom }

        // 2. Info block: right-hand labelled lines.
        val yLo = if (fieldTop != null && !usedPrior) fieldTop - 0.02f else INFO_FALLBACK_START
        val candidates = clean.filter { !it.assigned && it.left >= INFO_COLUMN_MIN }
        val labels = candidates.filter { it.cy in yLo..INFO_END && isInfoLabel(it.text) }
        var infoBottom: Float? = null
        if (labels.isNotEmpty()) {
            val top = labels.minOf { it.bounds.top } - 0.01f
            val bottom = labels.maxOf { it.bounds.bottom } + 0.01f
            candidates.filter { it.cy in top..bottom }.forEach { set(it, LetterZone.INFO_BLOCK) }
            infoBottom = bottom
        }
        clean.filter { !it.assigned && it.cy < INFO_END && DATE_ONLY.matches(it.text) }.forEach {
            set(it, LetterZone.INFO_BLOCK)
            infoBottom = maxOf(infoBottom ?: 0f, it.bounds.bottom)
        }
        if (usedPrior && labels.isEmpty()) {
            clean.filter { !it.assigned && it.left >= LEFT_COLUMN_MAX && it.cy in PRIOR_ADDRESS_START..PRIOR_ADDRESS_END }
                .forEach { set(it, LetterZone.INFO_BLOCK) }
        }

        // 3. Letterhead: everything above the return line / address field.
        val headLimit = fieldTop ?: LETTERHEAD_PRIOR_END
        clean.filter { !it.assigned && it.cy < headLimit }.forEach { set(it, LetterZone.LETTERHEAD) }

        // 4. Subject.
        val refTop = maxOf(fieldBottom ?: LETTERHEAD_PRIOR_END, infoBottom ?: 0f)
        classifySubject(clean.filter { !it.assigned && it.cy > refTop }.sortedBy { it.cy })
    }

    private fun isReturnAddressLine(l: Work): Boolean {
        val t = l.text
        return l.cy in RETURN_MIN_Y..RETURN_MAX_Y &&
            l.left < RETURN_MAX_LEFT &&
            l.bounds.width <= 0.65f &&
            t.length in 15..120 &&
            // two strong separators and a digit, or a separator, a digit run and a street number
            ((STRONG_SEPARATOR.findAll(t).count() >= 2 && t.any { it.isDigit() }) ||
                (RETURN_SEPARATOR.containsMatchIn(t) && DIGIT_RUN.containsMatchIn(t) && STREET_NUMBER.containsMatchIn(t)))
    }

    /** Lines stacked under [anchor] (same left edge, small gaps) up to and including "PLZ Ort" (+ country). */
    private fun collectDown(anchor: Work, pool: List<Work>): List<Work> {
        val out = mutableListOf<Work>()
        var prev = anchor
        var sawPlz = false
        for (l in pool.filter { it !== anchor && it.cy > anchor.cy }.sortedBy { it.cy }) {
            if (out.size >= MAX_ADDRESS_LINES) break
            if (l.cy - prev.cy > MAX_LINE_GAP || abs(l.left - anchor.left) > MAX_LEFT_DRIFT) break
            if (isInfoLabel(l.text) || SALUTATION_LETTER.containsMatchIn(l.text)) break
            if (sawPlz) {
                if (COUNTRY.matches(l.text)) out += l
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
                if (isInfoLabel(l.text)) break
                chain.add(0, l)
                prev = l
            }
            val start = chain.indexOfFirst { ADDRESS_SALUTATION.containsMatchIn(it.text) }
            val trimmed = if (start > 0) chain.drop(start) else chain
            if (trimmed.size < 2) continue
            val country = pool.firstOrNull {
                it.cy > p.cy && it.cy - p.cy <= MAX_LINE_GAP && abs(it.left - p.left) <= MAX_LEFT_DRIFT && COUNTRY.matches(it.text)
            }
            return trimmed + listOfNotNull(country)
        }
        return emptyList()
    }

    private fun classifySubject(cands: List<Work>) {
        val betreff = cands.firstOrNull { it.cy < SUBJECT_MAX_Y && BETREFF.containsMatchIn(it.text) }
        if (betreff != null) {
            set(betreff, LetterZone.SUBJECT)
            var prev: Work = betreff
            val betreffY = betreff.cy
            for (l in cands.filter { it.cy > betreffY }.take(2)) {
                if (l.cy - prev.cy > SUBJECT_LINE_GAP || SALUTATION_LETTER.containsMatchIn(l.text)) break
                set(l, LetterZone.SUBJECT)
                prev = l
            }
            return
        }
        val salutation = cands.firstOrNull { it.cy < SUBJECT_MAX_Y + 0.15f && SALUTATION_LETTER.containsMatchIn(it.text) }
            ?: return isolatedSubject(cands)
        val before = cands.filter { it.cy < salutation.cy }
        val last = before.lastOrNull() ?: return
        if (salutation.cy - last.cy > SALUTATION_GAP) return
        val chain = mutableListOf(last)
        for (l in before.dropLast(1).asReversed()) {
            if (chain.size >= 3 || chain.first().cy - l.cy > SUBJECT_LINE_GAP) break
            chain.add(0, l)
        }
        if (chain.any { it.text.length > 120 }) return
        chain.forEach { set(it, LetterZone.SUBJECT) }
    }

    /**
     * No "Betreff" and no salutation found: the subject is the first line below the address and
     * info blocks that stands apart (a clear gap after it), is short and is not a sentence.
     */
    private fun isolatedSubject(cands: List<Work>) {
        for ((i, l) in cands.withIndex()) {
            if (l.cy >= SUBJECT_MAX_Y) return
            val next = cands.getOrNull(i + 1) ?: return
            val t = l.text.trim()
            if (next.cy - l.cy < ISOLATED_GAP || t.length > 120 || t.last() in ".!?؟") continue
            set(l, LetterZone.SUBJECT)
            return
        }
    }

    private fun markPayment(clean: List<Work>) {
        val sorted = clean.sortedBy { it.cy }
        val hits = sorted.indices.filter { !sorted[it].assigned && (PAYMENT.containsMatchIn(sorted[it].text) || PAYMENT_SHAPE.containsMatchIn(sorted[it].text)) }.toMutableSet()
        val withNeighbours = hits.toMutableSet()
        for (i in hits) {
            for (j in listOf(i - 1, i + 1)) {
                val n = sorted.getOrNull(j) ?: continue
                if (!n.assigned && abs(n.cy - sorted[i].cy) <= PAYMENT_NEIGHBOUR_GAP && abs(n.left - sorted[i].left) < 0.15f) {
                    withNeighbours += j
                }
            }
        }
        withNeighbours.forEach { set(sorted[it], LetterZone.PAYMENT_SECTION) }
    }

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
    private val PAYMENT_SHAPE = Regex("(?<![A-Za-z0-9])[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{2,4}){3,}")

    /** A short line holding a 4 to 6 digit run (postcode) or an alphanumeric postcode, with letters. */
    private fun isPostcodeLine(t: String) =
        t.length in 4..45 && t.any { it.isLetter() } && (DIGIT_RUN.containsMatchIn(t) || ALNUM_POSTCODE.containsMatchIn(t))

    private fun isInfoLabel(t: String) = INFO_LABEL.containsMatchIn(t) || COLON_LABEL.matches(t)

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
    private val COUNTRY = Regex("(deutschland|germany|österreich|austria|schweiz|switzerland|luxemburg|niederlande)", IGNORE)

    private val ADDRESS_SALUTATION = Regex(
        "^(herrn?|frau|familie|eheleute|firma|z\\.?\\s?hd\\.?|zu händen|c/o|erziehungsberechtigte)(?![\\p{L}])",
        IGNORE,
    )
    private val SALUTATION_LETTER = Regex("^(sehr geehrte[rn]?|guten tag|liebe[rn]?|hallo|dear)(?![\\p{L}])", IGNORE)
    private val BETREFF = Regex("^(betreff|betr\\.?|betrifft)(?![\\p{L}])", IGNORE)
    private val DATE_ONLY = Regex(
        "(?:\\p{L}[\\p{L} .\\-]{0,25}[:,]?\\s*)?(?:den\\s+)?(?:\\d{1,2}\\.\\s?\\d{1,2}\\.\\s?\\d{2,4}|\\d{4}-\\d{2}-\\d{2}|" +
            "\\d{1,2}(?:st|nd|rd|th)?\\s+\\p{L}{3,10}\\.?,?\\s+\\d{4}|\\p{L}{3,10}\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{4})",
        IGNORE,
    )

    private val INFO_LABEL = Regex(
        "^\\s*(?:ihr(?:e)?\\s+(?:zeichen|nachricht)|unser(?:e)?\\s+zeichen|ansprechpartner|sachbearbeiter|bearbeiter|" +
            "telefon|durchwahl|tel\\.|fax|e-?mail|datum|kunden(?:nummer|-?nr)|aktenzeichen|geschäftszeichen|" +
            "beitragsnummer|vertragsnummer|versicherungsnummer|mitgliedsnummer|rechnungsnummer|steuernummer)",
        IGNORE,
    )
    private val FOOTER_KEYWORDS = Regex(
        "geschäftsführ|amtsgericht|registergericht|handelsregister|(?<!\\p{L})HR[AB](?!\\p{L})|ust-?id|umsatzsteuer-?id|" +
            "(?<!\\p{L})iban(?!\\p{L})|(?<!\\p{L})bic(?!\\p{L})|bankverbindung|sitz der gesellschaft|vorsitz|persönlich haftende",
        IGNORE,
    )
    private val PAYMENT = Regex(
        "betrag|(?<!\\p{L})\\p{L}*summe(?!\\p{L})|zu zahlen|zahlung|zahlbar|überweis|fällig|faellig|frist|einspruch|" +
            "kontoinhaber|verwendungszweck|abschlag|lastschrift|erstattung|guthaben|" +
            "(?<!\\p{L})iban(?!\\p{L})|(?<!\\p{L})bic(?!\\p{L})|(?<!\\p{L})sepa(?!\\p{L})",
        IGNORE,
    )

    // Fractions of page height/width. Priors and tolerances, not a template.
    private const val HEADER_REGION = 0.10f
    private const val FOOTER_REGION = 0.88f
    private const val FOOTER_START = 0.86f
    private const val FOOTER_KEYWORD_START = 0.78f
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
    private const val SUBJECT_LINE_GAP = 0.03f
    private const val SALUTATION_GAP = 0.06f
    private const val ISOLATED_GAP = 0.025f
    private const val PAYMENT_NEIGHBOUR_GAP = 0.035f
}
