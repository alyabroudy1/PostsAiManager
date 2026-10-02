package com.postsaimanager.core.domain.extraction.candidates

import com.postsaimanager.core.model.TextBounds

/**
 * What the page says about the numbers [AmountFinder] left as plain NUMBERs, by geometry and
 * arithmetic only, and the marking of consistent net + VAT = gross triples. Runs over the drafts of
 * every finder once they are all in.
 */
internal object AmountEvidence {

    /** A block at most this wide (fraction of the page) is read as a table cell; a wider one is running text. */
    private const val CELL_MAX_WIDTH = 0.4f

    /** A cell names a column's currency only when it is at most this far above or below (fraction of the page height). */
    private const val COLUMN_MAX_DISTANCE = 0.45f

    /** The sum rule looks at documents with at most this many amounts, to keep it a small pairwise check. */
    private const val MAX_SUM_AMOUNTS = 120

    /** Two cells share a column edge when their edges differ by less than this (fraction of the page width). */
    private const val EDGE_TOLERANCE = 0.02f

    private fun isPlainNumber(d: Draft) =
        d.c.kind == CandidateKind.NUMBER && d.c.attrs["percent"] == null && d.c.attrs["rate"] == null

    /**
     * Turns a NUMBER into an AMOUNT when the page gives evidence that it is money although no
     * currency stands next to it:
     * - **row**: the cell next to it on the same row shows a currency (a label cell "SUMME EUR",
     *   "12 x 220,00 EUR");
     * - **column**: a cell of its column shows a currency (a header "EUR", "Betrag in EUR") or is an
     *   amount already;
     * - **same value**: the same figure is printed elsewhere on the page with a currency;
     * - **triple**: it is a term of net + VAT = gross;
     * - **sum**: it is the exact sum or difference of two amounts of the document.
     * Percentages and rates never are. What was found once can carry over to its neighbours, so this
     * repeats until nothing changes. A promoted amount has no explicit currency, so the verifier caps
     * it like any amount without one.
     */
    fun promote(ctx: ExtractionContext) {
        val drafts = ctx.drafts
        if (drafts.none(::isPlainNumber)) return
        do {
            var changed = false
            for (n in drafts.filter(::isPlainNumber)) {
                // A figure on a line that already has its own currency amount is that line's label or quantity, not a second amount.
                if (drafts.any { it.line === n.line && it.c.kind == CandidateKind.AMOUNT && it.c.attrs["currencyExplicit"] == "true" }) continue
                val (how, currency) = rowCurrency(ctx, n)?.let { "row" to it }
                    ?: columnCurrency(ctx, n)?.let { "column" to it }
                    ?: sameValueCurrency(drafts, n)?.let { "value" to it }
                    ?: continue
                promote(n, currency, how)
                changed = true
            }
            if (promoteTriples(drafts)) changed = true
            if (promoteSums(drafts)) changed = true
        } while (changed)
    }

    /**
     * A figure that is the exact sum or difference of two amounts of the document, whatever the page
     * (a total on one page, its two parts on another), takes part in their arithmetic and is money too.
     * Only a figure joins two amounts that are already known; nothing is inferred from figures alone.
     */
    private fun promoteSums(drafts: List<Draft>): Boolean {
        val amounts = drafts.filter { it.c.kind == CandidateKind.AMOUNT && (it.c.cents ?: 0L) > 0L }
        if (amounts.size < 2 || amounts.size > MAX_SUM_AMOUNTS) return false
        val cents = amounts.map { it.c.cents!! }
        var promoted = false
        for (n in drafts.filter(::isPlainNumber)) {
            val x = n.c.cents ?: continue
            if (x <= 0L) continue
            val bySum = cents.withIndex().any { (i, p) -> cents.withIndex().any { (j, q) -> i < j && p + q == x } }
            val byDifference = cents.any { p -> p - x > 0L && (p - x) in cents }
            if (bySum || byDifference) {
                promote(n, amounts.first().c.currency, "sum")
                promoted = true
            }
        }
        return promoted
    }

    /** The currency shown by the cell beside [n] on its row, or null. */
    private fun rowCurrency(ctx: ExtractionContext, n: Draft): String? {
        val b = n.line.block?.bounds ?: return null
        if (!isCell(b)) return null
        for (o in ctx.lines) {
            val ob = o.block?.bounds ?: continue
            if (o.page != n.line.page || o.blockIndex == n.line.blockIndex || !isCell(ob)) continue
            val overlap = minOf(b.bottom, ob.bottom) - maxOf(b.top, ob.top)
            if (overlap < 0.5f * minOf(b.height, ob.height)) continue
            currencyIn(ctx.drafts, o)?.let { return it }
        }
        return null
    }

    /** The currency of the amount that already carries [n]'s figure on the same page, or null. */
    private fun sameValueCurrency(drafts: List<Draft>, n: Draft): String? {
        val cents = n.c.cents ?: return null
        return drafts.firstOrNull {
            it.c.kind == CandidateKind.AMOUNT && it.c.page == n.c.page && it.c.cents == cents && it.c.attrs["currencyExplicit"] == "true"
        }?.c?.currency
    }

