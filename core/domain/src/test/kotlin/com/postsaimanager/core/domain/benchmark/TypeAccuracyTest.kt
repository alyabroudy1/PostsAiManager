package com.postsaimanager.core.domain.benchmark

import com.google.common.truth.Truth.assertThat
import com.postsaimanager.core.domain.extraction.v2.DocDirection
import com.postsaimanager.core.domain.extraction.v2.ExtractionSchema
import com.postsaimanager.core.domain.extraction.zones.ModelProfiles
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The document type on the recorded type scores of the scoring reader, before and after the candidate set depends on the
 * document's direction. The recordings hold one score per type of the schema as it was then (all 11 types); "before" is the
 * argmax over all of them, "after" is the real interpreter replayed on the same scores with an incoming document, which is
 * offered [ExtractionSchema.typesFor] only. Writes `$TYPE_OUT` when set.
 *
 * The expected types are the letters' own kinds (the manifest's `document_type` for the N letters, the plain kind of the
 * three older ones); several are accepted where the letter is honestly either ("Angebot / Vertragsverlängerung").
 */
class TypeAccuracyTest {

    private val docs = BenchmarkFixtures.load().docs
    private val recordings = Recordings.load(File("src/test/resources/benchmark/recordings"))
    private val profile = ModelProfiles.QWEN35_08B.scoring

    private val expected = mapOf(
        "N1-mahnung-telco-qr-1p" to setOf("reminder_dunning"),
        "N2-kfz-verlaengerung-2p" to setOf("insurance_contract"),
        "N3-schule-familie-2p" to setOf("school"),
        "N4-zhd-firma-1p" to setOf("insurance_contract", "other"),
        "N5-co-familie-1p" to setOf("bill", "reminder_dunning"),
        "N6-nebenkosten-3p" to setOf("bill"),
        "N7-beitragsservice-1p" to setOf("bill", "reminder_dunning", "authority_tax"),
        "N8-info-bank-noaction-1p" to setOf("info_no_action"),
        "N9-fuzzy-name-1p" to setOf("bill"),
        "N10-kinderarzt-termin-1p" to setOf("health"),
        "invoice-2p" to setOf("bill"),
        "tax-long-7p" to setOf("authority_tax"),
        "receipt-noise-1p" to setOf("receipt"),
    )

    private class Row(val key: String, val before: String, val after: String?, val ok: Set<String>)

    private fun rows(): List<Row> {
        val all = ExtractionSchema.DEFAULT.types.filter { it.description.isNotBlank() }
        val incoming = ExtractionSchema.DEFAULT.typesFor(DocDirection.INCOMING).filter { it.description.isNotBlank() }
        return docs.mapNotNull { (m, f) ->
            val ok = expected[m.key] ?: return@mapNotNull null
            val rec = recordings.firstOrNull { it.key == m.key && it.variant == InterpreterMetrics.SCORING_VARIANT } ?: return@mapNotNull null
            val batch = rec.asks.first { it.name == "score:type" }
            val scores = batch.answer!!.split(',').map { it.trim().toDouble() }
            // A recording made before the fix scored all types; one made after scored only those an incoming document can be.
            val scored = when (scores.size) {
                all.size -> all
                incoming.size -> incoming
                else -> error("the recording was made with ${scores.size} types")
            }
            val before = scored[scores.indices.maxByOrNull { scores[it] }!!].id
            Row(m.key, before, InterpreterMetrics.replayResult(rec, f, profile).documentType?.id, ok)
        }
    }

    @Test
    fun `an incoming document is never typed as something the user sent or paid`() {
        val rows = rows()
        assertThat(rows).isNotEmpty()
        val offered = ExtractionSchema.DEFAULT.typesFor(DocDirection.INCOMING).map { it.id }
        assertThat(offered).containsNoneOf("outgoing_letter", "payment_proof")
        assertThat(offered).contains("other")
        for (r in rows) assertThat(offered).contains(r.after)
        assertThat(rows.count { it.after in it.ok }).isAtLeast(rows.count { it.before in it.ok })
        val out = System.getenv("TYPE_OUT") ?: return
        val sb = StringBuilder("# Type accuracy on the recorded type scores (${rows.size} letters)\n\n")
        sb.appendLine("| letter | expected | before (all 11 types) | after (incoming types) |\n|---|---|---|---|")
        for (r in rows) sb.appendLine("| ${r.key} | ${r.ok.joinToString("/")} | ${r.before}${if (r.before in r.ok) "" else " (wrong)"} | ${r.after}${if (r.after in r.ok) "" else " (wrong)"} |")
        sb.appendLine("\nbefore ${rows.count { it.before in it.ok }}/${rows.size}, after ${rows.count { it.after in it.ok }}/${rows.size}")
        File(out).writeText(sb.toString())
    }
}
