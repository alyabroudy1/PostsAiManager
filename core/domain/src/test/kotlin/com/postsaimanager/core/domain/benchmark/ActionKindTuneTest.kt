package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.actions.ActionKindProfile
import com.postsaimanager.core.domain.extraction.actions.ActionKindReader
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.actions.ActionQuestions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * The fitting of the action thresholds on the recorded scores (no device): writes `build/reports/action-kinds-tune.txt` with the per-letter
 * scores, the raw-sign baseline, the argmax and the margins with and without the per-kind bias, and the binding thresholds. Nothing is
 * asserted: [ActionKindReplayTest] pins what was chosen. Runs only when `-Daction.tune` or the environment variable ACTION_TUNE is set.
 */
class ActionKindTuneTest {

    private val recordings = ActionRecording.load()
    private val docs = ActionKindEval.docs()
    private val NEG = Double.NEGATIVE_INFINITY

    private fun kindScores(rec: ActionRecording) = ActionKinds.ALL.associate { it.id to rec.scores.getValue(ActionQuestions.kind(it)) }
    private fun any(rec: ActionRecording) = rec.scores.getValue(ActionQuestions.anything())

    /** The mean score of each kind over [train] (the model's habitual lean for that kind), the bias a profile subtracts. */
    private fun meanBias(train: List<ActionRecording>): Map<String, Double> =
        ActionKinds.ALL.associate { k -> k.id to train.map { kindScores(it).getValue(k.id) }.average() }

    private fun report(profile: ActionKindProfile) = ActionKindEval.run(recordings, profile)

    /** Leave-one-out: each letter is decided with the bias fitted on the other letters. */
    private fun leaveOneOut(profile: ActionKindProfile): ActionReport = ActionReport(
        recordings.mapNotNull { held ->
            val doc = docs[held.key] ?: return@mapNotNull null
            val bias = meanBias(recordings.filter { it.key != held.key })
            val reading = runBlocking { ActionKindReader(held.scorer, profile.copy(kindBias = bias)).read(held.slots, held.senderStored) }
            ActionOutcome(doc, held.slots, reading?.items.orEmpty())
        },
    )

    @Test
    fun tune() {
        if (System.getenv("ACTION_TUNE") == null && System.getProperty("action.tune") == null) return
        if (recordings.isEmpty()) return
        val sb = StringBuilder()
        fun line(s: String = "") { sb.appendLine(s) }

        line("# Action kinds: scores of ${recordings.size} recorded letters")
        line()
        line("## Per letter: any, then the kinds best first (raw score), expected")
        for (rec in recordings) {
            val doc = docs[rec.key]
            val ks = kindScores(rec).entries.sortedByDescending { it.value }
            line(
                String.format(Locale.ROOT, "%-30s any=%+.2f | %s | expected=%s also=%s", rec.key, any(rec), ks.joinToString(" ") { it.key + String.format(Locale.ROOT, "=%+.2f", it.value) },
                    doc?.actions?.joinToString(",") { it.kind }, doc?.actionsAlsoOk?.joinToString(",")),
            )
        }
        line()
        val bias = meanBias(recordings)
        line("## The mean lean of each kind over all letters")
        line(ActionKinds.ALL.joinToString(" ") { it.id + String.format(Locale.ROOT, "=%+.2f", bias.getValue(it.id)) })
        line()

        line("## Raw sign (every kind that scores above 0, no limit) and the plain argmax")
        line("raw sign : " + report(ActionKindProfile(anyThreshold = NEG, minScore = 0.0, margin = Double.MAX_VALUE, maxActions = 8)).table())
        line("argmax   : " + report(ActionKindProfile(anyThreshold = NEG, minScore = NEG, margin = 0.0, maxActions = 1)).table())
        line()

        line("## argmax plus kinds within a margin, at most 2; the any-threshold, the minimum, no bias")
        for (any in listOf(NEG, -1.0, 0.0, 0.5, 1.0, 1.5)) for (min in listOf(NEG, 0.0, 0.5, 1.0)) for (m in listOf(0.25, 0.5, 1.0, 2.0)) {
            line(String.format(Locale.ROOT, "any>=%5.2f min>=%5.2f margin=%.2f : ", any, min, m) + report(ActionKindProfile(anyThreshold = any, minScore = min, margin = m)).table())
        }
        line()

        line("## the same with the per-kind mean taken off, bias fitted on the other letters (leave-one-out)")
        for (any in listOf(NEG, -1.0, 0.0, 0.5, 1.0, 1.5)) for (min in listOf(NEG, 0.0, 0.5, 1.0)) for (m in listOf(0.25, 0.5, 1.0, 2.0)) {
            line(String.format(Locale.ROOT, "any>=%5.2f min>=%5.2f margin=%.2f : ", any, min, m) + leaveOneOut(ActionKindProfile(anyThreshold = any, minScore = min, margin = m)).table())
        }
        line()

        line("## binding thresholds, with argmax within margin 0.5, any>=0")
        for (d in listOf(NEG, -1.0, -0.5, 0.0, 0.5, 1.0, 2.0)) for (a in listOf(NEG, -1.0, 0.0, 1.0)) {
            val r = report(ActionKindProfile(anyThreshold = 0.0, minScore = NEG, margin = 0.5, dateThreshold = d, amountThreshold = a))
            line(String.format(Locale.ROOT, "date>%5.2f amount>%5.2f : date=%.2f amount=%.2f (n=%d)", d, a, r.dateAccuracy, r.amountAccuracy, r.bindingsAsked))
        }

        val out = File("build/reports/action-kinds-tune.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}
