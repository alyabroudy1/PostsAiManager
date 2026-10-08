package com.postsaimanager.core.domain.extraction.candidates

/**
 * Which lines of a page are the LABEL of a label/value pair in a stacked layout (an information block: a short line, then its value
 * on the next line, over and over). A label is not a party: a name finder that offered "Ansprechpartnerin", "E-Mail" or "Datum" as a
 * sender or addressee only gave the model a line to be fooled by. Decided by layout alone, never by what a word means:
 *
 * - a run is a stack of lines that continue one another (the same block, or right below and aligned: [ExtractionContext.continuation]);
 * - a line is word-shaped when it has no digit, no `@`, no web address (the shape of a name or a label) and value-shaped when it is a
 *   short line that has one (a phone number, an email, a date, a number); a sentence is neither;
 * - a word-shaped line followed by one value-shaped line, and then by a word-shaped line again (or the end), is a label; a run with at
 *   least two such labels is a label/value layout. In it, when every confirmed label sits at the same parity of the run (every second
 *   line), the word-shaped lines at that parity between the first and the last label are labels too, even when their value is itself a
 *   name ("Ansprechpartnerin" over "Frau Nadine Beispiel").
 *
 * One pair alone is no layout, and a word-shaped line over two value-shaped lines is no pair: a name over a street line over a postcode
 * line is an address block, and its name is a candidate.
 */
internal object LabelValueLayout {

    /** The fewest confirmed label lines (word-shaped over value-shaped) that make a run a label/value layout. */
    private const val MIN_PAIRS = 2

    /** A value of a pair is a short line: a longer one is a sentence, which is no value. */
    private const val MAX_VALUE_CHARS = 60

    private val WHITE = Regex("\\s+")

    /** Blocks whose left edges are within this step of one another (a fraction of the page width) share a column. */
    private const val LEFT_EDGE_STEP = 0.02f

    /** Each label line of [ctx] with the line right below it that continues it (its value, when it has one). */
    fun pairs(ctx: ExtractionContext): List<LabelValuePair> {
        val stacked = labelLines(ctx).sortedBy { it.order }.map { LabelValuePair(it.page, it.text, ctx.continuation(it)?.text) }
        // Side by side (an information block printed as two columns, the label left of its value on the same row): word-shaped blocks
        // with a block to their right, at least two of them sharing one left edge.
        val sideBySide = ctx.activeLines
            .filter { it.zone == null && it.block != null && wordShaped(it.text) }
            .mapNotNull { line -> ctx.rowNeighbour(line, left = false)?.let { line to it } }
            .groupBy { (line, _) -> line.page to Math.round(line.block!!.bounds.left / LEFT_EDGE_STEP) }
            .values.filter { it.size >= MIN_PAIRS }.flatten()
            .map { (line, value) -> LabelValuePair(line.page, line.text, value) }
        return (stacked + sideBySide).distinctBy { it.page to it.label }
    }

    /** The lines of [ctx] that are the label of a label/value pair. */
    fun labelLines(ctx: ExtractionContext): Set<SourceLine> {
        val labels = HashSet<SourceLine>()
        val seen = HashSet<SourceLine>()
        for (start in ctx.activeLines) {
            if (start in seen) continue
            val run = ArrayList<SourceLine>()
            var line: SourceLine? = start
            while (line != null && !line.noise && seen.add(line)) {
                run += line
                line = ctx.continuation(line)
            }
            if (run.size >= 2 * MIN_PAIRS) labels += labelsOf(run)
        }
        return labels
    }

    /** The label lines of one [run]: see the class comment. Empty when the run is no label/value layout. */
    internal fun labelsOf(run: List<SourceLine>): Set<SourceLine> {
        val words = run.map { wordShaped(it.text) }
        val values = run.map { valueShaped(it.text) }
        // A pair is word-shaped over value-shaped AND followed by another word-shaped line (or the end of the run): a name over a street
        // over a postcode is an address block (a value followed by a value), not a label. A sentence is neither shape and ends the pair.
        // A line the layout put in a zone of names (the address field, the return line, the letterhead, the footer) is never a label: a
        // footer's company name over its address over its register line alternates like a stack of labels, and is the sender's name.
        fun free(i: Int) = run[i].zone == null
        val confirmed = run.indices.filter { i -> free(i) && words[i] && i + 1 < run.size && values[i + 1] && (i + 2 >= run.size || words[i + 2]) }
        // The labels of one layout sit on every second line: the parity most of the confirmed labels share is the layout's (a tie decides
        // nothing), and one stray pair elsewhere in the run (a name over its one-line address) is left alone.
        val byParity = confirmed.groupBy { it % 2 }.values.sortedByDescending { it.size }
        val labels = byParity.firstOrNull()?.takeIf { it.size >= MIN_PAIRS && (byParity.size == 1 || byParity[1].size < it.size) } ?: return emptySet()
        // The word-shaped lines of that parity between the first and the last label are labels too, whatever their value looks like (a name
        // value is word-shaped itself). Nothing outside that stretch is touched.
        val parity = labels.first() % 2
        val chosen = (labels.first()..labels.last()).filter { it % 2 == parity && words[it] && free(it) }
        return chosen.mapTo(HashSet()) { run[it] }
    }

    /** A line of words only: no digit, no `@`, no web address, one to six words (a trailing colon of a label is ignored). */
    private fun wordShaped(text: String): Boolean {
        val t = text.trim().trimEnd(':').trim()
        if (t.length !in 2..60 || dataShaped(t)) return false
        if (t.none { it.isLetter() }) return false
        return t.split(WHITE).size in 1..6
    }

    /** A short line that holds data: a digit, an `@` or a web address, or no letter at all (a phone number, an email, a date, a number). */
    private fun valueShaped(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.length <= MAX_VALUE_CHARS && (dataShaped(t) || t.none { it.isLetter() })
    }

    private fun dataShaped(t: String): Boolean = t.any { it.isDigit() } || t.contains('@') || t.contains("://") || t.contains("www.", true)
}

/** A label line of an information block and the line below it that is its value ([value] null when none follows), as printed, on [page]. */
data class LabelValuePair(val page: Int, val label: String, val value: String?)

/** The label/value pairs of a letter, found by layout alone ([LabelValueLayout]): the reader is told which lines are labels, never names. */
object LabelValuePairs {

    fun of(pages: List<List<com.postsaimanager.core.model.OcrBlock>>, zones: Map<BlockKey, BlockZone>): List<LabelValuePair> =
        LabelValueLayout.pairs(ExtractionContext.ofPages(pages, zones))
}
