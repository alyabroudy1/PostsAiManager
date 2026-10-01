package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.v2.LegacyTypes
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
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
    private val schema = ExtractionSchema.V2

    private val expected = mapOf(
        "N1-mahnung-telco-qr-1p" to setOf("invoice_bill"),
        "N2-kfz-verlaengerung-2p" to setOf("contract_policy"),
        "N3-schule-familie-2p" to setOf("official_letter"),
        "N4-zhd-firma-1p" to setOf("contract_policy", "free_form"),
        "N5-co-familie-1p" to setOf("invoice_bill"),
        "N6-nebenkosten-3p" to setOf("invoice_bill"),
        "N7-beitragsservice-1p" to setOf("invoice_bill", "official_letter"),
        "N8-info-bank-noaction-1p" to setOf("official_letter"),
        "N9-fuzzy-name-1p" to setOf("invoice_bill"),
        "N10-kinderarzt-termin-1p" to setOf("medical"),
        "invoice-2p" to setOf("invoice_bill"),
        "tax-long-7p" to setOf("official_letter"),
        "receipt-noise-1p" to setOf("receipt"),
    )

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
