package com.postsaimanager.core.domain.benchmark

import java.util.Locale

/**
 * The direct check of the selection bias (a small model prefers the first option of a list, or the first
 * label): over every question that offered two or more candidates and got a choice, how often was the
 * choice the first candidate in the order shown, against what chance alone would give (1 over the number
 * of candidates offered).
 *
 * Read from the recordings, not from the model: a generated question lists its candidates as `ID: text` lines
 * after `CANDIDATES IN THESE ZONES` (zones) or `OPTIONS:` (restated questionnaire), the choice is the answer's
 * first id; a scored batch lists its candidates in the order of its questions and the choice is the highest score
 * (before any abstain threshold: the model's own preference). The plain questionnaire keeps its candidates in the
 * prefix, not in the questions, so it has none of these lines and no value here.
 */
class FirstShare(val cases: Int, val first: Int, val chance: Double) {
    val share: Double get() = if (cases == 0) 0.0 else first.toDouble() / cases
    val expected: Double get() = if (cases == 0) 0.0 else chance / cases

    operator fun plus(o: FirstShare) = FirstShare(cases + o.cases, first + o.first, chance + o.chance)

    fun text(): String =
        if (cases == 0) "-" else String.format(Locale.ROOT, "%.0f%% (chance %.0f%%, n=%d)", share * 100, expected * 100, cases)
}

class FirstShareReport(val overall: FirstShare, val byQuestion: Map<String, FirstShare>, val byZone: Map<String, FirstShare>)

object FirstCandidateShare {

    private val CANDIDATE_LINE = Regex("^([A-Z]{1,2}\\d{1,3}): ", RegexOption.MULTILINE)
    private val ZONE_TAG = Regex("ZONE ([a-z-]+)\\.")
    private val NONE = Regex("^\"?NONE\\b")

    fun of(recordings: List<Recording>): FirstShareReport {
        var overall = FirstShare(0, 0, 0.0)
        val byQuestion = linkedMapOf<String, FirstShare>()
        val byZone = linkedMapOf<String, FirstShare>()
        fun add(question: String, zone: String?, isFirst: Boolean, n: Int) {
            val one = FirstShare(1, if (isFirst) 1 else 0, 1.0 / n)
            overall += one
            byQuestion[question] = (byQuestion[question] ?: FirstShare(0, 0, 0.0)) + one
            if (zone != null) byZone[zone] = (byZone[zone] ?: FirstShare(0, 0, 0.0)) + one
        }

        for (rec in recordings) for (a in rec.asks) {
            val answer = a.answer ?: continue
            if (a.name.startsWith("score:")) {
                val name = a.name.removePrefix("score:")
                // Only the batches that choose among candidates: not the type, nor the kind/household side questions.
                if (name == "type" || name.startsWith("kind:") || name == "household") continue
                val scores = answer.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                if (scores.size < 2) continue
                val best = scores.indices.maxByOrNull { scores[it] } ?: continue
                add(name, null, best == 0, scores.size)
                continue
            }
            if (NONE.containsMatchIn(answer.trim())) continue
            val section = when {
                a.question.contains("CANDIDATES IN THESE ZONES") -> a.question.substringAfter("CANDIDATES IN THESE ZONES").substringBefore("QUESTION:")
                a.question.contains("OPTIONS:") -> a.question.substringAfter("OPTIONS:")
                else -> continue
            }
            val ids = CANDIDATE_LINE.findAll(section).map { it.groupValues[1] }.toList()
            if (ids.size < 2) continue
            val chosen = answer.trim().split(' ').firstOrNull()?.trim('"') ?: continue
            if (chosen !in ids) continue
            add(a.name, ZONE_TAG.find(a.question)?.groupValues?.get(1), chosen == ids.first(), ids.size)
        }
        return FirstShareReport(overall, byQuestion, byZone)
    }
}
