package com.postsaimanager.core.domain.extraction.candidates

/**
 * The one place candidate ids are made: a prefix per kind and a running number per prefix, in the order
 * the drafts are given (reading order). Recorded model answers refer to these ids, so neither the
 * prefixes nor the order may change.
 */
internal object CandidateIds {

    private fun prefix(kind: CandidateKind): String = when (kind) {
        CandidateKind.DATE -> "D"
        CandidateKind.DATETIME -> "DT"
        CandidateKind.AMOUNT -> "A"
        CandidateKind.NUMBER -> "Z"
        CandidateKind.IBAN -> "I"
        CandidateKind.BIC -> "B"
        CandidateKind.REFERENCE -> "N"
        CandidateKind.NAME -> "M"
        CandidateKind.PHONE -> "T"
        CandidateKind.EMAIL -> "E"
    }

    fun assign(drafts: List<Draft>): List<Candidate> {
        val counters = HashMap<String, Int>()
        return drafts.map { d ->
            val prefix = prefix(d.c.kind)
            val n = (counters[prefix] ?: 0) + 1
            counters[prefix] = n
            d.c.copy(id = "$prefix$n")
        }
    }
}
