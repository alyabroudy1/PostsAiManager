package com.postsaimanager.core.domain.extraction.candidates

/**
 * Label hints for amounts and dates, in one place. The nearest label ("Gesamtbetrag", "zahlbar bis")
 * becomes the candidate's `label` and `labelKind`. They are HINTS only: no candidate exists, is valid
 * or is chosen because of a label, and code never picks a letter date from one. A candidate with no
 * label near it is offered just the same (see [LabelDetector] for the words per language).
 */
internal object LabelHints {

    private val NET_BASE = Regex("(?i)(?:mwst|ust|umsatzsteuer|mehrwertsteuer|vat)\\.?\\s*(?:auf|on|of|von)\\s*$")
    private val PLACE_COMMA = Regex("^\\p{L}[\\p{L}.\\- ]{1,40},\\s*$")
    private val WEEKDAY = Regex(
        "(?i)^(?:Montag|Dienstag|Mittwoch|Donnerstag|Freitag|Samstag|Sonnabend|Sonntag|Mo|Di|Mi|Do|Fr|Sa|So|" +
            "Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\.?,?$",
    )
    private val PERIOD_JOIN = Regex("(?i)^\\s*(?:[-–—]|bis|to|until)\\s*$")
    private val LABELLED_KINDS = setOf(
        CandidateKind.DATE, CandidateKind.DATETIME, CandidateKind.AMOUNT,
        CandidateKind.IBAN, CandidateKind.REFERENCE, CandidateKind.PHONE,
    )

    /** Sets the label hint of every amount and date draft, reading the text around it on its line. */
    fun apply(ctx: ExtractionContext) {
        val byLine = ctx.drafts.groupBy { it.line }
        for ((line, list) in byLine) {
            val numeric = list.filter { it.c.kind in LABELLED_KINDS }
            var previousDate: Draft? = null
            for (d in numeric) {
                val prevEnd = numeric.filter { it !== d && it.end <= d.start }.maxOfOrNull { it.end } ?: 0
                val nextStart = numeric.filter { it !== d && it.start >= d.end }.minOfOrNull { it.start } ?: line.text.length
                val pre = line.text.substring(prevEnd, d.start)
                val post = line.text.substring(d.end, minOf(nextStart, d.end + 30))
                when (d.c.kind) {
                    CandidateKind.AMOUNT -> labelAmount(ctx, d, line, pre, post, prevEnd == 0)
                    CandidateKind.DATE, CandidateKind.DATETIME -> {
                        labelDate(ctx, d, line, pre, post, prevEnd == 0, previousDate)
                        previousDate = d
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun labelAmount(ctx: ExtractionContext, d: Draft, line: SourceLine, pre: String, post: String, firstInLine: Boolean) {
        val preSentence = LabelDetector.lastSentence(pre.takeLast(80))
        var hit: LabelHit? = null
        val nb = NET_BASE.find(preSentence)
        if (nb != null) hit = LabelHit(nb.value.trim(), LabelKind.NET, nb.range.first, nb.range.last + 1)
        if (hit == null) hit = LabelDetector.detectLast(preSentence, LabelDetector.AMOUNT_RULES)
        if (hit == null) {
            val p = LabelDetector.detectFirst(LabelDetector.firstSentence(post), LabelDetector.AMOUNT_RULES)
            if (p != null && p.start <= 12) hit = p
        }
        if (hit == null && firstInLine && pre.none { it.isLetter() }) {
            ctx.rowNeighbour(line, left = true)?.let { nt ->
                hit = LabelDetector.detectLast(LabelDetector.lastSentence(nt.takeLast(80)), LabelDetector.AMOUNT_RULES)
            }
        }
        d.c = d.c.copy(label = hit?.text.orEmpty(), labelKind = hit?.kind)
    }

    private fun labelDate(
        ctx: ExtractionContext,
        d: Draft,
        line: SourceLine,
        pre: String,
        post: String,
        firstInLine: Boolean,
        previousDate: Draft?,
    ) {
        val preSentence = LabelDetector.lastSentence(pre.takeLast(80))
        var hit = LabelDetector.detectLast(preSentence, LabelDetector.DATE_RULES)
        if (hit == null) {
            val p = LabelDetector.detectFirst(LabelDetector.firstSentence(post), LabelDetector.DATE_RULES)
            if (p != null && p.start <= 12) hit = p
        }
        if (hit == null && firstInLine && pre.none { it.isLetter() }) {
            ctx.rowNeighbour(line, left = true)?.let { nt ->
                hit = LabelDetector.detectLast(LabelDetector.lastSentence(nt.takeLast(80)), LabelDetector.DATE_RULES)
            }
        }
        var label = hit?.text.orEmpty()
        var kind = hit?.kind
        // "01.10.2026 - 31.12.2026" / "01.01.2025 bis 31.12.2025"
        if (previousDate != null && PERIOD_JOIN.matches(pre)) {
            kind = LabelKind.PERIOD
            if (label.isEmpty()) label = previousDate.c.label
            if (previousDate.c.labelKind != LabelKind.PERIOD) previousDate.c = previousDate.c.copy(labelKind = LabelKind.PERIOD)
        }
        val trimmedPre = pre.trim()
        if (kind == null && firstInLine && line.page == 1 && d.c.kind == CandidateKind.DATE &&
            PLACE_COMMA.matches(trimmedPre) && !WEEKDAY.matches(trimmedPre)
        ) {
            kind = LabelKind.LETTER_DATE
            label = "Ort, Datum"
        }
        if (kind == null && d.c.kind == CandidateKind.DATETIME && d.c.attrs["timeOnly"] == null) kind = LabelKind.APPOINTMENT
        d.c = d.c.copy(label = label, labelKind = kind)
    }
}
