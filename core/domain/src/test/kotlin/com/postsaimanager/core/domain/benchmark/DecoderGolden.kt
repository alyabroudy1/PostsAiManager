package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.v2.ExtractionV2Result
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile

/**
 * One line per slot, slot list and party of a replayed scoring run, with the numbers behind them (candidate id, value, role,
 * confidence words and score notes): everything the decoder can change. Two runs with the same fingerprint are the same result
 * for every consumer of the reading.
 */
internal object DecoderGolden {

    fun fingerprint(docs: List<Pair<ManifestDoc, Fixture>>, recordings: List<Recording>, profile: ScoringProfile): String {
        val sb = StringBuilder()
        for ((m, f) in docs) {
            val rec = recordings.firstOrNull { it.key == m.key && it.variant == InterpreterMetrics.SCORING_VARIANT } ?: continue
            val r = InterpreterMetrics.replayResult(rec, f, profile)
            sb.appendLine("== ${m.key}")
            // The golden was written when the reader chose among the legacy types; its type line stays the legacy type the recording's scores
            // chose, so the file holds what it always held: the decoder's readings of the questions that were recorded.
            sb.append(lines(r, LegacyFamilyBridge.viewOf(rec)?.bestLegacyId))
        }
        return sb.toString()
    }

    fun lines(r: ExtractionV2Result, type: String? = r.documentType?.id): String {
        val sb = StringBuilder()
        sb.appendLine("type $type language ${r.language}")
        for ((k, v) in r.slots.entries.sortedBy { it.key.json }) {
            sb.appendLine("slot ${k.json} ${v.candidateId} ${v.normalized} ${v.role} ${v.aiConfidence} ${v.confidence} ${v.notes}")
        }
        for ((k, list) in r.slotLists.entries.sortedBy { it.key.json }) {
            sb.appendLine("list ${k.json} " + list.joinToString { "${it.candidateId}:${it.normalized}" })
        }
        for (p in r.parties.all) {
            sb.appendLine("party ${p.role} ${p.value.candidateId} ${p.name} ${p.kind} ${p.relation} ${p.value.aiConfidence} ${p.value.notes}")
        }
        for (e in r.extras) sb.appendLine("extra ${e.value.normalized}")
        return sb.toString()
    }
}
