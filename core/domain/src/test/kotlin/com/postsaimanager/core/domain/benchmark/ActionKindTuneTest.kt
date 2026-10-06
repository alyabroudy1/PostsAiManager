package com.postsaimanager.core.domain.benchmark

import com.postsaimanager.core.domain.extraction.actions.ActionKindProfile
import com.postsaimanager.core.domain.extraction.actions.ActionKinds
import com.postsaimanager.core.domain.extraction.actions.ActionQuestions
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale

/**
 * The fitting of the action thresholds on the recorded scores (no device): writes `build/reports/action-kinds-tune.txt` with the per-letter
 * gate and kind scores, and the replay under the gate's settings (the baseline over an empty letter, the threshold over it, the second gate).
 * Nothing is asserted: [ActionKindReplayTest] pins what was chosen. Runs only when `-Daction.tune` or the environment variable ACTION_TUNE is set.
 */
class ActionKindTuneTest {

    private val recordings = ActionRecording.load()
    private val docs = ActionKindEval.docs()
    private val NEG = Double.NEGATIVE_INFINITY

    @Test
    fun tune() {
        if (System.getenv("ACTION_TUNE") == null && System.getProperty("action.tune") == null) return
        if (recordings.none { it.baseline.isNotEmpty() }) return
        val sb = StringBuilder()
        fun line(s: String = "") { sb.appendLine(s) }
        val gateBase = ActionKindEval.gateBaseline(recordings)
        val doneBase = ActionKindEval.doneBaseline(recordings)
        val kindsBase = ModelProfiles.QWEN35_08B.scoring.actions
        fun f(d: Double) = String.format(Locale.ROOT, "%+.2f", d)

        line("## The content-free baseline of the gate (over an empty letter), by family; the done question")
        line(gateBase.entries.joinToString("  ") { it.key.ifEmpty { "(none)" } + "=" + f(it.value) })
        line("done baseline=" + f(doneBase))
        line()
        line("## Per letter: family, gate raw, gate over its baseline, done raw, done over its baseline | expected kinds, also")
        for (rec in recordings) {
            val doc = docs[rec.key]
            val family = rec.family?.let(ExtractionSchema.DEFAULT::family)
            val gate = rec.scores.getValue(ActionQuestions.anything(family?.description))
            val done = rec.scores.getValue(ActionQuestions.done())
            val base = gateBase[rec.family.orEmpty()] ?: gateBase[""] ?: 0.0
            line(
                String.format(
                    Locale.ROOT, "%-30s %-18s gate=%s over=%s | done=%s over=%s | %s also=%s", rec.key, rec.family, f(gate), f(gate - base), f(done), f(done - doneBase),
                    doc?.actions?.joinToString(",") { it.kind }, doc?.actionsAlsoOk?.joinToString(","),
                ),
            )
        }
        line()

        val fixed = kindsBase.copy(anyThreshold = NEG, doneThreshold = Double.POSITIVE_INFINITY, gateBaseline = emptyMap(), doneBaseline = 0.0)
        line("## no gate at all (kinds only), the shipped kind floor and margin")
        line(ActionKindEval.run(recordings, fixed).table())
        line()
        line("## the gate with the family context, raw (no baseline), threshold t")
        for (t in listOf(-1.0, -0.5, 0.0, 0.5, 1.0, 1.5, 2.0)) line(String.format(Locale.ROOT, "t=%5.2f : ", t) + ActionKindEval.run(recordings, fixed.copy(anyThreshold = t)).table())
        line()
        line("## the gate over its baseline, margin t")
        for (t in listOf(-1.0, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, 1.0, 1.5)) {
            val p = fixed.copy(anyThreshold = t, gateBaseline = gateBase)
            val r = ActionKindEval.run(recordings, p)
            line(String.format(Locale.ROOT, "t=%5.2f : ", t) + r.table() + " | paymentsKept=" + r.paymentsKept())
        }
        line()
        line("## plus the second gate (completed already?), over its baseline, threshold d, with the gate margin t")
        for (t in listOf(NEG, -0.5, 0.0, 0.25, 0.5)) for (d in listOf(-0.5, -0.25, 0.0, 0.25, 0.5, 1.0)) {
            val p = fixed.copy(anyThreshold = t, gateBaseline = gateBase, doneBaseline = doneBase, doneThreshold = d)
            val r = ActionKindEval.run(recordings, p)
            line(String.format(Locale.ROOT, "t=%5.2f d=%5.2f : ", t, d) + r.table() + " | paymentsKept=" + r.paymentsKept())
        }

        val out = File("build/reports/action-kinds-tune.txt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}
