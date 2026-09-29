package com.postsaimanager.core.domain.extraction.v2

import com.postsaimanager.core.domain.extraction.candidates.Candidate
import com.postsaimanager.core.domain.extraction.candidates.CandidateKind
import com.postsaimanager.core.domain.extraction.candidates.CandidateSet

/**
 * One candidate as the model is offered it. Identical values found several times (a total in the
 * table and again in the payment sentence) are offered once, with every place it was seen listed;
 * the id is that of the occurrence that passed its checks, else the first.
 */
class OfferedRow(val candidate: Candidate, val nearLabels: List<String>, val pages: List<Int>)

/**
 * The candidates the model may choose from. Holds the data only; the prompt text for the table is
 * [SelectionPrompt.table] and the grammar's id lists come from [idsOf].
 *
 * @property dropped candidates found but not offered because their kind was over its cap, per kind.
 */
class OfferedCandidates(val rows: List<OfferedRow>, val dropped: Map<CandidateKind, Int> = emptyMap()) {

    private val byId: Map<String, OfferedRow> = rows.associateBy { it.candidate.id }

    fun get(id: String): Candidate? = byId[id]?.candidate

    fun idsOf(vararg kinds: CandidateKind): List<String> =
        rows.filter { it.candidate.kind in kinds }.map { it.candidate.id }

    val size: Int get() = rows.size
}

/**
 * Chooses which of the found candidates go in front of the model.
 *
 * Only shape-based decisions are made here (drop a time on its own, merge exact repeats, cap each
 * kind so the prompt stays small). Which candidate means what is the model's job. A kind over its
 * cap is sampled across the pages (round-robin, passing values first) rather than cut by reading
 * order, so a letter whose amounts are all on page 3 still offers them; what was left out is counted
 * in [OfferedCandidates.dropped] and reported in the diagnostics.
 */
object CandidateTable {

    private const val CAP_AMOUNTS = 20
    private const val CAP_DATES = 20
    private const val CAP_IBANS = 4
    private const val CAP_REFERENCES = 14
    private const val CAP_NAMES = 10
    private const val CAP_CONTACTS = 4
    private const val CAP_BIC = 2

    fun build(set: CandidateSet): OfferedCandidates {
        val offered = mutableListOf<OfferedRow>()
        val dropped = LinkedHashMap<CandidateKind, Int>()

        fun add(cap: Int, vararg kinds: CandidateKind) {
            val (rows, cut) = capped(set, cap, *kinds)
            offered += rows
            if (cut > 0) dropped[kinds.first()] = cut
        }

        add(CAP_NAMES, CandidateKind.NAME)
        add(CAP_DATES, CandidateKind.DATE, CandidateKind.DATETIME)
        add(CAP_AMOUNTS, CandidateKind.AMOUNT)
        add(CAP_IBANS, CandidateKind.IBAN)
        add(CAP_REFERENCES, CandidateKind.REFERENCE)
        // No fixed slot takes these, but the model may report them as open metadata.
        add(CAP_CONTACTS, CandidateKind.PHONE)
        add(CAP_CONTACTS, CandidateKind.EMAIL)
        add(CAP_BIC, CandidateKind.BIC)
        return OfferedCandidates(offered, dropped)
    }

    private fun capped(set: CandidateSet, cap: Int, vararg kinds: CandidateKind): Pair<List<OfferedRow>, Int> {
        val eligible = set.candidates.filter { it.kind in kinds && it.attrs["timeOnly"] == null }
        val merged = LinkedHashMap<String, MutableList<Candidate>>()
        for (c in eligible) merged.getOrPut(key(c)) { mutableListOf() }.add(c)
        val rows = merged.values.map { group ->
            OfferedRow(
                // The occurrence that passed its checks stands for the group: "64,98" in a table
                // cell and "64,98 €" in a sentence are one value, and only the second has a currency.
                candidate = group.firstOrNull { it.validation.isValid } ?: group.firstOrNull { it.attrs["zone"] != null } ?: group.first(),
                nearLabels = group.map { it.label }.filter { it.isNotBlank() }.distinct(),
                pages = group.map { it.page }.distinct().sorted(),
            )
        }
        if (rows.size <= cap) return rows to 0

        if (CandidateKind.NAME in kinds) {
            // Names matter on page 1 (letterhead, address field, footer): no spreading over the pages,
            // just the ones the layout placed in a zone before the ones found by shape alone.
            val keep = rows.withIndex()
                .sortedWith(
                    compareBy(
                        { it.value.candidate.attrs["zone"] == null },
                        { it.value.candidate.attrs["shape"] != null },
                        { it.index },
                    ),
                )
                .take(cap).map { it.index }.toSet()
            return rows.filterIndexed { i, _ -> i in keep } to (rows.size - keep.size)
        }

        // Round-robin over the pages, so no page is starved; within a page, passing values first.
        val byPage = rows.withIndex()
            .groupBy { it.value.pages.first() }
            .toSortedMap()
            .mapValues { (_, list) ->
                list.sortedWith(compareBy({ it.value.candidate.validation.isInvalid }, { it.index })).toMutableList()
            }
        val keep = mutableSetOf<Int>()
        while (keep.size < cap && byPage.values.any { it.isNotEmpty() }) {
            for (list in byPage.values) {
                if (keep.size >= cap) break
                list.removeFirstOrNull()?.let { keep += it.index }
            }
        }
        return rows.filterIndexed { i, _ -> i in keep } to (rows.size - keep.size)
    }

    private fun key(c: Candidate): String = when (c.kind) {
        CandidateKind.NAME -> "n:" + c.normalized.lowercase()
        else -> "${c.kind}:${c.normalized}"
    }
}
