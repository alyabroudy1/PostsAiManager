package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import com.postsaimanager.core.domain.extraction.zones.ScoringProfile
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The document family on the recorded type scores of the scoring reader, read through [LegacyTypes].
 *
 * The recordings were made before extraction-v2-2, so they hold one score per legacy type, not per family. The legacy view gives
 * a family the best score of the legacy types that map onto it (`invoice_bill` is the better of `bill` and `reminder_dunning`;
 * `official_letter` the best of `authority_tax`, `school` and `info_no_action`), and the abstain outcome [ExtractionSchema.FREE_FORM]
 * replaces the scored `other`: it is chosen when the best family score is not above the `family` threshold of the profile.
 * Legacy types that are no family of an incoming document (`other`, `outgoing_letter`, `payment_proof`) contribute nothing.
 * P4 re-records the family scores on the device; until then this keeps the accuracy measurable. Writes `$FAMILY_OUT` when set.
 *
 * The expected families are the letters' own kinds, remapped from the old type expectations; several are accepted where the
 * letter is honestly either ("Angebot / Vertragsverlängerung", "Rundfunkbeitrag").
 */
class FamilyAccuracyTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings"))
    private val threshold = ModelProfiles.QWEN35_08B.scoring.familyThreshold
    private val schema = ExtractionSchema.DEFAULT

    private val expected = FamilyExpectations.BY_KEY

    private companion object {
        /** The families the device recordings scored for an incoming document: the registry's first nine, in order. */
        const val RECORDED_FAMILIES = 9
    }

    /** The scored batch of one letter as family scores (legacy view), or null when the letter has no expectation or recording. */
    private class Row(val key: String, val familyScores: Map<String, Double>, val ok: Set<String>)

    private fun rows(): List<Row> {
        val scoredFamilies = schema.familiesFor(DocDirection.INCOMING).map { it.id }.toSet()
        return docs.mapNotNull { (m, _) ->
            val ok = expected[m.key] ?: return@mapNotNull null
            val rec = recordings.firstOrNull { it.key == m.key && it.variant == InterpreterMetrics.SCORING_VARIANT } ?: return@mapNotNull null
            // A recording made before the direction fix scored all types; one made after scored only those an incoming document can be.
            val view = LegacyFamilyBridge.viewOf(rec) ?: error("${rec.key}.${rec.variant} holds no legacy type scores")
            Row(m.key, view.familyScores.filterKeys { it in scoredFamilies }, ok)
        }
    }

    /** The family the classifier's decision rule gives [row] at [t]: the argmax, or the abstain family when it is not above [t]. */
    private fun decide(row: Row, t: Double): String {
        val best = row.familyScores.maxByOrNull { it.value } ?: return "free_form"
        return if (best.value > t) best.key else "free_form"
    }

    @Test
    fun `the abstain family is never one of the scored families`() {
        for (direction in DocDirection.entries) assertThat(schema.familiesFor(direction).map { it.id }).doesNotContain("free_form")
        assertThat(schema.family("free_form")).isNotNull()
    }

    @Test
    fun `every manifest letter states its topics, all known to the schema, and only the children's doctor letter is sensitive`() {
        val sixteen = docs.filter { !it.first.web }.map { it.first }
        assertThat(sixteen).hasSize(16)
        for (m in sixteen) for (t in m.topics) assertThat(schema.topic(t)).isNotNull()
        val sensitive = sixteen.filter { m -> m.topics.any { schema.topic(it)?.sensitive == true } }.map { it.key }
        assertThat(sensitive).containsExactly("N10-kinderarzt-termin-1p")
    }

    @Test
    fun `family accuracy on the device recordings of the families is the measured 9 of 13, the target of 11 is not met`() {
        val (right, _, total) = deviceReplay(ModelProfiles.QWEN35_08B.scoring)
        assertThat(total).isEqualTo(13)
        // The device recordings (P4) scored ten incoming families; email_printout, the tenth, is no family any more, so its column is dropped
        // (it is the last scored one) and the replay decides among the nine that remain: 9 of 13, as before. The architecture's target was
        // 11 of 13; the misses are letters the 0.8B model scores nearest to official_letter (N2, N4) or to another family (N6, the tax
        // letter). This test pins that replay, so an improvement or a regression is a visible change.
        assertThat(right).isEqualTo(9)
    }

    /**
     * The category decision (extraction-v2-16: nine broad categories, scored last) on the same recordings. A category's score is the best
     * recorded score of the families that stand for it ([com.postsaimanager.core.domain.extraction.v2.DocCategory.families], among the nine
     * recorded columns: "medical" and "ticket_booking" for the appointment, "certificate_id" for the notice); a category none of whose
     * families was recorded (the message) has no score and cannot win. This is an offline stand-in for what the categories would score, not
     * a measurement of the new questions, which only a device recording holds. An expectation is accepted when the decided category is the
     * category of an accepted family.
     */
    private fun categoryReplay(profile: ScoringProfile): Replay {
        val recorded = schema.familiesFor(DocDirection.INCOMING).take(RECORDED_FAMILIES).map { it.id }
        val categories = schema.categories
        var right = 0
        var abstained = 0
        var total = 0
        for (rec in recordings.filter { it.variant == "zonesscoring3" }) {
            val ok = expected[rec.key] ?: continue
            val scores = rec.asks.first { it.name == "score:family" }.answer!!.split(',').map { it.trim().toDouble() }.take(recorded.size)
            val byCategory = categories.map { c ->
                c.families.mapNotNull { id -> recorded.indexOf(id).takeIf { it >= 0 }?.let { scores[it] } }.maxOrNull() ?: LegacyFamilyBridge.NOT_RECORDED
            }
            val decided = profile.familyWinner(byCategory)?.let { categories[it].id }
            val accepted = ok.map { schema.categoryOf(it)?.id ?: "free_form" }.toSet()
            total++
            if ((decided ?: "free_form") in accepted) right++ else if (decided == null) abstained++
        }
        return Replay(right, abstained, total)
    }

    @Test
    fun `family accuracy at the category level on the device recordings is the measured 9 of 13 as well`() {
        val before = deviceReplay(ModelProfiles.QWEN35_08B.scoring)
        val after = categoryReplay(ModelProfiles.QWEN35_08B.scoring)
        assertThat(after.total).isEqualTo(13)
        // The nine categories are right on as many letters as the nine families were: the stand-in scores of the appointment (medical) and the
        // notice (certificate) keep their letters, and no letter was right only because of a family that is now a category's alias.
        assertThat(after.right).isAtLeast(before.right)
        val out = System.getenv("FAMILY_OUT")
        if (out != null) {
            File("$out.category").writeText(
                "# right / neutral Document / wrong, of 13, on the device recordings\n" +
                    "families (before): ${before.right} / ${before.abstained} / ${before.wrong}\n" +
                    "categories (after): ${after.right} / ${after.abstained} / ${after.wrong}\n",
            )
        }
    }

    /**
     * The decision of [profile] on the device recordings: how many of the 13 letters with an expectation get an accepted family. The
     * recordings hold the nine families of extraction-v2-2 (a tenth, email_printout, is dropped); the families added since were never
     * recorded, so only the first [RECORDED_FAMILIES] columns are replayed, among the families those columns were scored for.
     */
    private fun deviceReplay(profile: ScoringProfile): Replay {
        val scored = schema.familiesFor(DocDirection.INCOMING).take(RECORDED_FAMILIES)
        val real = recordings.filter { it.variant == "zonesscoring3" }
        assertThat(real).hasSize(16)
        var right = 0
        var abstained = 0
        var total = 0
        for (rec in real) {
            val ok = expected[rec.key] ?: continue
            val scores = rec.asks.first { it.name == "score:family" }.answer!!.split(',').map { it.trim().toDouble() }.take(scored.size)
            val decided = profile.familyWinner(scores)?.let { scored[it].id } ?: "free_form"
            total++
            if (decided in ok) right++ else if (decided == "free_form") abstained++
        }
        return Replay(right, abstained, total)
    }

    /** [right] letters got an accepted family, [abstained] got the neutral "Document" instead, the rest ([wrong]) a family that is not accepted. */
    private data class Replay(val right: Int, val abstained: Int, val total: Int) {
        val wrong: Int get() = total - right - abstained
    }

    @Test
    fun `a minimum lead over the runner-up trades wrong types for the neutral Document, measured on the device recordings`() {
        val shipped = ModelProfiles.QWEN35_08B.scoring
        val sweep = listOf(0.0, 0.05, 0.1, 0.15, 0.2, 0.3, 0.4, 0.5, 0.75, 1.0).associateWith { deviceReplay(shipped.copy(familyMinMargin = it)) }
        val out = System.getenv("FAMILY_OUT")
        if (out != null) {
            File("$out.margin").writeText(
                "# lead over the runner-up: right / neutral Document / wrong, of 13\n" +
                    sweep.entries.joinToString("\n") { "${it.key}: ${it.value.right} / ${it.value.abstained} / ${it.value.wrong}" } + "\n",
            )
        }
        // The shipped margin never loses a right answer to the neutral Document that the unmargined decision had.
        assertThat(deviceReplay(shipped).right).isAtLeast(sweep.getValue(0.0).right)
    }

    @Test
    fun `family accuracy on the recorded scores read through the legacy types is at least 9 of 13`() {
        val rows = rows()
        assertThat(rows).hasSize(expected.size)
        val right = rows.count { decide(it, threshold) in it.ok }
        assertThat(right).isAtLeast(9)
        val out = System.getenv("FAMILY_OUT") ?: return
        val sb = StringBuilder("# Family accuracy on the recorded type scores, legacy view (${rows.size} letters, threshold $threshold)\n\n")
        sb.appendLine("| letter | expected | decided | best score |\n|---|---|---|---|")
        for (r in rows) {
            val d = decide(r, threshold)
            sb.appendLine("| ${r.key} | ${r.ok.joinToString("/")} | $d${if (d in r.ok) "" else " (wrong)"} | ${"%.2f".format(r.familyScores.values.max())} |")
        }
        sb.appendLine("\n$right/${rows.size}\n\n| threshold | right |\n|---|---|")
        for (t in listOf(-1.0, -0.5, -0.25, -0.1, 0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.8)) sb.appendLine("| $t | ${rows.count { decide(it, t) in it.ok }} |")
        File(out).writeText(sb.toString())
    }
}