    /** The currency a line shows: its own explicit amount's, or a sign or code standing in it. Null when none. */
    private fun currencyIn(drafts: List<Draft>, o: SourceLine): String? {
        drafts.firstOrNull { it.line === o && it.c.kind == CandidateKind.AMOUNT && it.c.attrs["currencyExplicit"] == "true" }
            ?.let { return it.c.currency }
        val token = CurrencyShape.CURRENCY_TOKEN.find(o.text)?.groupValues?.get(1) ?: return null
        return AmountParser.currencyOf(token)
    }

    private fun promote(d: Draft, currency: String?, how: String) {
        val cents = d.c.cents ?: return
        val code = currency ?: d.c.currency ?: "EUR"
        d.c = d.c.copy(
            kind = CandidateKind.AMOUNT,
            normalized = Money(cents, code, false).canonical(),
            validation = Validation.Unchecked,
            attrs = d.c.attrs + mapOf("currency" to code, "promoted" to how),
        )
    }

    /** A cell that is narrow enough to be a table cell rather than a paragraph. */
    private fun isCell(b: TextBounds) = b.width <= CELL_MAX_WIDTH

    /**
     * The currency of the column [n] sits in, or null. A column is the set of narrow blocks on the
     * page that overlap [n]'s block horizontally by half, or share its left or right edge. A block of
     * it that shows a currency sign or code on its own (or holds a currency amount) names the column.
     */
    private fun columnCurrency(ctx: ExtractionContext, n: Draft): String? {
        val b = n.line.block?.bounds ?: return null
        if (!isCell(b)) return null
        for (o in ctx.lines) {
            val ob = o.block?.bounds ?: continue
            if (o.page != n.line.page || o.blockIndex == n.line.blockIndex || !isCell(ob)) continue
            if (kotlin.math.abs(ob.centerY - b.centerY) > COLUMN_MAX_DISTANCE) continue
            val overlap = minOf(b.right, ob.right) - maxOf(b.left, ob.left)
            val aligned = overlap >= 0.5f * minOf(b.width, ob.width) ||
                kotlin.math.abs(b.right - ob.right) <= EDGE_TOLERANCE || kotlin.math.abs(b.left - ob.left) <= EDGE_TOLERANCE
            if (!aligned) continue
            // An amount of the column, explicit or promoted, or a currency sign or code in a header cell.
            ctx.drafts.firstOrNull { it.line === o && it.c.kind == CandidateKind.AMOUNT }?.let { return it.c.currency }
            currencyIn(ctx.drafts, o)?.let { return it }
        }
        return null
    }

    /**
     * The (net, VAT, gross) groups of one page's [pool]: every pair n > v of one currency whose sum, within
     * one cent, is a third figure of that currency and whose ratio is a plausible tax rate.
     */
    private fun triples(pool: List<Draft>): List<List<Draft>> {
        val found = ArrayList<List<Draft>>()
        val byCents = pool.groupBy { it.c.cents!! }
        for (n in pool) for (v in pool) {
            if (n === v || n.c.currency != v.c.currency) continue
            val nc = n.c.cents!!
            val vc = v.c.cents!!
            if (vc >= nc) continue
            for (delta in -1L..1L) {
                for (g in byCents[nc + vc + delta].orEmpty()) {
                    if (g === n || g === v || g.c.currency != n.c.currency) continue
                    if (!AmountConsistency.isNetVatGross(nc, vc, g.c.cents!!)) continue
                    found.add(listOf(n, v, g))
                }
            }
        }
        return found
    }

    /** Terms of net + VAT = gross among the amounts and the plain numbers of one page become amounts. True when one was promoted. */
    private fun promoteTriples(drafts: List<Draft>): Boolean {
        var promoted = false
        val pool = drafts.filter {
            (it.c.kind == CandidateKind.AMOUNT || isPlainNumber(it)) && (it.c.cents ?: 0L) > 0L
        }
        for ((_, onPage) in pool.groupBy { it.c.page }) {
            val terms = HashSet<Draft>()
            for (t in triples(onPage)) terms.addAll(t)
            val currency = terms.firstOrNull { it.c.attrs["currencyExplicit"] == "true" }?.c?.currency
            for (t in terms) if (t.c.kind == CandidateKind.NUMBER) {
                promote(t, currency, "triple")
                promoted = true
            }
        }
        return promoted
    }

    /**
     * Arithmetic, not words: three amounts of one currency on one page where a + b = c (within one
     * cent) and b / a is a plausible tax rate (see [AmountConsistency.isNetVatGross]) are marked as a
     * consistent triple. Nothing says which is the net, the VAT or the gross; the sum does.
     */
    fun markTriples(drafts: List<Draft>) {
        var k = 0
        val marked = HashSet<Draft>()
        for ((_, onPage) in drafts.filter { it.c.kind == CandidateKind.AMOUNT && (it.c.cents ?: 0L) > 0L }.groupBy { it.c.page }) {
            for (triple in triples(onPage)) {
                k++
                for (x in triple) {
                    if (!marked.add(x)) continue
                    x.c = x.c.copy(
                        validation = if (x.c.validation.isInvalid) x.c.validation else Validation.Valid,
                        attrs = x.c.attrs + ("triple" to "T$k"),
                    )
                }
            }
        }
    }
}
