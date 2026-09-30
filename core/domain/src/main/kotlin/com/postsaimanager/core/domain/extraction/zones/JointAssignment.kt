package com.postsaimanager.core.domain.extraction.zones

import com.postsaimanager.core.domain.extraction.candidates.AmountConsistency
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The best answer for every question at once: the assignment of at most one candidate to each question (or none) that maximises
 * the sum of the calibrated scores of the chosen candidates, plus the unary bonus of a total that completes a net + VAT = gross
 * triple, minus the penalty of a date before the date it must not precede; a candidate answers only the questions that may share
 * it ([SharingRules]), which makes the choice a one-to-one matching when there are no penalties. Exact: depth-first
 * branch and bound on each group of questions that can interfere (the matrices are tiny, so this is the Hungarian algorithm's
 * answer plus the pair terms a matching cannot express); a node limit hands back the best assignment found so far.
 *
 * "None" is an option of every question, worth the question's abstain level in the same calibrated units, so a question whose
 * best candidate another question needs more is left unanswered rather than given a poor second choice, exactly when the
 * second choice is worth less than abstaining.
 *
 * No word of the letter is read: the scores are the AI's, the constraints are about identity, dates and arithmetic.
 */
class JointAssignment(private val spec: DecoderSpec, private val nodeLimit: Int = NODE_LIMIT) : SlotDecoder {

    /** A question's options: a calibrated value per candidate, and what none is worth. */
    private class Options(val q: ScoredQuestion, val values: DoubleArray, val none: Double)

    override fun decode(questions: List<ScoredQuestion>, pool: List<CandidateFacts>): Map<String, String?> {
        val triple = if (spec.tripleBonus != 0.0) TripleFacts.of(pool) else null
        val options = questions.map { options(it, triple) }
        val chosen = arrayOfNulls<Int>(questions.size) // per question: candidate index, or null for none
        for (group in groups(options)) solve(group, options, chosen)
        val out = LinkedHashMap<String, String?>()
        questions.forEachIndexed { i, q -> out[q.name] = chosen[i]?.let { q.candidates[it].id } }
        return out
    }

    // ── values ──

    private fun options(q: ScoredQuestion, triple: TripleFacts?): Options {
        val s = DoubleArray(q.candidates.size) { q.candidates[it].score }
        val (v, none) = Calibrate.apply(spec, q, s)
        if (triple != null && q.name in PairConstraints.TOTAL_SLOTS) {
            for (i in v.indices) v[i] += spec.tripleBonus * triple.effect(q.candidates[i].facts)
        }
        return Options(q, v, none)
    }

    // ── groups of questions that can interfere ──

    private fun interferes(a: Options, b: Options): Boolean {
        if (hasPairTerm(a.q.name, b.q.name)) return true
        if (spec.sharing.mayShare(a.q.name, b.q.name)) return false
        val ids = a.q.candidates.mapTo(HashSet()) { it.id }
        return b.q.candidates.any { it.id in ids }
    }

    private fun groups(options: List<Options>): List<List<Int>> {
        val parent = IntArray(options.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in options.indices) for (j in i + 1 until options.size) {
            if (interferes(options[i], options[j])) parent[find(i)] = find(j)
        }
        return options.indices.groupBy { find(it) }.values.toList()
    }

    private fun hasPairTerm(a: String, b: String): Boolean =
        spec.dateOrderPenalty != 0.0 && PairConstraints.DATE_ORDER.any { (x, y) -> (x == a && y == b) || (x == b && y == a) }

    // ── the search ──

    private class Search(val members: List<Int>, val options: List<Options>, val chosen: Array<Int?>, val limit: Int) {
        var nodes = 0
        var best = Double.NEGATIVE_INFINITY
        var bestPick: Array<Int?> = arrayOfNulls(members.size)
        val pick: Array<Int?> = arrayOfNulls(members.size)
    }

    private fun solve(group: List<Int>, options: List<Options>, chosen: Array<Int?>) {
        // The questions with the fewest candidates first: the bound is tight early.
        val members = group.sortedBy { options[it].q.candidates.size }
        val st = Search(members, options, chosen, nodeLimit)
        // bound[k]: the most the questions k.. can still add, each at its own best option.
        val bound = DoubleArray(members.size + 1)
        for (k in members.indices.reversed()) {
            val o = options[members[k]]
            bound[k] = bound[k + 1] + max(o.none, o.values.maxOrNull() ?: Double.NEGATIVE_INFINITY)
        }
        // Each question's options, best first; none before a candidate of equal value (an abstain level is not beaten by a tie).
        val order = members.map { m ->
            val o = options[m]
            val idx = o.values.indices.sortedByDescending { o.values[it] }
            buildList<Int?> {
                var noneDone = false
                for (i in idx) {
                    if (!noneDone && o.none >= o.values[i]) { add(null); noneDone = true }
                    add(i)
                }
                if (!noneDone) add(null)
            }
        }
        descend(st, order, bound, 0, 0.0)
        members.forEachIndexed { k, m -> chosen[m] = st.bestPick[k] }
    }

    private fun descend(st: Search, order: List<List<Int?>>, bound: DoubleArray, k: Int, total: Double) {
        if (k == st.members.size) {
            if (total > st.best + EPS) {
                st.best = total
                st.bestPick = st.pick.copyOf()
            }
            return
        }
        if (total + bound[k] <= st.best + EPS) return
        if (st.nodes++ > st.limit && st.best > Double.NEGATIVE_INFINITY) return
        val o = st.options[st.members[k]]
        for (c in order[k]) {
            val value = if (c == null) o.none else o.values[c]
            val penalty = if (c == null) 0.0 else penalty(st, k, c) ?: continue
            st.pick[k] = c
            descend(st, order, bound, k + 1, total + value + penalty)
        }
        st.pick[k] = null
    }

    /** The penalty of candidate [c] for the k-th member given the members before it; null when a value would answer two questions that may not share it. */
    private fun penalty(st: Search, k: Int, c: Int): Double? {
        val o = st.options[st.members[k]]
        val cand = o.q.candidates[c]
        var sum = 0.0
        for (j in 0 until k) {
            val pj = st.pick[j] ?: continue
            val other = st.options[st.members[j]]
            val oc = other.q.candidates[pj]
            if (oc.id == cand.id && !spec.sharing.mayShare(o.q.name, other.q.name)) return null
            if (spec.dateOrderPenalty != 0.0) sum -= spec.dateOrderPenalty * misordered(o.q.name, cand.facts, other.q.name, oc.facts)
        }
        return sum
    }

    /** 1 when the two answers are dates and the pair of questions is bound by [PairConstraints.DATE_ORDER] and they come in the wrong order. */
    private fun misordered(a: String, ca: CandidateFacts, b: String, cb: CandidateFacts): Int {
        for ((early, late) in PairConstraints.DATE_ORDER) {
            val (e, l) = when {
                early == a && late == b -> ca to cb
                early == b && late == a -> cb to ca
                else -> continue
            }
            val de = dateOf(e) ?: return 0
            val dl = dateOf(l) ?: return 0
            return if (dl.isBefore(de)) 1 else 0
        }
        return 0
    }

    private fun dateOf(c: CandidateFacts): LocalDate? {
        if (c.kind != CandidateKind.DATE && c.kind != CandidateKind.DATETIME) return null
        return runCatching { LocalDate.parse(c.normalized.take(ISO_DATE_CHARS)) }.getOrNull()
    }

    companion object {
        const val NODE_LIMIT = 2_000_000
        private const val EPS = 1e-12
        private const val ISO_DATE_CHARS = 10
    }
}

/** The net + VAT = gross triples among a letter's amounts, by arithmetic alone ([AmountConsistency.isNetVatGross]). */
internal class TripleFacts private constructor(private val gross: Set<Pair<String?, Long>>, private val part: Set<Pair<String?, Long>>) {

    /** +1 for an amount that is the gross of a triple, -1 for one that is only a net or VAT part of one, else 0. */
    fun effect(c: CandidateFacts): Int {
        val cents = c.cents ?: return 0
        val key = c.currency to cents
        return when {
            key in gross -> 1
            key in part -> -1
            else -> 0
        }
    }

    companion object {
        fun of(pool: List<CandidateFacts>): TripleFacts {
            val gross = HashSet<Pair<String?, Long>>()
            val part = HashSet<Pair<String?, Long>>()
            val amounts = pool.filter { it.kind == CandidateKind.AMOUNT && (it.cents ?: 0) > 0 }
            for ((currency, group) in amounts.groupBy { it.currency }) {
                val cents = group.mapNotNull { it.cents }.distinct()
                for (n in cents) for (v in cents) {
                    if (n == v) continue
                    for (g in cents) {
                        if (g == n || g == v || !AmountConsistency.isNetVatGross(n, v, g)) continue
                        gross += currency to g
                        part += currency to n
                        part += currency to v
                    }
                }
            }
            // An amount that is the gross of one triple and a part of another is a total.
            return TripleFacts(gross, part - gross)
        }
    }
}

/** Brings a question's scores to the scale its decoder adds them on. Pure. */
internal object Calibrate {

    private const val MIN_SPREAD = 1e-6

    /** @return the calibrated value of each candidate and of none. */
    fun apply(spec: DecoderSpec, q: ScoredQuestion, scores: DoubleArray): Pair<DoubleArray, Double> {
        val n = scores.size
        val t = q.threshold
        val (values, none) = when (spec.calibration) {
            ScoreCalibration.RAW -> scores.copyOf() to t
            ScoreCalibration.ZSCORE -> {
                if (n < 2) {
                    scores.copyOf() to t
                } else {
                    val mean = scores.average()
                    val sd = sqrt(scores.sumOf { (it - mean) * (it - mean) } / n).coerceAtLeast(MIN_SPREAD)
                    DoubleArray(n) { (scores[it] - mean) / sd } to (t - mean) / sd
                }
            }
            ScoreCalibration.RANK -> {
                if (n < 2) {
                    DoubleArray(n) { RANK_SINGLE } to if (n == 1 && t >= scores[0]) RANK_SINGLE + 1 else 0.0 - RANK_NONE_BELOW
                } else {
                    val rank = DoubleArray(n) { i ->
                        val below = scores.count { it < scores[i] }
                        val ties = scores.count { it == scores[i] }
                        (below + (ties - 1) / 2.0) / (n - 1)
                    }
                    rank to (scores.count { it <= t }.toDouble() / (n - 1)) - RANK_NONE_BELOW
                }
            }
            ScoreCalibration.SOFTMAX -> {
                val tau = spec.temperature.coerceAtLeast(MIN_SPREAD)
                val logits = DoubleArray(n + 1) { if (it < n) scores[it] / tau else t / tau }
                val peak = logits.max()
                val lse = peak + ln(logits.sumOf { exp(it - peak) })
                DoubleArray(n) { logits[it] - lse } to logits[n] - lse
            }
            ScoreCalibration.CONTENT_FREE -> {
                val prior = q.prior
                DoubleArray(n) { scores[it] - (prior?.getOrNull(it) ?: 0.0) } to t
            }
        }
        return values to (spec.abstain ?: none)
    }

    private const val RANK_SINGLE = 0.5
    private const val RANK_NONE_BELOW = 1e-9
}
